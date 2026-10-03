import json
from collections.abc import Iterator
from datetime import timedelta

from django.db import transaction
from django.http import StreamingHttpResponse
from django.shortcuts import get_object_or_404
from django.utils import timezone
from django.utils.dateparse import parse_date
from rest_framework.exceptions import NotFound, PermissionDenied, ValidationError
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from accounts.models import User
from accounts.serializers import UserSerializer
from activities.models import Activity, BestEffort
from activities.serializers import BestEffortSerializer
from core.auth_context import get_effective_athlete_id
from core.derived import DEFAULT_FITNESS_WINDOW_DAYS, compute_fitness_series
from core.permissions import user_may_read, user_may_write

from . import threshold_suggestions
from .models import BestEffortRecomputeJob, ThresholdHistory, ZoneSet
from .serializers import (
    AthleteUpdateSerializer,
    BestEffortRecomputeJobSerializer,
    FitnessPointSerializer,
    ZoneSetReplaceSerializer,
    ZoneSetSerializer,
)
from .tasks import run_best_effort_recompute
from .threshold_history import FIELD_SPORT, is_stale, rebuild_history_stream, record_manual_value, refresh_field
from .zones import ZONE_TYPES, get_or_create_zone_set, reference_for, zone_types_affected_by

# Settings whose change can affect which Threshold suggestions are current (window/sanity/warning
# lead time all feed detection directly; ftp_calculation_method changes which window ftp's own
# candidates are drawn from) - checked against AthleteDetailView.patch's validated_data to decide
# whether a cache invalidation is needed on top of the one record_manual_value's own ftp/
# critical_run_power/threshold_pace writes already trigger.
_SUGGESTION_AFFECTING_SETTINGS = {
    "threshold_window_days",
    "threshold_sanity_pct",
    "threshold_warning_days",
    "ftp_calculation_method",
}

# 4w/16w match BEST_EFFORT_TRIM_PERIOD_DAYS in uploads/processing.py exactly - the Best Efforts
# screen used to fetch the wider 3m/1y bucket and narrow it client-side to 28/112 days, but
# capping to top-N happens per the FETCHED bucket's own value ranking (see _cap_per_window), so
# narrowing afterwards could drop entries that were genuinely top-N within the narrower window
# but not within the top-N of the wider one it was fetched from (seen live: a 1km window with
# plenty of trimmed history over the year showed only 3 of the last 16 weeks' true top-10,
# because the fastest-of-the-year 10 happened to mostly predate that window). Querying the exact
# period directly avoids the mismatch.
BEST_EFFORT_PERIOD_DAYS = {"4w": 28, "3m": 90, "16w": 112, "1y": 365}
LOWER_IS_BETTER_KINDS = {"running_pace"}


def cap_per_window(efforts: list[BestEffort], lower_is_better: bool, top_n: int) -> list[BestEffort]:
    """Trim retains up to top_n rows per window in EACH tracked period independently (see
    _trim_kind_window in uploads/processing.py), so a single date-filtered read can still
    return more than top_n rows for one window - e.g. the top-10-of-112-days set and the
    top-10-of-365-days set can differ, and a query spanning both periods sees their union.
    This re-caps to the true top N by value (respecting direction) before returning,
    preserving the window-asc/value-desc order callers expect.
    """
    if top_n <= 0:  # 0 = unlimited, matching _trim_kind_window's own "0 = keep all"
        return efforts
    by_window: dict[str, list[BestEffort]] = {}
    for effort in efforts:
        by_window.setdefault(effort.window, []).append(effort)
    capped: list[BestEffort] = []
    for window_efforts in by_window.values():
        window_efforts.sort(key=lambda e: e.value, reverse=not lower_is_better)
        capped.extend(window_efforts[:top_n])
    capped.sort(key=lambda e: (e.window, -e.value))
    return capped


def _require_read(request: Request, athlete_id: str) -> None:
    sub, _ = get_effective_athlete_id(request)
    if not user_may_read(sub, athlete_id):
        raise PermissionDenied("You do not have access to that athlete's data.")


def _require_write(request: Request, athlete_id: str) -> None:
    sub, _ = get_effective_athlete_id(request)
    if not user_may_write(sub, athlete_id):
        raise PermissionDenied("You do not have write access to that athlete's data.")


