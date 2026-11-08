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
from datetime import date
from typing import Any

from django.db.models import Exists, F, Min, OuterRef, Q
from django.db.models.functions import Mod
from django.http import Http404
from django.shortcuts import get_object_or_404
from rest_framework.exceptions import ValidationError

from accounts.models import User
from authn.mcp_scopes import ACTIVITIES_READ, ACTIVITIES_WRITE
from authn.mcp_toolset import ScopedMCPToolset
from core.auth_context import get_effective_athlete_id
from core.cql.compiler import compile_ast_to_q
from core.cql.parser import parse
from core.units import parse_distance_meters

from . import activity_source_service, merge_rule_service
from .comments import resolve_parent_comment
from .duplicate_batches import resolve_duplicates_batch as resolve_duplicates_batch_action
from .duplicate_batches import undo_batch as undo_batch_action
from .merge_apply import CopyMode, NullPolicy
from .merge_service import MergeResult, run_merge
from .models import Activity, ActivityComment, ActivityTag, DurationCurve, Merge, Tag
from .overlaps import find_overlaps
from .serializers import ActivityCommentSerializer
from .splits import get_custom_activity_splits
from .tag_management import rename_tag as tag_rename
from .unmerge_service import undo_merge
from .views import _combined_tag_count
from .views import mark_duplicate as mark_activity_duplicate
from .views import set_primary as set_activity_primary
from .views import unmark_duplicate as unmark_activity_duplicate

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
    "avg_heat_strain": "avg_heat_strain",
    "max_heat_strain": "max_heat_strain",
    "avg_core_temp": "avg_core_temp",
    "max_core_temp": "max_core_temp",
    "avg_skin_temp": "avg_skin_temp",
    "max_skin_temp": "max_skin_temp",
    "start_weight_kg": "start_weight_kg",
    "end_weight_kg": "end_weight_kg",
    "fluids_ml": "fluids_ml",
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
        "avg_heat_strain": activity.avg_heat_strain,
        "max_heat_strain": activity.max_heat_strain,
        "avg_core_temp": activity.avg_core_temp,
        "max_core_temp": activity.max_core_temp,
        "avg_skin_temp": activity.avg_skin_temp,
        "max_skin_temp": activity.max_skin_temp,
        "start_weight_kg": activity.start_weight_kg,
        "end_weight_kg": activity.end_weight_kg,
        "fluids_ml": activity.fluids_ml,
    }


def _duplicate_link_summary(activity: Activity) -> dict[str, Any]:
    """mark_duplicate/unmark_duplicate/set_primary's return shape - unlike get_activity's
    trimmed dict (which deliberately drops duplicate linkage - rarely relevant to a
    conversational assistant), these tools exist specifically to show it. Mirrors
    ActivitySerializer.get_duplicate_activity_ids exactly: a duplicate can never itself be a
    primary (chains are rejected on update), so skip the lookup for anything already linked to a
    primary."""
    duplicate_ids = (
        []
        if activity.primary_activity_id
        else list(activity.duplicate_activities.order_by("start_date").values_list("id", flat=True))
    )
    return {
        "id": activity.id,
        "name": activity.name,
        "primary_activity_id": activity.primary_activity_id,
        "duplicate_activity_ids": duplicate_ids,
    }


def _parse_overlap_date(value: str | None, param: str) -> date | None:
    """Shared by list_overlapping_activities and resolve_duplicates_batch - both take the same
    from_date/to_date filter on the overlap query."""
    if value is None:
        return None
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise ValidationError({param: "Expected ISO date (YYYY-MM-DD)."}) from exc


def _parse_null_policy(value: str | None) -> NullPolicy:
    """merge_activities' `nulls` param - defaults to keep_original, matching the feature's own
    documented default, rather than requiring every call to spell it out."""
    if value is None:
        return "keep_original"
    if value not in ("overwrite", "keep_original"):
        raise ValidationError({"nulls": "nulls must be 'keep_original' or 'overwrite'."})
    return value


