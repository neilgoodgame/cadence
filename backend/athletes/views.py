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

from .models import ZoneSet
from .serializers import AthleteUpdateSerializer, FitnessPointSerializer, ZoneSetReplaceSerializer, ZoneSetSerializer
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
        return Response({"data": ZoneSetSerializer(zone_sets, many=True).data})


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