class AthleteDetailView(APIView):
    def get(self, request: Request, id: str) -> Response:
        _require_read(request, id)
        athlete = get_object_or_404(User, pk=id)
        return Response(UserSerializer(athlete).data)

    def patch(self, request: Request, id: str) -> Response:
        _require_write(request, id)
        athlete = get_object_or_404(User, pk=id)

        serializer = AthleteUpdateSerializer(athlete, data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)
        with transaction.atomic():
            serializer.save()
            # A manually-entered threshold functions as an initial value (or a correction) just
            # like any other ledger entry - see threshold_history.py::record_manual_value. The
            # Preferences form resubmits every field on every save regardless of whether it was
            # edited, so record_manual_value's own no-op-if-unchanged check is load-bearing here.
            for field in FIELD_SPORT:
                if field in serializer.validated_data:
                    record_manual_value(athlete, field, serializer.validated_data[field])
            # A manual threshold edit above already changes what's current (so suggestions could
            # change too); a change to one of the detection-affecting settings can change the
            # suggestion list even when no threshold value itself was touched this request.
            if set(serializer.validated_data) & (set(FIELD_SPORT) | _SUGGESTION_AFFECTING_SETTINGS):
                threshold_suggestions.invalidate(athlete.id)

        recomputed = zone_types_affected_by(serializer.validated_data.keys())
        existing = set(ZoneSet.objects.filter(athlete=athlete, type__in=recomputed).values_list("type", flat=True))

        data = UserSerializer(athlete).data
        data["zones_recomputed"] = [zt for zt in recomputed if zt in existing]
        return Response(data)


class ZoneSetListView(APIView):
    def get(self, request: Request, id: str) -> Response:
        _require_read(request, id)
        athlete = get_object_or_404(User, pk=id)
        zone_sets = [get_or_create_zone_set(athlete, zone_type) for zone_type in ZONE_TYPES]

        # Optional: scope bike_power/run_power/pace's reference to one activity's own threshold
        # snapshot instead of the athlete's current profile - see zones.py::reference_for. Must
        # belong to this same athlete, same ownership check as any other athlete-scoped read.
        activity_id = request.query_params.get("activity_id")
        activity = None
        if activity_id:
            activity = get_object_or_404(Activity, pk=activity_id, athlete_id=id)

        return Response({"data": ZoneSetSerializer(zone_sets, many=True, context={"activity": activity}).data})


class ZoneSetDetailView(APIView):
    def put(self, request: Request, id: str, type: str) -> Response:
        if type not in ZONE_TYPES:
            raise ValidationError(
                {"error": {"type": "invalid_request_error", "param": "type", "message": "Unknown zone type."}}
            )
        _require_write(request, id)
        athlete = get_object_or_404(User, pk=id)

        serializer = ZoneSetReplaceSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)

        ZoneSet.objects.update_or_create(
            athlete=athlete, type=type, defaults={"zones": serializer.validated_data["zones"]}
        )
        return Response({"type": type, "reference": reference_for(athlete, type), "updated": True})


class BestEffortListView(APIView):
    def get(self, request: Request, id: str) -> Response:
        _require_read(request, id)

        kind = request.query_params.get("kind")
        if kind not in dict(BestEffort.KIND_CHOICES):
            raise ValidationError(
                {"kind": "Must be one of cycling_hr, cycling_power, running_hr, running_pace, running_power."}
            )

        period = request.query_params.get("period", "all")
        if period not in ("4w", "3m", "16w", "1y", "all"):
            raise ValidationError({"period": "Must be one of 4w, 3m, 16w, 1y, all."})

        qs = BestEffort.objects.filter(athlete_id=id, kind=kind).order_by("window", "-value")
        if period in BEST_EFFORT_PERIOD_DAYS:
            cutoff = timezone.now().date() - timedelta(days=BEST_EFFORT_PERIOD_DAYS[period])
            qs = qs.filter(date__gte=cutoff)

        athlete = get_object_or_404(User, pk=id)
        capped = cap_per_window(list(qs), kind in LOWER_IS_BETTER_KINDS, athlete.best_effort_top_n)

        return Response({"kind": kind, "period": period, "data": BestEffortSerializer(capped, many=True).data})


