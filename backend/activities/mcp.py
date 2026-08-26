"""MCP tools for activity data - trimmed summaries/detail mirroring the Java backend's
mcp/tools/activities/*.java (ActivityReadTools, ActivityStreamTools, ActivityCurveTools). Reuses
this app's existing REST view logic directly (activities/views.py) - Django has no separate
service layer for a service-agnostic caller to go through instead.

Auto-discovered by django-mcp-server at startup (INSTALLED_APPS's autodiscover_modules('mcp'));
every non-underscore-prefixed method on ActivityMCPTools becomes one MCP tool, named after the
method, described by its docstring - see authn/mcp_toolset.py's module docstring for why the
inherited scope/ownership helpers are underscore-prefixed instead.
"""

import base64
from typing import Any

from django.db.models import Exists, OuterRef, Q
from django.shortcuts import get_object_or_404
from rest_framework.exceptions import ValidationError

from authn.mcp_scopes import ACTIVITIES_READ, ACTIVITIES_WRITE
from authn.mcp_toolset import ScopedMCPToolset
from core.auth_context import get_effective_athlete_id
from core.cql.compiler import compile_ast_to_q
from core.cql.parser import parse

from .models import Activity, ActivityComment, ActivityTag, DurationCurve
from .serializers import ActivityCommentSerializer

ACTIVITY_FIELD_MAP = {
    "date": "start_date",
    "hr": "avg_hr",
    "maxhr": "max_hr",
    "tss": "tss",
    "distance": "distance_km",
    "duration": "moving_time",
    "power": "avg_power",
    "temperature": "avg_air_temp",
    "humidity": "avg_humidity",
    "sport": "sport",
    "environment": "environment",
    "name": "name",
}

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
DEFAULT_STREAM_FIELDS = ["power", "heartrate", "cadence", "altitude"]
STREAM_RESOLUTION_STEP = 15  # "low" resolution only - matches the Java tool, no resolution param exposed.

# Duration-in-seconds keys DurationCurve.points is stored under, per activities/models.py.
_CURVE_KEYS = {"sec5": "5", "min1": "60", "min5": "300", "min20": "1200", "min60": "3600"}


def _tag_filter(value: str) -> Q:
    return Q(Exists(ActivityTag.objects.filter(activity=OuterRef("pk"), tag__name__iexact=value)))


def _activity_summary(activity: Activity) -> dict[str, Any]:
    return {
        "id": activity.id,
        "name": activity.name,
        "sport": activity.sport,
        "start_date": activity.start_date.isoformat(),
        "moving_time": activity.moving_time,
        "distance_km": activity.distance_km,
        "avg_power": activity.avg_power,
        "avg_hr": activity.avg_hr,
        "tss": activity.tss,
        "intensity": activity.intensity,
    }


def _encode_cursor(activity: Activity) -> str:
    raw = f"{activity.start_date.isoformat()}|{activity.id}"
    return base64.urlsafe_b64encode(raw.encode()).decode()


def _decode_cursor(cursor: str) -> tuple[str, str]:
    raw = base64.urlsafe_b64decode(cursor.encode()).decode()
    start_date, activity_id = raw.split("|", 1)
    return start_date, activity_id


