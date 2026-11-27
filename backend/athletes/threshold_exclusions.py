"""Issue #616: excluding an activity from threshold detection for one field - the service behind
POST/GET/DELETE /v1/athletes/{id}/threshold-exclusions. Excluding (or removing an exclusion)
rebuilds that field's ledger from the activity's date and re-rates TSS/intensity from there,
through the same recompute_after_duplicate_change cascade a duplicate-status change uses, since
both take one activity out of (or put it back into) threshold detection. Mirrors backend_java's
ThresholdExclusionService.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from django.db import transaction
from rest_framework.exceptions import NotFound, ValidationError

from accounts.models import User
from activities.models import Activity

from . import threshold_suggestions
from .models import THRESHOLD_FIELD_CHOICES, AcceptedThresholdCandidate, ExcludedThresholdCandidate, ThresholdHistory
from .threshold_history import FIELD_SPORT, _row_value, _seconds_to_mmss

_FIELDS = {value for value, _label in THRESHOLD_FIELD_CHOICES}

# A ledger row's identity for the before/after diff - everything but its id, which a rebuild
# changes even when the row itself comes back identical.
_RowKey = tuple[str, str | None, date, date, float | None]


@dataclass(frozen=True)
class ExclusionEffect:
    field: str
    activity_id: str
    excluded: bool
    preview: bool
    value_before: float | None
    value_after: float | None
    rows_removed: list[_RowKey]
    rows_added: list[_RowKey]


def validate_field(field: str | None) -> str:
    if field not in _FIELDS:
        raise ValidationError({"field": f"Must be one of {', '.join(sorted(_FIELDS))}."})
    return field


def _activity_for(athlete: User, field: str, activity_id: str) -> Activity:
    activity = Activity.objects.filter(pk=activity_id, athlete=athlete).first()
    if activity is None:
        raise NotFound("No such activity.")
    if activity.sport != FIELD_SPORT[field]:
        raise ValidationError({"activity_id": f"A {activity.sport} activity can't define {field}."})
    return activity


def _ledger_from(athlete: User, field: str, from_date: date) -> list[_RowKey]:
    rows = ThresholdHistory.objects.filter(athlete=athlete, field=field, current_from__gte=from_date)
    keys = [
        (row.kind, row.source_activity_id, row.effective_from, row.current_from, _row_value(field, row)) for row in rows
    ]
    # By date first; source ids are None for manual/decayed rows, so they can't be compared raw.
    return sorted(keys, key=lambda k: (k[3], k[2], k[0], k[1] or "", k[4] if k[4] is not None else 0))


def _live_value(athlete: User, field: str) -> float | None:
    athlete.refresh_from_db(fields=[field])
    raw = getattr(athlete, field)
    if field == "threshold_pace":
        from .threshold_history import _mmss_to_seconds

        return _mmss_to_seconds(raw) if raw else None
    return raw


def _apply(athlete: User, field: str, activity: Activity, change, preview: bool, excluded: bool) -> ExclusionEffect:
    """Runs `change` (the row write) plus the recompute it triggers, diffing the ledger from the
    activity's date before and after. `preview` rolls all of it back, so the diff is exactly what
    a real run would do, with nothing persisted."""
    from uploads.processing import recompute_after_duplicate_change  # uploads imports athletes

    from_date = activity.start_date.date()
    with transaction.atomic():
        value_before = _live_value(athlete, field)
        before = _ledger_from(athlete, field, from_date)
        changed = change()
        if changed:
            recompute_after_duplicate_change(athlete, fields=[field], from_date=from_date)
        after = _ledger_from(athlete, field, from_date)
        value_after = _live_value(athlete, field)
        if preview:
            transaction.set_rollback(True)
    if preview:
        athlete.refresh_from_db()
    elif changed:
        # The ledger was rebuilt, and the activity left (or rejoined) every scan - so the cached
        # suggestion list may now be wrong, e.g. still offering the excluded ride as a "rejected"
        # candidate. Same rule as every other ThresholdHistory write (see invalidate's docstring).
        threshold_suggestions.invalidate(athlete.id)
    before_set, after_set = set(before), set(after)
    return ExclusionEffect(
        field=field,
        activity_id=activity.id,
        excluded=excluded,
        preview=preview,
        value_before=value_before,
        value_after=value_after,
        rows_removed=[row for row in before if row not in after_set],
        rows_added=[row for row in after if row not in before_set],
    )


def exclude(
    athlete: User, field: str, activity_id: str, set_by: User | None = None, preview: bool = False
) -> ExclusionEffect:
    """Excludes `activity_id` from `field`'s threshold detection, removing any acceptance of the
    same (field, activity) - exclusion wins - and rebuilds the ledger from the activity's date.
    Idempotent: excluding an already-excluded activity changes nothing."""
    field = validate_field(field)
    activity = _activity_for(athlete, field, activity_id)

    def change() -> bool:
        _row, created = ExcludedThresholdCandidate.objects.get_or_create(
            athlete=athlete, field=field, activity=activity, defaults={"set_by": set_by}
        )
        AcceptedThresholdCandidate.objects.filter(athlete=athlete, field=field, activity=activity).delete()
        return created

    return _apply(athlete, field, activity, change, preview, excluded=True)


def include(athlete: User, field: str, activity_id: str, preview: bool = False) -> ExclusionEffect:
    """Removes the exclusion, so the activity counts again, and rebuilds the ledger from its date.
    404 if the activity isn't excluded for `field`."""
    field = validate_field(field)
    activity = _activity_for(athlete, field, activity_id)
    if not ExcludedThresholdCandidate.objects.filter(athlete=athlete, field=field, activity=activity).exists():
        raise NotFound("That activity isn't excluded for this field.")

    def change() -> bool:
        ExcludedThresholdCandidate.objects.filter(athlete=athlete, field=field, activity=activity).delete()
        return True

    return _apply(athlete, field, activity, change, preview, excluded=False)


def list_exclusions(
    athlete: User, field: str | None = None, activity_id: str | None = None
) -> list[ExcludedThresholdCandidate]:
    qs = ExcludedThresholdCandidate.objects.filter(athlete=athlete).select_related("activity")
    if field is not None:
        qs = qs.filter(field=validate_field(field))
    if activity_id is not None:
        qs = qs.filter(activity_id=activity_id)
    return list(qs.order_by("activity__start_date", "field"))


def wire_value(field: str, value: float | None) -> int | str | None:
    if value is None:
        return None
    return _seconds_to_mmss(value) if field == "threshold_pace" else round(value)


def exclusion_wire(row: ExcludedThresholdCandidate) -> dict:
    return {
        "field": row.field,
        "activity_id": row.activity_id,
        "activity_name": row.activity.name,
        "activity_date": row.activity.start_date.date(),
        "set_by_user_id": row.set_by_id,
        "created_at": row.created_at,
    }


def effect_wire(effect: ExclusionEffect) -> dict:
    def rows(keys: list[_RowKey]) -> list[dict]:
        return [
            {
                "kind": kind,
                "source_activity_id": source,
                "effective_from": effective_from,
                "current_from": current_from,
                "value": wire_value(effect.field, value),
            }
            for kind, source, effective_from, current_from, value in keys
        ]

    return {
        "field": effect.field,
        "activity_id": effect.activity_id,
        "excluded": effect.excluded,
        "preview": effect.preview,
        "value_before": wire_value(effect.field, effect.value_before),
        "value_after": wire_value(effect.field, effect.value_after),
        "rows_removed": rows(effect.rows_removed),
        "rows_added": rows(effect.rows_added),
    }