# The 4w/3m periods are deliberately excluded here (unlike BEST_EFFORT_PERIOD_DAYS above) - they
# flag almost everything recent, which is exactly what the Dashboard "Top efforts this week" card
# is trying to avoid drowning the athlete in. See ActivityBestEffortRanksView's docstring.
RECENT_TOP_EFFORT_PERIODS = ("16w", "1y", "all")


class ActivityBestEffortRanksView(APIView):
    """Backs the Dashboard's "Top efforts this week" card: for a caller-supplied batch of
    activity ids, which ones were good enough to rank top-3 in any of the athlete's best-effort
    leaderboards (16-week, 1-year, or all-time - 4w/3m excluded, see RECENT_TOP_EFFORT_PERIODS).

    Deliberately takes activity ids rather than a "how many days back" parameter: "which
    activities count as recent" is a local-date/timezone-sensitive judgement the frontend
    already has to make correctly for its own display (the This Week calendar), so it's the
    caller's job to decide that and pass in exactly the activities it cares about - this view
    only ever answers "what are these specific activities' ranks", with no date logic of its
    own to drift out of sync with the frontend's.

    An "entry" is one activity x one best-effort category (kind + window). Only kinds/windows
    that at least one of the given activities actually holds a BestEffort row for are ever
    queried at all - for each of those, this takes the exact same (capped, top-N) row set
    BestEffortListView would return for that period, ranks it by value (respecting direction),
    and keeps the ranks belonging to the requested activities. The same (activity, kind,
    window) triple can appear across multiple periods - its `ranks` dict accumulates one entry
    per period it showed up in, `None` for a period it didn't reach the top-N of at all (not "0
    rank", a real absence). Only entries with at least one period's rank <= 3 are returned -
    sorting/grouping/headline-period selection all happen client-side (see the design handoff's
    TopEffortsCard.tsx).

    Retention caveat: trim keeps best_effort_top_n rows per window *per period* independently
    (see cap_per_window's own docstring), so ranks 1-3 are always recoverable as long as
    best_effort_top_n >= 3. An athlete with it set to 1-2 just gets ranked out of whatever
    smaller set actually exists - not an error case, nothing extra to handle here.
    """

    def get(self, request: Request, id: str) -> Response:
        _require_read(request, id)
        athlete = get_object_or_404(User, pk=id)

        activity_ids = [a for a in request.query_params.get("activity_ids", "").split(",") if a]
        if not activity_ids:
            return Response({"data": []})

        # Only the (kind, window) pairs these specific activities actually hold a row for are
        # worth ranking at all - a kind none of them touched needs no query, and a window one
        # of them touched still needs every *other* athlete row for that window to rank
        # correctly against, hence the second, unfiltered-by-activity query below.
        target_rows = list(BestEffort.objects.filter(athlete_id=id, activity_id__in=activity_ids))
        if not target_rows:
            return Response({"data": []})
        target_ids = {row.activity_id for row in target_rows}
        windows_by_kind: dict[str, set[str]] = {}
        for row in target_rows:
            windows_by_kind.setdefault(row.kind, set()).add(row.window)

        entries: dict[tuple[str, str, str], dict] = {}
        for kind, windows_needed in windows_by_kind.items():
            lower_is_better = kind in LOWER_IS_BETTER_KINDS
            for period in RECENT_TOP_EFFORT_PERIODS:
                qs = BestEffort.objects.filter(athlete_id=id, kind=kind).select_related("activity")
                if period in BEST_EFFORT_PERIOD_DAYS:
                    cutoff = timezone.now().date() - timedelta(days=BEST_EFFORT_PERIOD_DAYS[period])
                    qs = qs.filter(date__gte=cutoff)
                capped = cap_per_window(list(qs), lower_is_better, athlete.best_effort_top_n)

                by_window: dict[str, list[BestEffort]] = {}
                for effort in capped:
                    if effort.window in windows_needed:
                        by_window.setdefault(effort.window, []).append(effort)

                for window_efforts in by_window.values():
                    # Rank order is direction-aware and computed fresh here, not inherited from
                    # cap_per_window's own return order - that function's final sort is always
                    # value-desc for display purposes, which is actually *reverse* rank order
                    # for a lower-is-better kind (pace); BestEffortsScreen.tsx has always had to
                    # re-sort ascending for exactly this reason (see RunPaceCard).
                    window_efforts.sort(key=lambda e: e.value, reverse=not lower_is_better)
                    for rank, effort in enumerate(window_efforts, start=1):
                        if effort.activity_id not in target_ids:
                            continue
                        key = (effort.activity_id, effort.kind, effort.window)
                        entry = entries.get(key)
                        if entry is None:
                            entry = {
                                "activity_id": effort.activity_id,
                                "date": effort.date.isoformat(),
                                "sport": effort.activity.sport,
                                "kind": effort.kind,
                                "window": effort.window,
                                "value": effort.value,
                                "unit": effort.unit,
                                "ranks": dict.fromkeys(RECENT_TOP_EFFORT_PERIODS),
                            }
                            entries[key] = entry
                        entry["ranks"][period] = rank

        data = [e for e in entries.values() if any(r is not None and r <= 3 for r in e["ranks"].values())]
        return Response({"data": data})


