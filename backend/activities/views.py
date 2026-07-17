from typing import Any

from django.db.models import Exists, OuterRef, Q
from django.shortcuts import get_object_or_404
from rest_framework.exceptions import PermissionDenied, ValidationError
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from core.auth_context import get_effective_athlete_id
from core.cql import compile_ast_to_q, parse, resolve_order_by
from core.pagination import CadenceCursorPagination
from core.permissions import user_may_read, user_may_write
from uploads.serializers import UploadSerializer
from uploads.services import create_activity_upload
from workouts.models import Workout

from .models import Activity, ActivityTag, DurationCurve, Tag
from .serializers import (
    ActivitySerializer,
    ActivityUpdateSerializer,
    DurationCurveSerializer,
    LapSerializer,
    TagAttachSerializer,
    TagSerializer,
)

SCALAR_STREAM_FIELDS = {
    "time": "t",
    "power": "power",
    "heartrate": "heartrate",
    "cadence": "cadence",
    "altitude": "altitude",
    "distance": "distance_km",
    "speed": "speed",
    "air_temp": "air_temp",
    "humidity": "humidity",
    "core_temp": "core_temp",
    "skin_temp": "skin_temp",
    "heat_strain": "heat_strain",
}
STREAM_CHANNELS = set(SCALAR_STREAM_FIELDS) | {"latlng"}
STREAM_RESOLUTION_STEP = {"high": 1, "medium": 5, "low": 15}

ACTIVITY_FIELD_MAP = {
    "hr": "avg_hr",
    "maxhr": "max_hr",
    "tss": "tss",
    "distance": "distance_km",
    "duration": "moving_time",
    "power": "avg_power",
    "sport": "sport",
    "environment": "environment",
    "name": "name",
}


def _tag_filter(value: str) -> Q:
    return Q(Exists(ActivityTag.objects.filter(activity=OuterRef("pk"), tag__name__iexact=value)))


def _require_read(request: Request, athlete_id: str) -> None:
    sub, _ = get_effective_athlete_id(request)
    if not user_may_read(sub, athlete_id):
        raise PermissionDenied("You do not have access to that athlete's data.")


def _require_write(request: Request, athlete_id: str) -> None:
    sub, _ = get_effective_athlete_id(request)
    if not user_may_write(sub, athlete_id):
        raise PermissionDenied("You do not have write access to that athlete's data.")


class ActivityCursorPagination(CadenceCursorPagination):
    ordering = ("-start_date", "-id")


