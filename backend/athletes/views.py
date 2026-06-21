from datetime import timedelta

from django.shortcuts import get_object_or_404
from django.utils import timezone
from django.utils.dateparse import parse_date
from rest_framework.exceptions import PermissionDenied, ValidationError
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from accounts.models import User
from accounts.serializers import UserSerializer
from activities.models import BestEffort
from activities.serializers import BestEffortSerializer
from core.auth_context import get_effective_athlete_id
from core.derived import DEFAULT_FITNESS_WINDOW_DAYS, compute_fitness_series
from core.permissions import user_may_read, user_may_write

from .models import ZoneSet
from .serializers import AthleteUpdateSerializer, FitnessPointSerializer, ZoneSetReplaceSerializer, ZoneSetSerializer
from .zones import ZONE_TYPES, get_or_create_zone_set, reference_for, zone_types_affected_by

BEST_EFFORT_PERIOD_DAYS = {"3m": 90, "1y": 365}


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
            raise ValidationError({"kind": "Must be one of cycling_power, running_pace, running_power."})

        period = request.query_params.get("period", "all")
        if period not in ("3m", "1y", "all"):
            raise ValidationError({"period": "Must be one of 3m, 1y, all."})

        qs = BestEffort.objects.filter(athlete_id=id, kind=kind)
        if period in BEST_EFFORT_PERIOD_DAYS:
            cutoff = timezone.now().date() - timedelta(days=BEST_EFFORT_PERIOD_DAYS[period])
            qs = qs.filter(date__gte=cutoff)

        return Response({"kind": kind, "period": period, "data": BestEffortSerializer(qs, many=True).data})


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