class FitnessListView(APIView):
    def get(self, request: Request, id: str) -> Response:
        _require_read(request, id)

        to_param = request.query_params.get("to")
        to_date = parse_date(to_param) if to_param else timezone.now().date()
        if to_date is None:
            raise ValidationError({"to": "Must be a date in YYYY-MM-DD format."})

        from_param = request.query_params.get("from")
        from_date = parse_date(from_param) if from_param else to_date - timedelta(days=DEFAULT_FITNESS_WINDOW_DAYS)
        if from_date is None:
            raise ValidationError({"from": "Must be a date in YYYY-MM-DD format."})

        if from_date > to_date:
            raise ValidationError({"from": "Must not be after 'to'."})

        series = compute_fitness_series(id, from_date, to_date)
        return Response({"data": FitnessPointSerializer(series, many=True).data})


class RecomputeAthleteTssView(APIView):
    def post(self, request: Request, id: str) -> Response:
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        athlete = get_object_or_404(User, pk=id)

        from activities.models import Activity
        from uploads.processing import compute_normalized_power, compute_tss

        candidates = Activity.objects.filter(
            athlete_id=id,
            parent_activity__isnull=True,
        ).exclude(sport__in=("multisport", "transition"))

        updated = 0
        for activity in candidates:
            power_series = list(activity.records.order_by("t").values_list("power", flat=True))
            hr_series = list(activity.records.order_by("t").values_list("heartrate", flat=True))
            norm_power = compute_normalized_power(power_series) if any(p is not None for p in power_series) else None
            new_tss = compute_tss(activity, athlete, norm_power, hr_series)
            if new_tss != activity.tss:
                activity.tss = new_tss
                activity.save(update_fields=["tss"])
                updated += 1

        return Response({"updated": updated})


def _recompute_stats_stream(athlete: User) -> Iterator[str]:
    from uploads.processing import backfill_extended_stats

    candidates = Activity.objects.filter(
        athlete=athlete,
        parent_activity__isnull=True,
    ).exclude(sport__in=("multisport", "transition"))

    activities = list(candidates.order_by("start_date"))
    total = len(activities)
    updated = 0

    for i, activity in enumerate(activities):
        update_fields = backfill_extended_stats(activity, athlete)
        if update_fields:
            activity.save(update_fields=update_fields)
            updated += 1
        yield f"data: {json.dumps({'current': i + 1, 'total': total})}\n\n"

    yield f"event: done\ndata: {json.dumps({'updated': updated})}\n\n"


class RecomputeAthleteStatsView(APIView):
    """Bulk equivalent of activities.views.RecomputeActivityStatsView - backfills max
    power, cadence, elevation, calories, and TRIMP across every one of the athlete's
    activities from stored Record rows, for activities ingested before that computation
    existed (or, e.g., recorded with a since-fixed formula). Streamed like
    BestEffortRecomputeView, not a single synchronous response: an athlete can have
    thousands of activities, each with its own per-second Record rows, and a single
    multi-minute request with no progress feedback risks a client/proxy timeout with
    nothing to show for it (seen live on the Java backend's now-fixed synchronous
    equivalent, against 2,600+ activities).
    """

    def post(self, request: Request, id: str) -> StreamingHttpResponse:
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        athlete = get_object_or_404(User, pk=id)
        response = StreamingHttpResponse(_recompute_stats_stream(athlete), content_type="text/event-stream")
        response["Cache-Control"] = "no-cache"
        response["X-Accel-Buffering"] = "no"
        return response