class ActivityListView(APIView):
    def get(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_read(request, athlete_id)

        # Multisport children are reachable via their parent's child_activity_ids, not the
        # list - showing legs alongside the parent would present the same session twice.
        qs = Activity.objects.filter(athlete_id=athlete_id, parent_activity__isnull=True)

        order_field = None
        q = request.query_params.get("q")
        if q:
            result = parse(q)
            if not result.empty:
                if result.ast:
                    qs = qs.filter(compile_ast_to_q(result.ast, ACTIVITY_FIELD_MAP, tag_filter=_tag_filter))
                if result.order:
                    order_field = resolve_order_by(result.order, ACTIVITY_FIELD_MAP)

        sport = request.query_params.get("sport")
        if sport:
            if sport not in dict(Activity.SPORT_CHOICES):
                raise ValidationError({"sport": f"Unknown sport '{sport}'."})
            qs = qs.filter(sport=sport)

        environment = request.query_params.get("environment")
        if environment:
            if environment not in dict(Activity.ENVIRONMENT_CHOICES):
                raise ValidationError({"environment": f"Unknown environment '{environment}'."})
            qs = qs.filter(environment=environment)

        if order_field is None:
            sort_param = request.query_params.get("sort")
            if sort_param:
                raw_field = sort_param.lstrip("-")
                mapped = ACTIVITY_FIELD_MAP.get(raw_field)
                if mapped is None:
                    raise ValidationError({"sort": f"Unknown sort field '{raw_field}'."})
                order_field = f"-{mapped}" if sort_param.startswith("-") else mapped

        paginator = ActivityCursorPagination()
        if order_field:
            paginator.ordering = (order_field, "-id")
        page = paginator.paginate_queryset(qs, request, view=self)
        return paginator.get_paginated_response(ActivitySerializer(page, many=True).data)

    def post(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)

        upload, status_code = create_activity_upload(request, athlete_id)
        response = Response(UploadSerializer(upload).data, status=status_code)
        if status_code == 202:
            response["Location"] = f"/v1/uploads/{upload.id}"
            response["Retry-After"] = "2"
        return response


class ActivityDetailView(APIView):
    def get(self, request: Request, id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, activity.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")
        return Response(ActivitySerializer(activity).data)

    def patch(self, request: Request, id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, activity.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")

        serializer = ActivityUpdateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        update_fields = []
        if "name" in data:
            activity.name = data["name"]
            update_fields.append("name")
        if "sport" in data:
            activity.sport = data["sport"]
            update_fields.append("sport")
        if "workout_id" in data:
            workout_id = data["workout_id"]
            if workout_id is None:
                activity.workout = None
            else:
                activity.workout = get_object_or_404(Workout, pk=workout_id, created_by_id=activity.athlete_id)
            update_fields.append("workout")
        if "start_weight_kg" in data:
            activity.start_weight_kg = data["start_weight_kg"]
            update_fields.append("start_weight_kg")
        if "end_weight_kg" in data:
            activity.end_weight_kg = data["end_weight_kg"]
            update_fields.append("end_weight_kg")
        if "fluids_ml" in data:
            activity.fluids_ml = data["fluids_ml"]
            update_fields.append("fluids_ml")
        if "avg_air_temp" in data or "avg_humidity" in data:
            stryd_computed = activity.sport == "run" and activity.records.filter(air_temp__isnull=False).exists()
            if not stryd_computed:
                if "avg_air_temp" in data:
                    activity.avg_air_temp = data["avg_air_temp"]
                    update_fields.append("avg_air_temp")
                if "avg_humidity" in data:
                    activity.avg_humidity = data["avg_humidity"]
                    update_fields.append("avg_humidity")

        if update_fields:
            activity.save(update_fields=update_fields)
        return Response(ActivitySerializer(activity).data)

    def delete(self, request: Request, id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, activity.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        activity.delete()
        return Response(status=204)


class LapListView(APIView):
    def get(self, request: Request, id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, activity.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")
        return Response({"data": LapSerializer(activity.laps.all(), many=True).data})


class StreamsView(APIView):
    def get(self, request: Request, id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, activity.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")

        resolution = request.query_params.get("resolution", "high")
        if resolution not in STREAM_RESOLUTION_STEP:
            raise ValidationError({"resolution": f"Unknown resolution '{resolution}'."})

        fields_param = request.query_params.get("fields")
        if fields_param:
            channels = [c for c in fields_param.split(",") if c]
            unknown = [c for c in channels if c not in STREAM_CHANNELS]
            if unknown:
                raise ValidationError({"fields": f"Unknown channel(s): {', '.join(unknown)}."})
        else:
            channels = list(SCALAR_STREAM_FIELDS) + (["latlng"] if activity.has_gps else [])

        records = list(activity.records.order_by("t"))
        step = STREAM_RESOLUTION_STEP[resolution]
        if step > 1:
            records = records[::step]

        fields_payload: dict[str, list[Any]] = {}
        for channel in channels:
            if channel == "latlng":
                fields_payload["latlng"] = [[r.lat, r.lng] for r in records if r.lat is not None and r.lng is not None]
            else:
                fields_payload[channel] = [getattr(r, SCALAR_STREAM_FIELDS[channel]) for r in records]

        return Response({"object": "streams", "resolution": resolution, "fields": fields_payload})


class CurvesView(APIView):
    def get(self, request: Request, id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, activity.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")

        metric = request.query_params.get("metric", "power")
        if metric not in dict(DurationCurve.METRIC_CHOICES):
            raise ValidationError({"metric": f"Unknown metric '{metric}'."})

        curve = activity.duration_curves.filter(metric=metric).first()
        if curve is None:
            return Response({"metric": metric, "extends_to": 0, "points": {}})
        return Response(DurationCurveSerializer(curve).data)


class TagListView(APIView):
    def get(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_read(request, athlete_id)
        tags = Tag.objects.filter(athlete_id=athlete_id).order_by("name")
        return Response({"data": TagSerializer(tags, many=True).data})


class ActivityTagView(APIView):
    def post(self, request: Request, id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, activity.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")

        serializer = TagAttachSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        if data.get("tag_id"):
            tag = get_object_or_404(Tag, pk=data["tag_id"], athlete_id=activity.athlete_id)
        else:
            tag, _created = Tag.objects.get_or_create(
                athlete_id=activity.athlete_id, name=data["name"], defaults={"origin": "manual"}
            )

        ActivityTag.objects.get_or_create(activity=activity, tag=tag)
        return Response({"activity_id": activity.id, "tag": TagSerializer(tag).data}, status=201)


class ActivityUntagView(APIView):
    def delete(self, request: Request, id: str, tag_id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, activity.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")

        link = get_object_or_404(ActivityTag, activity=activity, tag_id=tag_id)
        if link.tag.origin == "auto":
            raise PermissionDenied("Auto-applied tags cannot be removed.")
        link.delete()
        return Response(status=204)
