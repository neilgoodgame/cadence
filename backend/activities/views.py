from typing import Any

from django.db.models import Count, Exists, OuterRef, Q, Value
from django.db.models.functions import Coalesce
from django.shortcuts import get_object_or_404
from django.utils import timezone
from rest_framework.exceptions import PermissionDenied, ValidationError
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from core.auth_context import get_effective_athlete_id
from core.cql import compile_ast_to_q, parse, resolve_order_by
from core.exceptions import ConflictError
from core.pagination import CadenceCursorPagination
from core.permissions import user_may_read, user_may_write
from uploads.processing import (
    backfill_extended_stats,
    compute_normalized_power,
    compute_tss,
    detect_threshold_increase,
)
from uploads.serializers import UploadSerializer
from uploads.services import create_activity_upload
from workouts.inference import infer_workout
from workouts.models import Workout

from .models import Activity, ActivityComment, ActivityTag, DurationCurve, Record, Tag
from .serializers import (
    ActivityCommentCreateSerializer,
    ActivityCommentSerializer,
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
    "date": "start_date",
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

# avg_hr/max_hr/avg_power are nullable (not every activity has HR or power data).
# Postgres's default NULLS ordering (NULLS FIRST for DESC, NULLS LAST for ASC) would
# otherwise put activities with no data at the very top of a descending sort - not what
# "sort by heart rate" means to a user. The value is a sentinel safely outside any real
# reading for these fields, used to push nulls to the end regardless of direction.
NULLABLE_SORT_SENTINEL = 100_000
NULLABLE_SORT_FIELDS = {"avg_hr", "max_hr", "avg_power"}


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


def _is_multisport_linked(activity: Activity) -> bool:
    return activity.sport in ("multisport", "transition") or activity.parent_activity_id is not None


def _resolve_primary_activity(activity: Activity, primary_id: str) -> Activity:
    """Validates marking `activity` as a duplicate of `primary_id`: same athlete, no
    self-links, no chains (neither side may already be a duplicate or have duplicates
    pointing the other way), and multisport parents/legs stay out entirely - their
    training load is already handled by the parent/child relationship."""
    if primary_id == activity.pk:
        raise ValidationError({"primary_activity_id": "An activity cannot be a duplicate of itself."})
    primary = get_object_or_404(Activity, pk=primary_id, athlete_id=activity.athlete_id)
    if primary.primary_activity_id is not None:
        raise ValidationError({"primary_activity_id": "The chosen primary is itself a duplicate of another activity."})
    if activity.duplicate_activities.exists():
        raise ValidationError({"primary_activity_id": "This activity has duplicates of its own; re-link those first."})
    if _is_multisport_linked(activity) or _is_multisport_linked(primary):
        raise ValidationError(
            {"primary_activity_id": "Multisport sessions and their legs cannot be linked as duplicates."}
        )
    return primary


class ActivityCursorPagination(CadenceCursorPagination):
    ordering = ("-start_date", "-id")


class ActivityListView(APIView):
    def get(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_read(request, athlete_id)

        # Multisport children are reachable via their parent's child_activity_ids, not the
        # list - showing legs alongside the parent would present the same session twice.
        # Duplicate recordings are likewise reachable only via their primary's
        # duplicate_activity_ids.
        qs = Activity.objects.filter(athlete_id=athlete_id, parent_activity__isnull=True, primary_activity__isnull=True)

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

        # Separate from `q`: the CQL `tag <name>` clause only ever captures a single
        # token as the value (see core/cql/parser.py), so a multi-word tag name like
        # "Heat Training" silently loses everything after the first word and matches
        # nothing. The tag filter chips drive this param directly instead of smuggling
        # the tag name through CQL text.
        tag = request.query_params.get("tag")
        if tag:
            qs = qs.filter(_tag_filter(tag))

        after = request.query_params.get("after")
        if after:
            try:
                qs = qs.filter(start_date__date__gte=after)
            except (ValueError, ValidationError) as exc:
                raise ValidationError({"after": "Expected ISO date (YYYY-MM-DD)."}) from exc

        before = request.query_params.get("before")
        if before:
            try:
                qs = qs.filter(start_date__date__lte=before)
            except (ValueError, ValidationError) as exc:
                raise ValidationError({"before": "Expected ISO date (YYYY-MM-DD)."}) from exc

        if order_field is None:
            sort_param = request.query_params.get("sort")
            if sort_param:
                raw_field = sort_param.lstrip("-")
                mapped = ACTIVITY_FIELD_MAP.get(raw_field)
                if mapped is None:
                    raise ValidationError({"sort": f"Unknown sort field '{raw_field}'."})
                order_field = f"-{mapped}" if sort_param.startswith("-") else mapped

        # Coalescing to a sentinel (rather than a raw nulls_last order_by) because DRF's
        # CursorPagination only accepts plain field-name strings for `ordering`, not F()
        # expressions - see NULLABLE_SORT_FIELDS above for why this is needed at all.
        if order_field:
            raw_order_field = order_field.lstrip("-")
            if raw_order_field in NULLABLE_SORT_FIELDS:
                descending = order_field.startswith("-")
                annotation = f"{raw_order_field}_sort"
                sentinel = -1 if descending else NULLABLE_SORT_SENTINEL
                qs = qs.annotate(**{annotation: Coalesce(raw_order_field, Value(sentinel))})
                order_field = f"-{annotation}" if descending else annotation

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

    def delete(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)
        # record is a TimescaleDB hypertable chunked by time, so per-activity cascade
        # deletes each visit every chunk; clear the records in one set-based statement
        # first, then let the cascade handle the small per-activity tables. Uploads keep
        # their rows (activity FK is SET_NULL), so the same files can be re-imported
        # afterwards without tripping duplicate detection.
        Record.objects.filter(activity__athlete_id=athlete_id).delete()
        Activity.objects.filter(athlete_id=athlete_id).delete()
        return Response(status=204)


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
        if "primary_activity_id" in data:
            primary_id = data["primary_activity_id"]
            activity.primary_activity = None if primary_id is None else _resolve_primary_activity(activity, primary_id)
            update_fields.append("primary_activity")
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


class RecomputeActivityTssView(APIView):
    def post(self, request: Request, id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, activity.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        athlete = activity.athlete
        power_series = list(activity.records.order_by("t").values_list("power", flat=True))
        hr_series = list(activity.records.order_by("t").values_list("heartrate", flat=True))
        norm_power = compute_normalized_power(power_series) if any(p is not None for p in power_series) else None
        activity.tss = compute_tss(activity, athlete, norm_power, hr_series)
        activity.save(update_fields=["tss"])
        return Response(ActivitySerializer(activity).data)


class ActivityThresholdSuggestionView(APIView):
    """POST /v1/activities/<id>/threshold-suggestion {"field": ..., "accept": bool,
    "update_profile": bool} - accept or dismiss a threshold increase detected from this
    activity's own best effort (see uploads/processing.py::detect_threshold_increase).

    Accepting always updates this activity's own snapshot (the effort that revealed the new
    threshold is itself re-rated against it) and recomputes its TSS/intensity - skipped for
    threshold_pace, which neither TSS nor intensity are derived from anywhere in this codebase.
    It ALSO updates the athlete's profile, but only when update_profile is true, and only for an
    activity within MAX_PROFILE_UPDATE_AGE_DAYS - a suggestion from years-old activity shouldn't
    silently redefine the athlete's *current* profile, even though the activity's own numbers are
    still worth correcting. Requesting update_profile=True for an activity past the cutoff is
    rejected with 400 rather than silently downgraded, so the caller's request always means what
    it says.

    Dismissing just clears the suggestion. Either way the suggested_* field is consumed - it
    never re-appears for the same activity/value; a later, larger effort can still set it again.
    """

    # field -> (suggested column, snapshot column, athlete profile column)
    FIELD_MAP = {
        "ftp": ("suggested_ftp", "ftp_snapshot", "ftp"),
        "critical_run_power": ("suggested_critical_run_power", "critical_run_power_snapshot", "critical_run_power"),
        "threshold_pace": ("suggested_threshold_pace", "threshold_pace_snapshot", "threshold_pace"),
    }

    MAX_PROFILE_UPDATE_AGE_DAYS = 365

    def post(self, request: Request, id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, activity.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")

        field = request.data.get("field")
        accept = request.data.get("accept")
        update_profile = request.data.get("update_profile")
        if field not in self.FIELD_MAP:
            raise ValidationError({"field": "Must be one of ftp, critical_run_power, threshold_pace."})
        if not isinstance(accept, bool):
            raise ValidationError({"accept": "Must be a boolean."})
        if accept and not isinstance(update_profile, bool):
            raise ValidationError({"update_profile": "Must be a boolean."})

        suggested_field, snapshot_field, athlete_field = self.FIELD_MAP[field]
        suggested_value = getattr(activity, suggested_field)
        if not suggested_value:
            raise ConflictError("No pending suggestion for that field.")

        update_fields = [suggested_field]
        if accept:
            athlete = activity.athlete
            if update_profile:
                age_days = (timezone.now() - activity.start_date).days
                if age_days > self.MAX_PROFILE_UPDATE_AGE_DAYS:
                    raise ValidationError({"update_profile": "This activity is too old to update your profile from."})
                setattr(athlete, athlete_field, suggested_value)
                athlete.save(update_fields=[athlete_field])
            setattr(activity, snapshot_field, suggested_value)
            update_fields.append(snapshot_field)

            if field in ("ftp", "critical_run_power"):
                power_series = list(activity.records.order_by("t").values_list("power", flat=True))
                hr_series = list(activity.records.order_by("t").values_list("heartrate", flat=True))
                norm_power = (
                    compute_normalized_power(power_series) if any(p is not None for p in power_series) else None
                )
                threshold = getattr(activity, snapshot_field)
                if norm_power and threshold:
                    activity.intensity = round(norm_power / threshold, 3)
                activity.tss = compute_tss(activity, athlete, norm_power, hr_series)
                update_fields.extend(["intensity", "tss"])

        # Clear the suggestion either way - accepted (consumed) or dismissed (rejected).
        setattr(activity, suggested_field, "" if isinstance(suggested_value, str) else None)
        activity.save(update_fields=update_fields)
        return Response(ActivitySerializer(activity).data)


class RecomputeActivityThresholdsView(APIView):
    """POST /v1/activities/<id>/recompute-thresholds - re-runs detect_threshold_increase against
    this activity's own stored Records and marks it threshold_checked. Detection normally only
    runs once, at ingest - this lets an activity that predates the feature (or was imported,
    which also never runs detection) get evaluated retroactively, so the banner's "this
    activity's zones may be based on outdated values" flag can be resolved."""

    def post(self, request: Request, id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, activity.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")

        records = activity.records.order_by("t").values_list("t", "power", "distance_km")
        t_series = [r[0] for r in records]
        power_series = [r[1] for r in records]
        distance_km_series = [r[2] for r in records]

        detect_threshold_increase(activity, power_series, t_series, distance_km_series)
        activity.threshold_checked = True
        update_fields = ["threshold_checked"] + [
            f
            for f in ("suggested_ftp", "suggested_critical_run_power", "suggested_threshold_pace")
            if getattr(activity, f)
        ]
        activity.save(update_fields=update_fields)
        return Response(ActivitySerializer(activity).data)


class RecomputeActivityStatsView(APIView):
    """Single-activity wrapper around uploads.processing.backfill_extended_stats - see
    RecomputeAthleteStatsView (athletes/views.py) for the per-athlete bulk equivalent.
    """

    def post(self, request: Request, id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, activity.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        update_fields = backfill_extended_stats(activity, activity.athlete)
        if update_fields:
            activity.save(update_fields=update_fields)
        return Response(ActivitySerializer(activity).data)


class LapListView(APIView):
    def get(self, request: Request, id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, activity.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")
        return Response({"data": LapSerializer(activity.laps.all(), many=True).data})


class InferWorkoutView(APIView):
    def get(self, request: Request, id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, activity.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")
        if activity.sport not in dict(Workout.SPORT_CHOICES):
            raise ValidationError({"sport": "Only bike and run activities can be inferred into a workout."})
        if not activity.laps.exists():
            raise ValidationError({"laps": "This activity has no laps to infer a workout from."})

        auto_detect_repeats = request.query_params.get("auto_detect_repeats", "true").lower() == "true"
        return Response(infer_workout(activity, auto_detect_repeats=auto_detect_repeats))


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
        tags = (
            Tag.objects.filter(athlete_id=athlete_id).annotate(count=Count("activity_tags")).order_by("-count", "name")
        )
        return Response({"data": TagSerializer(tags, many=True).data})


class TagDetailView(APIView):
    def delete(self, request: Request, id: str) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)
        tag = get_object_or_404(Tag, pk=id, athlete_id=athlete_id)
        if ActivityTag.objects.filter(tag=tag).exists():
            raise ConflictError("This tag is still linked to activities.")
        tag.delete()
        return Response(status=204)


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


class ActivityCommentListView(APIView):
    """Comments are a lightweight social feature gated by read access, not write access -
    anyone who can see an athlete's activity (the athlete, a coach, or a viewer share) can
    comment on it, same as the model docstring documents.
    """

    def get(self, request: Request, id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, activity.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")
        comments = activity.comments.select_related("author", "activity").order_by("created")
        return Response({"data": ActivityCommentSerializer(comments, many=True).data})

    def post(self, request: Request, id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, activity.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")

        serializer = ActivityCommentCreateSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)

        comment = ActivityComment.objects.create(
            activity=activity, author_id=sub, text=serializer.validated_data["text"]
        )
        return Response(ActivityCommentSerializer(comment).data, status=201)


class ActivityCommentDetailView(APIView):
    def delete(self, request: Request, id: str, comment_id: str) -> Response:
        activity = get_object_or_404(Activity, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, activity.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")

        comment = get_object_or_404(ActivityComment, pk=comment_id, activity=activity)
        if comment.author_id != sub:
            raise PermissionDenied("You can only delete your own comments.")
        comment.delete()
        return Response(status=204)