def _recompute_curves_stream(athlete: User) -> Iterator[str]:
    from uploads.processing import _write_duration_curves

    candidates = (
        Activity.objects.filter(athlete=athlete, parent_activity__isnull=True)
        .exclude(sport__in=("multisport", "transition"))
        .order_by("start_date")
    )

    activities = list(candidates)
    total = len(activities)

    for i, activity in enumerate(activities):
        records = list(activity.records.order_by("t").values("power", "heartrate"))
        if records:
            power_series = [r["power"] for r in records]
            hr_series = [r["heartrate"] for r in records]
            _write_duration_curves(activity, power_series, hr_series)
        yield f"data: {json.dumps({'current': i + 1, 'total': total})}\n\n"

    yield f"event: done\ndata: {json.dumps({'processed': total})}\n\n"


class RecomputeAthleteCurvesView(APIView):
    """Backfills duration curves for every eligible activity - mirrors
    backend_java's DurationCurveRecomputeService/CurveController#recompute. Nothing else ever
    computes a duration curve for an activity that already exists (in particular, ImportReader-
    equivalent restores raw Record rows but never recomputes curves from them), so every
    activity restored from an export has none until this runs. Streamed like
    RecomputeAthleteStatsView, same reasoning.
    """

    def post(self, request: Request, id: str) -> StreamingHttpResponse:
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        athlete = get_object_or_404(User, pk=id)
        response = StreamingHttpResponse(_recompute_curves_stream(athlete), content_type="text/event-stream")
        response["Cache-Control"] = "no-cache"
        response["X-Accel-Buffering"] = "no"
        return response


_THRESHOLD_FIELDS = ("ftp", "critical_run_power", "threshold_pace")


def _validate_threshold_field(field: str | None) -> str:
    if field not in _THRESHOLD_FIELDS:
        raise ValidationError({"field": "Must be one of ftp, critical_run_power, threshold_pace."})
    return field


def threshold_summary_for_field(athlete: User, field: str) -> dict:
    entries = list(ThresholdHistory.objects.filter(athlete=athlete, field=field).order_by("-effective_from")[:2])
    if not entries:
        return {
            "value": None,
            "previous_value": None,
            "source_activity_id": None,
            "effective_from": None,
            "stale": True,
        }
    current, previous = entries[0], entries[1] if len(entries) > 1 else None

    def _value(entry: ThresholdHistory) -> int | str:
        return entry.value_pace if field == "threshold_pace" else entry.value_numeric

    return {
        "value": _value(current),
        "previous_value": _value(previous) if previous is not None else None,
        "source_activity_id": current.source_activity_id,
        "effective_from": current.effective_from,
        "stale": is_stale(athlete, field),
    }


class AthleteThresholdsView(APIView):
    """GET /v1/athletes/<id>/thresholds - current FTP/critical_run_power/threshold_pace plus
    each one's previous value and whether its source activity has aged out of the athlete's
    rolling window (see athletes/threshold_history.py). A plain cache read plus a date
    comparison - no recompute happens here, so it's cheap enough for the dashboard to call on
    every view. A stale field is never silently corrected; the dashboard offers a manual
    refresh (see RefreshThresholdView) instead."""

    def get(self, request: Request, id: str) -> Response:
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, id):
            raise PermissionDenied("You do not have access to that athlete's data.")
        athlete = get_object_or_404(User, pk=id)
        return Response({field: threshold_summary_for_field(athlete, field) for field in _THRESHOLD_FIELDS})


class ThresholdHistoryListView(APIView):
    """GET /v1/athletes/<id>/threshold-history?field=... - the full ledger for one field, most
    recent first, each entry linking to the activity whose effort set it. Backs the history
    screen the dashboard's per-field links lead to."""

    def get(self, request: Request, id: str) -> Response:
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, id):
            raise PermissionDenied("You do not have access to that athlete's data.")
        athlete = get_object_or_404(User, pk=id)
        field = _validate_threshold_field(request.query_params.get("field"))

        entries = ThresholdHistory.objects.filter(athlete=athlete, field=field).order_by("-effective_from")
        data = [
            {
                "value": entry.value_pace if field == "threshold_pace" else entry.value_numeric,
                "source_activity_id": entry.source_activity_id,
                "effective_from": entry.effective_from,
                "current_from": entry.current_from,
            }
            for entry in entries
        ]
        return Response({"field": field, "data": data})


