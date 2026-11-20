"""Issue #541: tells a real power-meter dropout (a sensor/connection glitch that reads 0 W for a
few seconds while the rider keeps pedalling) apart from genuine coasting (0 W because the rider
really did stop pedalling), using corroborating evidence from a second stream on the same
timeline when one's available, or cadence as a single-stream fallback. Mirrors backend_java's
activities/calc/DropoutDetector - keep the two in sync when changing either.

A related but blunter precedent already exists in workouts/match_scan.py (`if not power:
continue`) - it treats *any* literal 0 as a dropout unconditionally, which is safe there only
because that module never has a genuine 0-effort target to confuse it with. This module can't
make that assumption, so it requires corroborating evidence and a bounded run length instead.
"""

from collections.abc import Iterable
from dataclasses import dataclass

# Internal defaults, not request parameters - the issue frames these as defaults, not
# athlete-configurable settings, so there's no API surface for them yet.
_MAX_RUN_SECONDS = 5
_REFERENCE_MIN_WATTS = 100


@dataclass(frozen=True)
class DropoutRun:
    start_t: int
    length: int


def detect_dropouts(
    primary: dict[int, int | None],
    reference: dict[int, int | None] | None,
    cadence: dict[int, int | None] | None,
) -> tuple[set[int], list[DropoutRun]]:
    """Finds runs of consecutive seconds where `primary` reads a literal 0 and confirms each one
    as a dropout (not real data) only when it's short (<= 5 s - a longer zero stretch is more
    likely a real stop) and there's evidence it happened mid-effort:
    - `reference` (another stream on the *same* timeline as `primary`, e.g. the other side of a
      merge/attach/comparison) reads > 100 W at every second of the run, or
    - with no `reference` available, `cadence` (on `primary`'s own timeline) reads > 0 throughout
      the run *and* the seconds immediately bracketing it in `primary` itself read > 100 W -
      pedalling hard right before and after a gap is the single-stream signature of a glitch
      rather than a real stop.
    Returns the confirmed dropout seconds as a flat set (for O(1) membership checks by callers)
    and the run list (for reporting). Neither input dict needs every second covered - a missing
    key is treated as "no data there", distinct from a real 0."""
    zero_ts = sorted(t for t, v in primary.items() if v == 0)
    runs: list[list[int]] = []
    for t in zero_ts:
        if runs and runs[-1][-1] == t - 1:
            runs[-1].append(t)
        else:
            runs.append([t])

    dropout_ts: set[int] = set()
    dropout_runs: list[DropoutRun] = []
    for run in runs:
        if len(run) > _MAX_RUN_SECONDS:
            continue
        if reference is not None:
            # A second with no reference sample at all (None) is neutral - neither confirms nor
            # disconfirms, since the reference stream can drop a sample too (its own, unrelated
            # hiccup shouldn't sink the rest of an otherwise well-corroborated run). A second
            # where the reference genuinely reads <= 100 W does disconfirm (that's the "mutual
            # near-zero" case - real coasting, not a one-sided glitch). At least one second must
            # actually confirm - a run with no reference data at all for any of its seconds is
            # unconfirmed, not blindly trusted.
            reference_values = [reference.get(t) for t in run]
            disconfirmed = any(v is not None and v <= _REFERENCE_MIN_WATTS for v in reference_values)
            confirmed = not disconfirmed and any(v is not None and v > _REFERENCE_MIN_WATTS for v in reference_values)
        elif cadence is not None:
            pedalling = all((cadence.get(t) or 0) > 0 for t in run)
            before = primary.get(run[0] - 1)
            after = primary.get(run[-1] + 1)
            confirmed = (
                pedalling
                and before is not None
                and before > _REFERENCE_MIN_WATTS
                and after is not None
                and after > _REFERENCE_MIN_WATTS
            )
        else:
            confirmed = False
        if confirmed:
            dropout_ts.update(run)
            dropout_runs.append(DropoutRun(run[0], len(run)))
    return dropout_ts, dropout_runs


def build_reference(
    target_values_by_t: dict[int, int | None], offset_seconds: int, source_ts: Iterable[int]
) -> dict[int, int | None] | None:
    """Builds the `reference` dict detect_dropouts expects - source-local t -> the target's own
    value at the corresponding target-local t (source-local t + offset_seconds) - or None if the
    target has no data at all overlapping these seconds (e.g. attaching power onto an activity
    recorded on a device with no power meter). Returning None here, not a dict of every value
    None, matters: detect_dropouts' own `reference is not None` branch would otherwise always be
    taken even when there's genuinely nothing to compare against, silently defeating the
    cadence-only single-stream fallback it exists for. One shared helper (merge_apply.py's
    _compute and activity_source_service.py's attach_stream both call this, instead of each
    building the dict and this check inline) so this exact bug can't resurface in just one of
    the two call sites later."""
    reference = {t: target_values_by_t.get(t + offset_seconds) for t in source_ts}
    return reference if any(v is not None for v in reference.values()) else None