def _parse_copy_mode(value: str | None) -> CopyMode:
    """merge_activities' `copy_mode` param (issue #503 phase 1) - defaults to overwrite, today's
    only prior behavior, so every existing caller is unaffected."""
    if value is None:
        return "overwrite"
    if value not in ("overwrite", "attach"):
        raise ValidationError({"copy_mode": "copy_mode must be 'overwrite' or 'attach'."})
    return value


def _merge_record_summary(merge: Merge) -> dict[str, Any]:
    """get_merge's return shape - the merge record itself, not the before/after summary
    merge_activities/run_merge returns (MergeResult is for an in-progress call's own outcome)."""
    return {
        "merge_id": merge.id,
        "source_id": merge.source_activity_id,
        "source_name": merge.source_activity.name if merge.source_activity_id else None,
        "fields": merge.fields,
        "null_policy": merge.null_policy,
        "offset_seconds": merge.offset_seconds,
        "alignment_score": merge.alignment_score,
        "field_counts": merge.field_counts,
        "source_hidden": merge.source_hidden,
        "created_at": merge.created_at.isoformat(),
    }


def _merge_rule_response(sport: str, rule: Any) -> dict[str, Any]:
    """get_merge_rules/set_merge_rule's shared return shape for one sport's rule - `rule` is
    either a saved MergeRule instance or a merge_rule_service.ResolvedRule namedtuple (the
    built-in default case); both carry the same four attributes."""
    return {
        "sport": sport,
        "target_pattern": rule.target_pattern,
        "source_pattern": rule.source_pattern,
        "fields": rule.fields,
        "null_policy": rule.null_policy,
    }


def _encode_cursor(activity: Activity) -> str:
    raw = f"{activity.start_date.isoformat()}|{activity.id}"
    return base64.urlsafe_b64encode(raw.encode()).decode()


def _decode_cursor(cursor: str) -> tuple[str, str]:
    raw = base64.urlsafe_b64decode(cursor.encode()).decode()
    start_date, activity_id = raw.split("|", 1)
    return start_date, activity_id


def convert_custom_steps_input(steps: Any) -> list[dict[str, Any]]:
    """Converts the MCP tool's own step shape (`duration_s` seconds; `distance` a unit string
    like "1mi"/"2km"/"400m") into get_custom_activity_splits's (`duration_s`; `distance_km` a
    plain float) - mirrors workouts/mcp.py's _convert_step_distances, but splits.py already
    speaks km everywhere else, so this converts meters back down to km rather than leaving it in
    meters like a WorkoutStep."""
    if not isinstance(steps, list):
        raise ValidationError({"steps": "steps must be a list."})
    converted = []
    for i, step in enumerate(steps):
        if not isinstance(step, dict):
            raise ValidationError({"steps": f"steps[{i}] must be an object."})
        out = {"end_type": step.get("end_type"), "label": step.get("label")}
        if "duration_s" in step:
            out["duration_s"] = step["duration_s"]
        if "distance" in step:
            meters = parse_distance_meters(step["distance"], f"steps[{i}].distance")
            out["distance_km"] = meters / 1000 if meters is not None else None
        converted.append(out)
    return converted