class RefreshThresholdView(APIView):
    """POST /v1/athletes/<id>/thresholds/refresh?field=... - the dashboard's manual "refresh
    now" action for a stale field: re-runs the same cheap current-window computation the ingest
    hook uses, on demand rather than waiting for the next activity. Returns the updated summary
    for every field (same shape as AthleteThresholdsView), since a manual refresh is rare enough
    that recomputing all three is negligible and simplest for the frontend to consume."""

    def post(self, request: Request, id: str) -> Response:
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        athlete = get_object_or_404(User, pk=id)
        field = _validate_threshold_field(request.query_params.get("field"))

        refresh_field(athlete, field)
        athlete.refresh_from_db()
        threshold_suggestions.invalidate(athlete.id)
        return Response({f: threshold_summary_for_field(athlete, f) for f in _THRESHOLD_FIELDS})


def _recompute_threshold_history_stream(athlete: User, field: str) -> Iterator[str]:
    # `field` is only ever used to filter/branch, never echoed back into the emitted text -
    # same convention as the sibling _recompute_stream's `kind` above, which keeps a validated
    # but still request-controlled string out of the response body entirely (CodeQL flags the
    # dataflow from request.query_params through here as a reflected-XSS pattern even though
    # _validate_threshold_field's whitelist check already makes it safe).
    total = 0
    for current, total in rebuild_history_stream(athlete, field):
        yield f"data: {json.dumps({'current': current, 'total': total})}\n\n"
    threshold_suggestions.invalidate(athlete.id)
    yield f"event: done\ndata: {json.dumps({'total': total})}\n\n"


class RecomputeThresholdHistoryView(APIView):
    """POST /v1/athletes/<id>/recompute-threshold-history?field=... - rebuilds the entire
    history ledger for one field from scratch, replaying the athlete's activities oldest-first
    (see athletes/threshold_history.py::rebuild_history_stream). For bootstrapping history on an
    existing account, or after changing the window/sanity-check settings. Streamed like the
    other bulk recomputes - an athlete can have thousands of activities."""

    def post(self, request: Request, id: str) -> StreamingHttpResponse:
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        athlete = get_object_or_404(User, pk=id)
        field = _validate_threshold_field(request.query_params.get("field"))

        response = StreamingHttpResponse(
            _recompute_threshold_history_stream(athlete, field), content_type="text/event-stream"
        )
        response["Cache-Control"] = "no-cache"
        response["X-Accel-Buffering"] = "no"
        return response


class BestEffortExcludeView(APIView):
    def delete(self, request: Request, id: str, activity_id: str) -> Response:
        _require_read(request, id)
        kind = request.query_params.get("kind")
        if not kind or kind not in dict(BestEffort.KIND_CHOICES):
            raise ValidationError(
                {"kind": "Must be one of cycling_hr, cycling_power, running_hr, running_pace, running_power."}
            )
        BestEffort.objects.filter(athlete_id=id, kind=kind, activity_id=activity_id).delete()
        return Response(status=204)


class BestEffortRecomputeView(APIView):
    # Runs via Celery + polling, not a synchronous StreamingHttpResponse (the original
    # implementation) - a full account (thousands of activities) can take longer than
    # gunicorn's sync-worker timeout, which streaming alone doesn't avoid since the
    # request is still held open for the whole duration either way. See
    # athletes/tasks.py's run_best_effort_recompute for the actual work.
    def post(self, request: Request, id: str) -> Response:
        _require_write(request, id)
        athlete = get_object_or_404(User, pk=id)
        kind = request.query_params.get("kind") or None
        if kind and kind not in dict(BestEffort.KIND_CHOICES):
            raise ValidationError(
                {"kind": "Must be one of cycling_hr, cycling_power, running_hr, running_pace, running_power."}
            )

        job = BestEffortRecomputeJob.objects.create(athlete=athlete, kind=kind or "")
        run_best_effort_recompute.delay(job.id)

        response = Response(BestEffortRecomputeJobSerializer(job).data, status=202)
        response["Location"] = f"/v1/athletes/{id}/best-efforts/recompute/{job.id}"
        response["Retry-After"] = "5"
        return response