class ActivityMCPTools(ScopedMCPToolset):
    def list_activities(
        self,
        query: str | None = None,
        sport: str | None = None,
        after: str | None = None,
        before: str | None = None,
        limit: int = 20,
        cursor: str | None = None,
    ) -> dict[str, Any]:
        """List the authenticated athlete's activities, most recent first, optionally filtered
        by sport and/or date range. Returns compact summaries (name, sport, date, duration,
        distance, avg power/HR, TSS) - use get_activity for the full detail on one activity.
        Paginated via next_cursor."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        qs = Activity.objects.filter(athlete_id=athlete_id, parent_activity__isnull=True, primary_activity__isnull=True)

        if query:
            result = parse(query)
            if not result.empty and result.ast:
                qs = qs.filter(compile_ast_to_q(result.ast, ACTIVITY_FIELD_MAP, tag_filter=_tag_filter))

        if sport:
            if sport not in dict(Activity.SPORT_CHOICES):
                raise ValidationError({"sport": f"Unknown sport '{sport}'."})
            qs = qs.filter(sport=sport)

        if after:
            try:
                qs = qs.filter(start_date__date__gte=after)
            except (ValueError, ValidationError) as exc:
                raise ValidationError({"after": "Expected ISO date (YYYY-MM-DD)."}) from exc
        if before:
            try:
                qs = qs.filter(start_date__date__lte=before)
            except (ValueError, ValidationError) as exc:
                raise ValidationError({"before": "Expected ISO date (YYYY-MM-DD)."}) from exc

        if cursor:
            cursor_date, cursor_id = _decode_cursor(cursor)
            qs = qs.filter(Q(start_date__lt=cursor_date) | (Q(start_date=cursor_date) & Q(id__lt=cursor_id)))

        capped_limit = max(1, min(limit or 20, 100))
        qs = qs.order_by("-start_date", "-id")
        rows = list(qs[: capped_limit + 1])
        has_more = len(rows) > capped_limit
        rows = rows[:capped_limit]

        return {
            "has_more": has_more,
            "next_cursor": _encode_cursor(rows[-1]) if has_more and rows else None,
            "data": [_activity_summary(a) for a in rows],
        }

    def get_activity(self, activity_id: str) -> dict[str, Any]:
        """Get full detail on a single activity by id (from list_activities' results) - name,
        sport, duration, distance, power/HR/TSS, elevation, calories, training effect, tags, and
        linked workout/gear ids."""
        self._require_scope(ACTIVITIES_READ)
        activity = get_object_or_404(Activity, pk=activity_id)
        self._require_read(activity.athlete_id)

        return {
            "id": activity.id,
            "name": activity.name,
            "sport": activity.sport,
            "start_date": activity.start_date.isoformat(),
            "moving_time": activity.moving_time,
            "distance_km": activity.distance_km,
            "avg_power": activity.avg_power,
            "avg_hr": activity.avg_hr,
            "tss": activity.tss,
            "intensity": activity.intensity,
            "ascent": activity.ascent,
            "calories": activity.calories,
            "training_effect_label": activity.training_effect_label,
            "tags": list(activity.tags.order_by("name").values_list("name", flat=True)),
            "workout_id": activity.workout_id,
            "bike_id": activity.bike_id,
            "shoe_id": activity.shoe_id,
        }

    def get_activity_laps(self, activity_id: str) -> dict[str, Any]:
        """Get the lap/interval splits recorded during an activity (index, duration, distance,
        avg HR/power per lap) - useful for interval workouts where the athlete lapped each
        rep."""
        self._require_scope(ACTIVITIES_READ)
        activity = get_object_or_404(Activity, pk=activity_id)
        self._require_read(activity.athlete_id)

        # Wrapped in {"data": [...]} rather than returned as a bare list - django-mcp-server
        # serializes a bare top-level list as one content block PER ITEM (confirmed live), so an
        # empty list produces zero content blocks instead of a clean empty result. Matches this
        # backend's own REST convention too (every list endpoint already wraps in {"data": ...}).
        return {
            "data": [
                {
                    "index": lap.index,
                    "duration": lap.duration,
                    "distance_km": lap.distance_km,
                    "avg_hr": lap.avg_hr,
                    "avg_power": lap.avg_power,
                }
                for lap in activity.laps.order_by("index")
            ]
        }

    def get_activity_stream_summary(self, activity_id: str, fields: str | None = None) -> dict[str, Any]:
        """Get a coarse (1 sample per 15s) time-series summary for an activity - min/max/avg per
        requested field plus the downsampled series itself. Useful for 'how variable was my
        power/HR' or 'show me the shape of this effort' - never the full per-second data."""
        self._require_scope(ACTIVITIES_READ)
        activity = get_object_or_404(Activity, pk=activity_id)
        self._require_read(activity.athlete_id)

        if fields:
            channels = [c.strip() for c in fields.split(",") if c.strip()]
            unknown = [c for c in channels if c not in SCALAR_STREAM_FIELDS and c != "latlng"]
            if unknown:
                raise ValidationError({"fields": f"Unknown channel(s): {', '.join(unknown)}."})
        else:
            channels = list(DEFAULT_STREAM_FIELDS)

        records = list(activity.records.order_by("t"))
        records = records[::STREAM_RESOLUTION_STEP]

        series: dict[str, list[Any]] = {}
        stats: dict[str, dict[str, float]] = {}
        for channel in channels:
            if channel == "latlng":
                series["latlng"] = [[r.lat, r.lng] for r in records if r.lat is not None and r.lng is not None]
                continue
            values = [getattr(r, SCALAR_STREAM_FIELDS[channel]) for r in records]
            series[channel] = values
            numeric = [v for v in values if v is not None]
            if numeric:
                stats[channel] = {"min": min(numeric), "max": max(numeric), "avg": sum(numeric) / len(numeric)}

        return {"resolution": "low", "sample_count": len(records), "stats": stats, "series": series}

    def get_activity_power_curve(self, activity_id: str, metric: str = "power") -> dict[str, Any]:
        """Get an activity's best-sustained power or heart rate at fixed durations (5s/1min/
        5min/20min/60min) - the standard 'power curve' shape used to judge sprint/anaerobic/
        threshold/aerobic effort. Null for any duration not tracked for that metric, or if the
        activity has no curve data."""
        self._require_scope(ACTIVITIES_READ)
        activity = get_object_or_404(Activity, pk=activity_id)
        self._require_read(activity.athlete_id)

        if metric not in dict(DurationCurve.METRIC_CHOICES):
            raise ValidationError({"metric": "metric must be one of: power, heartrate."})

        curve = activity.duration_curves.filter(metric=metric).first()
        points = curve.points if curve else {}

        return {"metric": metric, **{key: points.get(sec) for key, sec in _CURVE_KEYS.items()}}

    def post_activity_comment(self, activity_id: str, text: str) -> dict[str, Any]:
        """Post a short comment on an activity (from list_activities/get_activity) - visible to
        the athlete and anyone else with access to it, e.g. coaching feedback on a specific
        session."""
        self._require_scope(ACTIVITIES_WRITE)
        activity = get_object_or_404(Activity, pk=activity_id)
        # Comments are read-gated, not write-gated (see ActivityCommentListView's docstring) -
        # anyone who can see the activity can comment on it, same as the REST endpoint.
        self._require_read(activity.athlete_id)
        sub, _ = get_effective_athlete_id(self.request)

        if not text or not text.strip():
            raise ValidationError({"text": "Comment text cannot be empty."})
        if len(text) > 4000:
            raise ValidationError({"text": "text must be 4000 characters or fewer."})

        comment = ActivityComment.objects.create(activity=activity, author_id=sub, text=text)
        return ActivityCommentSerializer(comment).data

    def list_activity_comments(self, activity_id: str) -> dict[str, Any]:
        """List the comments on an activity (from list_activities/get_activity), oldest first -
        who wrote each one and their role (athlete/coach/viewer). Use this to see whether the
        athlete replied to a comment before posting another."""
        self._require_scope(ACTIVITIES_READ)
        activity = get_object_or_404(Activity, pk=activity_id)
        self._require_read(activity.athlete_id)
        comments = activity.comments.select_related("author", "activity").order_by("created")
        return {"data": ActivityCommentSerializer(comments, many=True).data}
