"""Issue #540 (phase 6): GET .../streams/flags - orchestrates two different detectors behind one
read-only endpoint, since "detect contact-loss" and "detect cadence-lock" dispatch to entirely
different pure-computation modules (field="power" contact-loss delegates to dropout_detection,
unchanged; heartrate/cadence contact-loss delegates to implausible_jump_detection; cadence-lock
delegates to cadence_lock_detection), with one consistent request/response shape. That's why this
lives in its own module rather than extending either detector or activity_source_service.py
itself.

Never persists - flags are always recomputed fresh, same stance dropout_detection's existing call
sites already take. See stream_patch_service.py for the write side (POST streams/patch/unpatch),
which calls back into this module for its own `segments: "flagged"` case.
"""

from dataclasses import dataclass
from typing import Literal

from django.shortcuts import get_object_or_404
from rest_framework.exceptions import ValidationError

from .activity_source_service import PRIMARY_SOURCE_REF, VALID_FIELDS, load_field_series
from .cadence_lock_detection import detect_cadence_lock
from .dropout_detection import detect_dropouts
from .implausible_jump_detection import detect_implausible_jumps
from .models import Activity

Kind = Literal["contact_loss", "cadence_lock"]
KINDS = ("contact_loss", "cadence_lock")


@dataclass(frozen=True)
class FlagSegment:
    """One flagged [start_t, end_t] inclusive span, normalized from whichever detector produced
    it - DropoutRun/ImplausibleRun carry start_t+length, LockRun already carries start_t+end_t;
    this is the one shape stream_patch_service.py and the REST response both deal in.
    reference_confirmed is always True for contact_loss (both of its detectors already filter out
    everything they can't confirm before returning - see detect_dropouts/detect_implausible_jumps'
    own docstrings); for cadence_lock it reflects whether that specific run had a corroborating
    reference_hr - see cadence_lock_detection's own "no reference, no confirmation" caution."""

    start_t: int
    end_t: int
    reference_confirmed: bool


@dataclass(frozen=True)
class StreamFlagsResult:
    field: str
    kind: Kind
    segments: list[FlagSegment]


def _validate_field(field: str) -> None:
    if field not in VALID_FIELDS:
        raise ValidationError({"field": "field must be 'power', 'heartrate', or 'cadence'."})


def _contact_loss_segments(activity: Activity, field: str, reference_ref: str | None) -> list[FlagSegment]:
    _validate_field(field)
    primary = load_field_series(activity, field, PRIMARY_SOURCE_REF)
    reference_series = load_field_series(activity, field, reference_ref) if reference_ref else None

    if field == "power":
        # Same single-stream fallback activity_source_service._detect_power_dropouts already
        # uses: cadence is only loaded when there's no reference to confirm against.
        cadence_series = (
            load_field_series(activity, "cadence", PRIMARY_SOURCE_REF) if reference_series is None else None
        )
        _, runs = detect_dropouts(primary, reference_series, cadence_series)
        return [FlagSegment(run.start_t, run.start_t + run.length - 1, True) for run in runs]

    _, jump_runs = detect_implausible_jumps(primary, field, reference_series)
    return [FlagSegment(run.start_t, run.start_t + run.length - 1, True) for run in jump_runs]


def _cadence_lock_segments(activity: Activity, field: str, reference_ref: str | None) -> list[FlagSegment]:
    if field != "heartrate":
        raise ValidationError({"field": "kind=cadence_lock only supports field='heartrate'."})
    hr_primary = load_field_series(activity, "heartrate", PRIMARY_SOURCE_REF)
    cadence = load_field_series(activity, "cadence", PRIMARY_SOURCE_REF)
    reference_hr = load_field_series(activity, "heartrate", reference_ref) if reference_ref else None

    lock_runs = detect_cadence_lock(hr_primary, cadence, reference_hr)
    return [FlagSegment(run.start_t, run.end_t, run.reference_correlation is not None) for run in lock_runs]


def detect_stream_flags(activity_id: str, field: str, kind: str, reference: str | None) -> StreamFlagsResult:
    """kind="contact_loss": field may be "power"/"heartrate"/"cadence". kind="cadence_lock":
    field must be "heartrate". reference, when given, is a source ref ("primary" or a real
    ActivitySource id, same shape as compare_streams' a/b) - the corroborating stream; omitting it
    is allowed (weaker evidence - see each detector's own "no reference" behavior)."""
    if kind not in KINDS:
        raise ValidationError({"kind": "kind must be 'contact_loss' or 'cadence_lock'."})
    activity = get_object_or_404(Activity, pk=activity_id)
    segments = (
        _contact_loss_segments(activity, field, reference)
        if kind == "contact_loss"
        else _cadence_lock_segments(activity, field, reference)
    )
    return StreamFlagsResult(field=field, kind=kind, segments=segments)


def stream_flags_response(result: StreamFlagsResult) -> dict:
    return {
        "field": result.field,
        "kind": result.kind,
        "segments": [
            {"start_t": s.start_t, "end_t": s.end_t, "reference_confirmed": s.reference_confirmed}
            for s in result.segments
        ],
    }