class BestEffortRecomputeJobDetailView(APIView):
    def get(self, request: Request, id: str, job_id: str) -> Response:
        job = get_object_or_404(BestEffortRecomputeJob, pk=job_id, athlete_id=id)
        _require_read(request, job.athlete_id)

        response = Response(BestEffortRecomputeJobSerializer(job).data)
        if job.status in ("queued", "processing"):
            response["Retry-After"] = "5"
        return response


class BestEffortTrimView(APIView):
    def post(self, request: Request, id: str) -> Response:
        _require_write(request, id)
        athlete = get_object_or_404(User, pk=id)
        from uploads.processing import trim_best_efforts

        trim_best_efforts(athlete)
        return Response(status=204)


class ThresholdSuggestionListView(APIView):
    """GET /v1/athletes/<id>/threshold-suggestions - a cached (24h, explicitly invalidated on
    anything that could change it - see threshold_suggestions.invalidate) list of actionable
    threshold suggestions: a qualifying effort the sanity band rejected ("rejected"), or a
    current value about to age out of the window with nothing dismissing it ("upcoming_drop"/
    "race_will_refresh"). See threshold_suggestions.py for the assembly logic."""

    def get(self, request: Request, id: str) -> Response:
        _require_read(request, id)
        athlete = get_object_or_404(User, pk=id)
        return Response({"data": threshold_suggestions.list_suggestions(athlete)})


class ThresholdSuggestionAcceptView(APIView):
    """POST .../threshold-suggestions/<suggestion_id>/accept - accepts a "rejected" candidate as
    real, recording it as a genuine ledger entry (bypassing the sanity band for this activity
    from now on). DELETE undoes it, reverting to the normal windowed recompute."""

    def post(self, request: Request, id: str, suggestion_id: str) -> Response:
        _require_write(request, id)
        athlete = get_object_or_404(User, pk=id)
        entry = threshold_suggestions.accept(athlete, suggestion_id)
        if entry is None:
            raise NotFound("No such suggestion.")
        return Response(
            {
                "value": entry.value_pace if entry.field == "threshold_pace" else entry.value_numeric,
                "source_activity_id": entry.source_activity_id,
                "effective_from": entry.effective_from,
                "current_from": entry.current_from,
            }
        )

    def delete(self, request: Request, id: str, suggestion_id: str) -> Response:
        _require_write(request, id)
        athlete = get_object_or_404(User, pk=id)
        field, activity_id = _parse_suggestion_id(suggestion_id, expected_kind="rejected")
        threshold_suggestions.undo_accept(athlete, field, activity_id)
        return Response(status=204)


class ThresholdSuggestionDismissView(APIView):
    """POST .../threshold-suggestions/<suggestion_id>/dismiss - silences one specific suggestion
    occurrence (see ThresholdSuggestionDismissal.key's docstring for what makes an occurrence
    "specific" - a dismissal never silences a genuinely different future one). DELETE undoes it."""

    def post(self, request: Request, id: str, suggestion_id: str) -> Response:
        _require_write(request, id)
        athlete = get_object_or_404(User, pk=id)
        if not threshold_suggestions.dismiss(athlete, suggestion_id):
            raise NotFound("No such suggestion.")
        return Response(status=204)

    def delete(self, request: Request, id: str, suggestion_id: str) -> Response:
        _require_write(request, id)
        athlete = get_object_or_404(User, pk=id)
        field, kind, key = _parse_dismiss_suggestion_id(suggestion_id)
        threshold_suggestions.undo_dismiss(athlete, field, kind, key)
        return Response(status=204)


def _parse_suggestion_id(suggestion_id: str, expected_kind: str) -> tuple[str, str]:
    """field:kind:key -> (field, key), raising 404 (not 400 - this is a resource lookup, not a
    body-validation failure) for a malformed id or the wrong kind for this action."""
    parts = suggestion_id.split(":", 2)
    if len(parts) != 3 or parts[0] not in _THRESHOLD_FIELDS or parts[1] != expected_kind:
        raise NotFound("No such suggestion.")
    return parts[0], parts[2]


def _parse_dismiss_suggestion_id(suggestion_id: str) -> tuple[str, str, str]:
    parts = suggestion_id.split(":", 2)
    if len(parts) != 3 or parts[0] not in _THRESHOLD_FIELDS or parts[1] not in ("rejected", "upcoming_drop"):
        raise NotFound("No such suggestion.")
    return parts[0], parts[1], parts[2]
