import json
from collections.abc import Iterator
from datetime import timedelta

from django.http import StreamingHttpResponse
from django.shortcuts import get_object_or_404
from django.utils import timezone
from django.utils.dateparse import parse_date
from rest_framework.exceptions import PermissionDenied, ValidationError
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

from .models import ThresholdHistory, ZoneSet
from .serializers import AthleteUpdateSerializer, FitnessPointSerializer, ZoneSetReplaceSerializer, ZoneSetSerializer
from .threshold_history import is_stale, rebuild_history_stream, refresh_field
from .zones import ZONE_TYPES, get_or_create_zone_set, reference_for, zone_types_affected_by

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


def _cap_per_window(efforts: list[BestEffort], lower_is_better: bool, top_n: int) -> list[BestEffort]:
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
        serializer.save()

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
        capped = _cap_per_window(list(qs), kind in LOWER_IS_BETTER_KINDS, athlete.best_effort_top_n)

        return Response({"kind": kind, "period": period, "data": BestEffortSerializer(capped, many=True).data})


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


_THRESHOLD_FIELDS = ("ftp", "critical_run_power", "threshold_pace")


def _validate_threshold_field(field: str | None) -> str:
    if field not in _THRESHOLD_FIELDS:
        raise ValidationError({"field": "Must be one of ftp, critical_run_power, threshold_pace."})
    return field


def _threshold_summary_for_field(athlete: User, field: str) -> dict:
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
        return Response({field: _threshold_summary_for_field(athlete, field) for field in _THRESHOLD_FIELDS})


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
        return Response({f: _threshold_summary_for_field(athlete, f) for f in _THRESHOLD_FIELDS})


def _recompute_threshold_history_stream(athlete: User, field: str) -> Iterator[str]:
    for current, total in rebuild_history_stream(athlete, field):
        yield f"data: {json.dumps({'current': current, 'total': total})}\n\n"
    yield f"event: done\ndata: {json.dumps({'field': field})}\n\n"


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


_SPORT_FOR_KIND = {
    "cycling_hr": "bike",
    "cycling_power": "bike",
    "running_hr": "run",
    "running_pace": "run",
    "running_power": "run",
}


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


def _recompute_stream(athlete: User, kind: str | None) -> Iterator[str]:
    from uploads.processing import compute_kind_best_efforts, update_best_efforts

    qs = BestEffort.objects.filter(athlete=athlete)
    if kind:
        qs = qs.filter(kind=kind)
    qs.delete()

    candidates = Activity.objects.filter(
        athlete=athlete,
        parent_activity__isnull=True,
    ).exclude(sport__in=("multisport", "transition"))
    if kind:
        candidates = candidates.filter(sport=_SPORT_FOR_KIND[kind])

    activities = list(candidates.order_by("start_date"))
    total = len(activities)

    for i, activity in enumerate(activities):
        records = list(activity.records.order_by("t"))
        if records:
            power_series = [r.power for r in records]
            hr_series = [r.heartrate for r in records]
            t_series = [r.t for r in records]
            distance_series = [r.distance_km for r in records]
            if kind:
                compute_kind_best_efforts(activity, athlete, kind, power_series, t_series, distance_series, hr_series)
            else:
                update_best_efforts(activity, athlete, power_series, t_series, distance_series, hr_series)
        yield f"data: {json.dumps({'current': i + 1, 'total': total})}\n\n"

    yield f"event: done\ndata: {json.dumps({'processed': total})}\n\n"


class BestEffortRecomputeView(APIView):
    def post(self, request: Request, id: str) -> StreamingHttpResponse:
        _require_write(request, id)
        athlete = get_object_or_404(User, pk=id)
        kind = request.query_params.get("kind") or None
        if kind and kind not in dict(BestEffort.KIND_CHOICES):
            raise ValidationError(
                {"kind": "Must be one of cycling_hr, cycling_power, running_hr, running_pace, running_power."}
            )
        response = StreamingHttpResponse(
            _recompute_stream(athlete, kind),
            content_type="text/event-stream",
        )
        response["Cache-Control"] = "no-cache"
        response["X-Accel-Buffering"] = "no"
        return response


class BestEffortTrimView(APIView):
    def post(self, request: Request, id: str) -> Response:
        _require_write(request, id)
        athlete = get_object_or_404(User, pk=id)
        from uploads.processing import trim_best_efforts

        trim_best_efforts(athlete)
        return Response(status=204)