class ActivityMCPTools(ScopedMCPToolset):
    def list_activities(
        self,
        query: str | None = None,
        sport: str | None = None,
        after: str | None = None,
        before: str | None = None,
        limit: int = 20,
        cursor: str | None = None,
        include_duplicates: bool = False,
    ) -> dict[str, Any]:
        """List the authenticated athlete's activities, most recent first, optionally filtered
        by sport and/or date range. Returns compact summaries (name, sport, date, duration,
        distance, avg power/HR, TSS, - when a CORE body-temperature sensor was worn - avg/max
        heat strain/core temp/skin temp, and - when weigh-ins were recorded - start/end weight
        and fluids consumed) - use get_activity for the full detail on one activity. `query` also
        supports filtering on these fields, e.g. "avg_heat_strain > 5 and date > 2026-06-01" to
        find high-heat-strain sessions in a date range. Paginated via next_cursor.
        `include_duplicates` (default false) also lists same-ride-twice duplicate copies, which
        are otherwise hidden entirely (issue #498) - useful for finding a specific duplicate to
        resolve with mark_duplicate/unmark_duplicate/set_primary, not for everyday browsing."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        qs = Activity.objects.filter(athlete_id=athlete_id, parent_activity__isnull=True)
        if not include_duplicates:
            qs = qs.filter(primary_activity__isnull=True)

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

    def list_overlapping_activities(
        self,
        activity_id: str | None = None,
        sport: str | None = None,
        cross_sport: bool = False,
        device: str | None = None,
        other_device: str | None = None,
        environment: str | None = None,
        from_date: str | None = None,
        to_date: str | None = None,
        min_duration_seconds: int | None = None,
        other_has: list[str] | None = None,
        min_overlap_pct: float | None = None,
        max_start_delta_seconds: int | None = None,
        include_resolved: bool = False,
        cursor: str | None = None,
        limit: int = 50,
    ) -> dict[str, Any]:
        """Issue #498's detection backbone: pairs of the athlete's activities whose recorded
        intervals intersect - the same ride uploaded twice from two devices (e.g. Zwift +
        Garmin), but also any genuinely overlapping pair. `anchor`/`other` are an arbitrary
        pair labelling (see GET /v1/activities/overlaps' own description for exactly how); pass
        `activity_id` to pin a specific activity as anchor and list only its overlaps (the
        "what else overlaps this one" / merge-candidate use case). `other_has` (repeatable:
        power, heartrate, cadence, core_temp) requires the other side to have all of the given
        fields. `status` on each pair is 'unresolved', 'duplicate' (one side's
        primary_activity_id already points at the other), or 'merged' (reserved for #501, not
        yet produced). Paginated via next_cursor."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        return find_overlaps(
            athlete_id,
            activity_id=activity_id,
            sport=sport,
            cross_sport=cross_sport,
            device=device,
            other_device=other_device,
            environment=environment,
            from_date=_parse_overlap_date(from_date, "from_date"),
            to_date=_parse_overlap_date(to_date, "to_date"),
            min_duration_seconds=min_duration_seconds,
            other_has=other_has,
            min_overlap_pct=min_overlap_pct,
            max_start_delta_seconds=max_start_delta_seconds,
            include_resolved=include_resolved,
            cursor=cursor,
            limit=max(1, min(limit or 50, 200)),
        )

    def get_activity(self, activity_id: str) -> dict[str, Any]:
        """Get full detail on a single activity by id (from list_activities' results) - name,
        sport, duration, distance, power/HR/TSS, elevation, calories, training effect, tags,
        linked workout/gear ids, average air temperature/humidity (device-reported ambient
        conditions - present for indoor rides too, from a smart trainer's onboard sensor, not
        just outdoor weather), - when a CORE body-temperature sensor was worn - avg/max heat
        strain/core temp/skin temp, and - when weigh-ins were recorded - start/end weight (kg)
        and fluids consumed (ml). Sweat rate isn't stored directly - compute it yourself as
        ((start_weight_kg - end_weight_kg) + fluids_ml / 1000) / (moving_time / 3600) L/h."""
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
            "avg_air_temp": activity.avg_air_temp,
            "avg_humidity": activity.avg_humidity,
            "avg_heat_strain": activity.avg_heat_strain,
            "max_heat_strain": activity.max_heat_strain,
            "avg_core_temp": activity.avg_core_temp,
            "max_core_temp": activity.max_core_temp,
            "avg_skin_temp": activity.avg_skin_temp,
            "max_skin_temp": activity.max_skin_temp,
            "start_weight_kg": activity.start_weight_kg,
            "end_weight_kg": activity.end_weight_kg,
            "fluids_ml": activity.fluids_ml,
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

        # Decimated in the query itself, not by loading every 1Hz record and slicing in Python -
        # see StreamsView.get's identical fix in activities/views.py for why.
        offset = activity.records.aggregate(first_t=Min("t"))["first_t"]
        records = (
            list(
                activity.records.annotate(mod_val=Mod(F("t") - offset, STREAM_RESOLUTION_STEP))
                .filter(mod_val=0)
                .order_by("t")
            )
            if offset is not None
            else []
        )

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

    def get_activity_custom_splits(self, activity_id: str, steps: list[dict[str, Any]]) -> dict[str, Any]:
        """Get splits for an activity from your own list of steps instead of a fixed distance -
        e.g. "the first mile, then every 2km after that", or steps that mirror a workout's own
        structure. Each step is either `{"end_type": "time", "duration_s": N}` (N seconds) or
        `{"end_type": "distance", "distance": "1mi"|"2km"|"800m"}` (a number with a unit) - add
        a "label" to name one (e.g. "Mile 1"). Steps are consumed in order from the start of the
        recording, each picking up where the previous one left off, so they can never overlap or
        be out of order - a step with no recording left to consume (the activity ended early) is
        just dropped, and any recording left over after the last step becomes one unlabeled
        trailing split. No goal/target to compare against here - see get_race_custom_splits for
        a goal race's own delta."""
        self._require_scope(ACTIVITIES_READ)
        activity = get_object_or_404(Activity, pk=activity_id)
        self._require_read(activity.athlete_id)
        return get_custom_activity_splits(activity, convert_custom_steps_input(steps))

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

    def post_activity_comment(
        self, activity_id: str, text: str, parent_comment_id: str | None = None
    ) -> dict[str, Any]:
        """Post a short comment on an activity (from list_activities/get_activity) - visible to
        the athlete and anyone else with access to it, e.g. coaching feedback on a specific
        session. Omit parent_comment_id for a top-level comment, or pass an existing top-level
        comment's id (from list_activity_comments) to reply to it - single-level threading only,
        replying to a reply is rejected."""
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
        parent = resolve_parent_comment(activity, parent_comment_id)

        comment = ActivityComment.objects.create(activity=activity, author_id=sub, parent=parent, text=text)
        return ActivityCommentSerializer(comment).data

    def update_activity(self, activity_id: str, name: str) -> dict[str, Any]:
        """Rename an activity (from list_activities/get_activity). Only the name can be changed
        through this tool - other fields (sport, linked workout/gear, weight/hydration/
        temperature overrides) require the web app."""
        self._require_scope(ACTIVITIES_WRITE)
        if not name or not name.strip():
            raise ValidationError({"name": "name cannot be empty."})
        activity = get_object_or_404(Activity, pk=activity_id)
        self._require_write(activity.athlete_id)
        activity.name = name
        activity.save(update_fields=["name"])
        return self.get_activity(activity_id)

    def tag_activity(self, activity_id: str, name: str) -> dict[str, Any]:
        """Attach a tag to an activity (from list_activities/get_activity) by name - creates the
        tag if the athlete doesn't already have one with this exact name, matching the web app's
        tag picker. A no-op if the activity already carries this tag."""
        self._require_scope(ACTIVITIES_WRITE)
        if not name or not name.strip():
            raise ValidationError({"name": "name cannot be empty."})
        activity = get_object_or_404(Activity, pk=activity_id)
        self._require_write(activity.athlete_id)
        tag, _created = Tag.objects.get_or_create(
            athlete_id=activity.athlete_id, name=name, defaults={"origin": "manual"}
        )
        ActivityTag.objects.get_or_create(activity=activity, tag=tag)
        return self.get_activity(activity_id)

    def mark_duplicate(self, activity_id: str, primary_id: str) -> dict[str, Any]:
        """Issue #498: marks activity_id as a duplicate recording of primary_id (from
        list_overlapping_activities/list_activities) - activity_id is hidden from TSS/CTL,
        threshold candidates and best efforts from now on, only primary_id counts. Both must
        belong to the same athlete, neither may already be linked some other way, and neither
        may be a multisport session or leg. Rebuilds the athlete's threshold history and
        re-rates affected activities' TSS/intensity before returning, since a duplicate-sourced
        best effort may no longer qualify as a threshold candidate."""
        self._require_scope(ACTIVITIES_WRITE)
        activity = get_object_or_404(Activity, pk=activity_id)
        self._require_write(activity.athlete_id)
        updated = mark_activity_duplicate(activity_id, primary_id)
        return _duplicate_link_summary(updated)

    def unmark_duplicate(self, activity_id: str) -> dict[str, Any]:
        """Issue #498: reverses mark_duplicate - activity_id counts toward TSS/CTL and threshold
        candidates again. A no-op (not an error) if it wasn't marked as a duplicate of anything.
        Rebuilds the athlete's threshold history and re-rates affected activities' TSS/intensity
        before returning."""
        self._require_scope(ACTIVITIES_WRITE)
        activity = get_object_or_404(Activity, pk=activity_id)
        self._require_write(activity.athlete_id)
        updated = unmark_activity_duplicate(activity_id)
        return _duplicate_link_summary(updated)

    def set_primary(self, activity_id: str) -> dict[str, Any]:
        """Issue #498: swaps which copy of a duplicate pair (or larger group) is primary -
        activity_id, currently hidden as a duplicate, becomes the visible primary, and the
        activity it used to duplicate becomes the duplicate instead. Fails if activity_id is
        already the primary (nothing to swap). Rebuilds the athlete's threshold history and
        re-rates affected activities' TSS/intensity before returning."""
        self._require_scope(ACTIVITIES_WRITE)
        activity = get_object_or_404(Activity, pk=activity_id)
        self._require_write(activity.athlete_id)
        updated = set_activity_primary(activity_id)
        return _duplicate_link_summary(updated)

    def merge_activities(
        self,
        target_id: str,
        source_id: str,
        fields: list[str],
        nulls: str | None = None,
        hide_source: bool = False,
        offset_seconds: int | None = None,
        preview: bool = True,
        copy_mode: str | None = None,
        source_kind: str | None = None,
    ) -> MergeResult:
        """Issue #501: copies fields from source_id into target_id - the target's timeline is
        authoritative, no stream rows are created or deleted. Resolves offset_seconds
        automatically (cross-correlating cadence, falling back to power, over a window around
        the two activities' start_date delta) unless offset_seconds overrides it. fields is any
        mix of stream fields (power, heartrate, cadence, altitude, lat, lng, speed, distance_km,
        air_temp, humidity, skin_temp, core_temp, heat_strain) and activity-level fields (name,
        avg_left_balance_pct, start_weight_kg, end_weight_kg, fluids_ml, bike_id, shoe_id). nulls
        controls what happens to a stream field where the source has no data for a second:
        "keep_original" (default) keeps the target's own value there; "overwrite" nulls it out
        to mirror the source exactly. hide_source (default false) also marks source_id as a
        hidden #498 duplicate of target_id. preview defaults to true here (the opposite of the
        web app's own default) - it computes and returns the exact result, including the
        resolved offset/alignment score and before/after summary, without writing anything; call
        again with preview=false once you're happy with it to actually apply the merge.
        copy_mode (issue #503 phase 1) defaults to "overwrite" (today's only prior behavior);
        "attach" accepts exactly one field at a time - power, heartrate, or cadence - and keeps
        target's existing stream for that field untouched, storing source's own stream alongside
        it as a non-primary source (see list_activity_sources/set_primary_source) instead of
        overwriting one with the other. source_kind (phase 3) is an optional tag for what kind of
        sensor the attached heartrate/cadence stream came from (strap/wrist for heartrate;
        pedals/trainer/watch for cadence) - ignored for power, which classifies by device
        instead; if it matches the athlete's hr_source/cadence_source preference, the attached
        stream becomes primary immediately, same as power's own auto-swap."""
        self._require_scope(ACTIVITIES_WRITE)
        target = get_object_or_404(Activity, pk=target_id)
        self._require_write(target.athlete_id)
        source = get_object_or_404(Activity, pk=source_id)
        self._require_write(source.athlete_id)
        return run_merge(
            target_id=target_id,
            source_id=source_id,
            fields=fields,
            null_policy=_parse_null_policy(nulls),
            offset_seconds=offset_seconds,
            hide_source=hide_source,
            preview=preview,
            copy_mode=_parse_copy_mode(copy_mode),
            source_kind=source_kind,
        )

    def unmerge_activity(self, activity_id: str) -> dict[str, Any]:
        """Issue #501: reverses activity_id's own latest active merge (from merge_activities) -
        restores its stream fields by re-parsing its own stored upload file, restores its
        activity-level fields from the snapshot the merge itself recorded, and un-hides the
        source if the merge had hidden it (unless something else has since re-resolved that
        source, which is left alone). Fails if there's no active merge, more than one is stacked
        on this activity (a merge chain - undo isn't supported for that), or the activity's own
        upload row has no stored file."""
        self._require_scope(ACTIVITIES_WRITE)
        activity = get_object_or_404(Activity, pk=activity_id)
        self._require_write(activity.athlete_id)
        undo_merge(activity_id)
        return self.get_activity(activity_id)

    def list_activity_sources(self, activity_id: str) -> list[dict[str, Any]]:
        """Issue #503 (phases 1-3): every non-primary stream kept alongside activity_id - power,
        heartrate, or cadence - e.g. a Garmin/pedal power recording attached onto a Zwift ride
        without overwriting the Zwift copy (merge_activities' attach copy_mode). The activity's
        own current primary stream is never listed here - it's whatever's already on the
        activity itself (get_activity); is_primary on a listed source instead tells you whether
        that source matches the current primary (by device classification for a bike power
        source, by its own source_kind tag otherwise), i.e. whether set_primary_source has
        nothing to do for it. Returns an empty list if nothing has ever been attached or
        swapped for this activity."""
        self._require_scope(ACTIVITIES_READ)
        activity = get_object_or_404(Activity, pk=activity_id)
        self._require_read(activity.athlete_id)
        return [summary._asdict() for summary in activity_source_service.list_sources(activity_id)]

    def set_primary_source(self, activity_id: str, field: str, source_id: str) -> dict[str, Any]:
        """Issue #503 (phases 1-3): swaps activity_id's current primary stream for field with the
        named source_id's stream (from list_activity_sources) - e.g. switching a ride's power
        back to its Garmin/pedal reading after a Zwift copy became primary. The stream being
        replaced is itself kept as a new source (never lost), so this can always be reversed by
        calling it again with the other source's id. Reruns the same recompute cascade a merge
        triggers, so TSS/FTP-candidate data reflects the new primary immediately. field must be
        "power", "heartrate", or "cadence"."""
        self._require_scope(ACTIVITIES_WRITE)
        activity = get_object_or_404(Activity, pk=activity_id)
        self._require_write(activity.athlete_id)
        activity_source_service.set_primary_source(activity_id, field, source_id)
        return self.get_activity(activity_id)

    def set_merge_rule(
        self,
        sport: str,
        target_pattern: str,
        source_pattern: str,
        fields: list[str],
        nulls: str | None = None,
    ) -> dict[str, Any]:
        """Issue #501 (phase 4): saves sport's automatic-merge rule - consulted whenever an
        overlapping pair of this sport is resolved with on_overlap="merge" (import-time) or the
        #498 backfill/resolve_duplicates_batch's own action="merge" (not the manual
        merge_activities call, which always takes its own explicit fields/nulls).
        target_pattern/source_pattern are "device:<prefix>" strings (e.g. "device:zwift"),
        matched case-insensitively as a prefix against each candidate's device - whichever
        activity in a pair matches target_pattern becomes the merge target, the one matching
        source_pattern becomes the source; a pair where neither orientation matches is left
        flagged rather than guessed. Overwrites any existing rule for this sport."""
        self._require_scope(ACTIVITIES_WRITE)
        athlete_id = self._effective_athlete_id()
        self._require_write(athlete_id)
        if sport in ("multisport", "transition"):
            raise ValidationError({"sport": "sport must be one of: bike, run, swim, walk, row."})
        saved = merge_rule_service.set_rule(
            athlete_id, sport, target_pattern, source_pattern, fields, _parse_null_policy(nulls)
        )
        return _merge_rule_response(saved.sport, saved)

    def get_merge_rules(self) -> dict[str, Any]:
        """Issue #501 (phase 4): the automatic-merge rule that would currently apply to each
        sport, whether saved (set_merge_rule) or built-in default (bike only: target
        device:zwift, source device:garmin, fields [power], keep_original) - a sport with
        neither is omitted entirely, meaning automatic merging simply doesn't apply to it; an
        unresolved pair of that sport is always left flagged for manual review."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)
        data = []
        for sport, _label in Activity.SPORT_CHOICES:
            if sport in ("multisport", "transition"):
                continue
            resolved = merge_rule_service.resolve(athlete_id, sport)
            if resolved is not None:
                data.append(_merge_rule_response(sport, resolved))
        return {"data": data}

    def get_merge(self, activity_id: str) -> dict[str, Any] | None:
        """Issue #501: the merge record for activity_id's currently active merge (from
        merge_activities), if any - which activity it was merged from, the fields copied, the
        null policy, the offset/alignment score used, per-field copied/kept_original/nulled
        counts, whether the source was hidden, and when it happened. Returns null if activity_id
        has no active merge (never merged, or its merge was already undone). source_id/
        source_name are null if the source activity has since been deleted outright."""
        self._require_scope(ACTIVITIES_READ)
        activity = get_object_or_404(Activity, pk=activity_id)
        self._require_read(activity.athlete_id)
        merge = (
            Merge.objects.filter(target_activity_id=activity_id, undone_at__isnull=True)
            .order_by("-created_at")
            .select_related("source_activity")
            .first()
        )
        return _merge_record_summary(merge) if merge else None

    def resolve_duplicates_batch(
        self,
        action: str,
        pairs: list[list[str]] | None = None,
        activity_id: str | None = None,
        sport: str | None = None,
        cross_sport: bool = False,
        device: str | None = None,
        other_device: str | None = None,
        environment: str | None = None,
        from_date: str | None = None,
        to_date: str | None = None,
        min_duration_seconds: int | None = None,
        other_has: list[str] | None = None,
        min_overlap_pct: float | None = None,
        max_start_delta_seconds: int | None = None,
        limit: int | None = None,
        dry_run: bool = True,
    ) -> dict[str, Any]:
        """Issue #498: applies `action` ("mark" or "merge" - merge currently falls back to flag
        everywhere, same as every other overlap path, until #501 ships) to many overlap pairs at
        once - either an explicit `pairs` list of [activity_id, other_activity_id], or every
        unresolved pair the overlap query's own filters find (same parameters as
        list_overlapping_activities: activity_id pins an anchor, sport/device/other_device/
        environment/from_date/to_date/min_duration_seconds/other_has/min_overlap_pct/
        max_start_delta_seconds narrow it). Pass exactly one of pairs or the filter kwargs. At
        most 100 pairs per call either way.

        `dry_run` (default true) only reports what would happen for each pair - which side
        would become primary, or that it would just be flagged - without writing anything.
        `dry_run=false` actually resolves every pair, recomputes the athlete's threshold history
        and TSS once at the end (not once per pair), and returns a `batch_id` you can pass to
        undo_batch to reverse the whole thing later."""
        self._require_scope(ACTIVITIES_WRITE)
        athlete_id = self._effective_athlete_id()
        sub = self._require_write(athlete_id)
        athlete = get_object_or_404(User, pk=athlete_id)
        set_by = athlete if sub == athlete_id else get_object_or_404(User, pk=sub)

        return resolve_duplicates_batch_action(
            athlete,
            action,
            pairs=pairs,
            activity_id=activity_id,
            sport=sport,
            cross_sport=cross_sport,
            device=device,
            other_device=other_device,
            environment=environment,
            from_date=_parse_overlap_date(from_date, "from_date"),
            to_date=_parse_overlap_date(to_date, "to_date"),
            min_duration_seconds=min_duration_seconds,
            other_has=other_has,
            min_overlap_pct=min_overlap_pct,
            max_start_delta_seconds=max_start_delta_seconds,
            limit=limit,
            dry_run=dry_run,
            set_by=set_by,
        )

    def undo_batch(self, batch_id: str) -> dict[str, Any]:
        """Issue #498: reverses a resolve_duplicates_batch(..., dry_run=false) call - unmarks
        every pair it actually marked (a flagged pair never changed anything, so there's nothing
        to reverse for it), restoring their gear mileage and recomputing the athlete's threshold
        history and TSS once at the end. A pair re-resolved or hand-edited since the batch
        applied is skipped rather than clobbered - see `skipped_count` in the result. Fails if
        this batch was already undone."""
        self._require_scope(ACTIVITIES_WRITE)
        athlete_id = self._effective_athlete_id()
        self._require_write(athlete_id)
        athlete = get_object_or_404(User, pk=athlete_id)
        return undo_batch_action(athlete, batch_id)

    def list_tags(self) -> dict[str, Any]:
        """List the authenticated athlete's tags with how many activities and workouts carry
        each one combined, most used first - use this to find a tag's exact name before
        renaming or deleting it."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)
        tags = (
            Tag.objects.filter(athlete_id=athlete_id).annotate(count=_combined_tag_count()).order_by("-count", "name")
        )
        return {
            "data": [{"id": t.id, "name": t.name, "origin": t.origin, "color": t.color, "count": t.count} for t in tags]
        }

    def _find_tag_by_name(self, athlete_id: str, name: str) -> Tag:
        # Exact match first: if the athlete has both "race" and "Race" (possible via the older
        # exact-match attach-by-name path), an iexact-only lookup would be ambiguous about which
        # one "race" refers to. Falls back to case-insensitive so a caller unsure of the exact
        # casing still finds the tag in the common case where no such duplicate exists.
        tag = Tag.objects.filter(athlete_id=athlete_id, name=name).first()
        if tag is None:
            tag = Tag.objects.filter(athlete_id=athlete_id, name__iexact=name).first()
        if tag is None:
            raise Http404(f'No tag named "{name}".')
        return tag

    def rename_tag(self, name: str, new_name: str) -> dict[str, Any]:
        """Renames a tag (see list_tags for exact names) to new_name. If the athlete already has
        a different tag with that name (case-insensitive), merges into it instead - every
        activity or workout carrying the old tag ends up carrying the existing one, and the old
        tag is removed. Returns the tag that now holds new_name."""
        self._require_scope(ACTIVITIES_WRITE)
        if not new_name or not new_name.strip():
            raise ValidationError({"new_name": "new_name cannot be empty."})
        athlete_id = self._effective_athlete_id()
        self._require_write(athlete_id)
        tag = self._find_tag_by_name(athlete_id, name)
        result = tag_rename(tag, new_name.strip())
        result = Tag.objects.annotate(count=_combined_tag_count()).get(pk=result.pk)
        return {
            "id": result.id,
            "name": result.name,
            "origin": result.origin,
            "color": result.color,
            "count": result.count,
        }

    def delete_tag(self, name: str) -> dict[str, Any]:
        """Deletes a tag (see list_tags for exact names) entirely, removing it from every
        activity and workout that carries it - not just an unused one."""
        self._require_scope(ACTIVITIES_WRITE)
        athlete_id = self._effective_athlete_id()
        self._require_write(athlete_id)
        tag = self._find_tag_by_name(athlete_id, name)
        deleted_name = tag.name
        tag.delete()
        return {"deleted": True, "name": deleted_name}

    def list_activity_comments(self, activity_id: str) -> dict[str, Any]:
        """List the comments on an activity (from list_activities/get_activity), oldest first -
        who wrote each one and their role (athlete/coach/viewer). Use this to see whether the
        athlete replied to a comment before posting another."""
        self._require_scope(ACTIVITIES_READ)
        activity = get_object_or_404(Activity, pk=activity_id)
        self._require_read(activity.athlete_id)
        comments = activity.comments.select_related("author", "activity").order_by("created")
        return {"data": ActivityCommentSerializer(comments, many=True).data}
