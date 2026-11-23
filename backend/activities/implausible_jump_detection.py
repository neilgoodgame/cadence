"""Issue #540 (phase 6): tells a real contact-loss glitch (a sensor reading a frozen/garbage
value or a physiologically-impossible jump for a few seconds, e.g. a wrist HR strap losing skin
contact) apart from a genuine physiological event, using corroborating evidence from a reference
stream on the same timeline (e.g. a chest strap against a wrist HR primary). Mirrors
backend_java's activities/calc/ImplausibleJumpDetector - keep the two in sync when changing
either.

Doesn't generalize dropout_detection.py's literal-0 power case: "coasting" (a real, large,
instant drop to 0 W) is normal for power but never physiologically normal for heartrate, so this
module is triggered by an implausible second-to-second *jump* instead. field="power" is
deliberately out of scope here - it keeps going through dropout_detection.py unchanged (see
stream_flag_service.py for how the two are dispatched behind one endpoint).

Same reference-corroboration interface as dropout_detection.detect_dropouts but single-tier, not
two-tier: unlike detect_dropouts there is no single-stream (cadence) fallback branch here at all
(see detect_implausible_jumps' own docstring) - confirmation requires a reference stream, full
stop."""

from dataclasses import dataclass

# Internal defaults, not request parameters - same status as dropout_detection's own
# _MAX_RUN_SECONDS/_REFERENCE_MIN_WATTS.
_MAX_PHYSIOLOGICAL_DELTA = {"heartrate": 25, "cadence": 40}  # bpm/s, rpm/s
_MAX_RUN_SECONDS = 10  # a longer implausible stretch is a real (if unusual) physiological event,
# not a sensor glitch - same reasoning as dropout_detection's own 5s cap, loosened slightly since
# an HR dropout-and-recovery can run a bit longer than a power zero-glitch. EXCEPTION, heartrate
# only: when the run's held level (median of its values) exceeds _MAX_PLAUSIBLE_HR, the length cap
# does not apply - a sustained implausible HR is never physiological (unlike power, where a
# sustained high reading is real effort), so a long freeze at an implausible level - the canonical
# TICKR failure - must not be dismissed as "unusual physiology". Reference confirmation still
# applies. Cadence keeps the plain cap (symmetry-only field, per this module's v1 scope note).
_MAX_PLAUSIBLE_HR = 215  # bpm - above any documented human max; internal default like the others.


@dataclass(frozen=True)
class ImplausibleRun:
    start_t: int
    length: int
    peak_delta: float


def _median(values: list[float]) -> float:
    ordered = sorted(values)
    n = len(ordered)
    mid = n // 2
    return ordered[mid] if n % 2 else (ordered[mid - 1] + ordered[mid]) / 2.0


def _boundary_ts(primary: dict[int, int | None], max_delta: float) -> list[int]:
    """Seconds t (with t-1 also present) where the jump from t-1 to t exceeds max_delta - the
    "entry"/"exit" jumps a glitch's spike shape is bounded by. Only computed across genuinely
    adjacent seconds (t-1, t both present and one second apart) - a data gap can't be read as a
    per-second rate, so it never contributes a boundary."""
    ts = sorted(t for t, v in primary.items() if v is not None)
    return [
        ts[i]
        for i in range(1, len(ts))
        if ts[i] == ts[i - 1] + 1 and abs(primary[ts[i]] - primary[ts[i - 1]]) > max_delta
    ]


def _find_runs(primary: dict[int, int | None], max_delta: float) -> list[tuple[list[int], int, int]]:
    """A candidate run is the held span between two consecutive boundary jumps - "jump away,
    hold at an implausible level, jump back", not a smooth ramp. A boundary jump with no
    following boundary (the glitch never resolves before the data ends) isn't reported - same
    "no trailing bracket, no confirmation" stance dropout_detection's single-stream fallback
    already takes at the very start of a recording. Only seconds actually present in primary are
    included in the run itself - a data gap inside the span (a missing sample that wasn't itself
    adjacent to its neighbor, so never became a boundary) is skipped rather than treated as a
    real held value. Each boundary pair is returned alongside the run (not re-derived from the
    filtered run's own first/last element) because entry_boundary - 1 / exit_boundary - 1 are
    guaranteed present by _boundary_ts's own adjacency check, whereas run[0] - 1 / run[-1] + 1
    aren't once a gap sits right at either edge - both boundaries are always present and included
    in the run, so a run is never empty."""
    boundaries = _boundary_ts(primary, max_delta)
    return [
        (
            [t for t in range(boundaries[i], boundaries[i + 1]) if primary.get(t) is not None],
            boundaries[i],
            boundaries[i + 1],
        )
        for i in range(len(boundaries) - 1)
    ]


def detect_implausible_jumps(
    primary: dict[int, int | None],
    field: str,
    reference: dict[int, int | None] | None,
) -> tuple[set[int], list[ImplausibleRun]]:
    """Finds runs of consecutive seconds where primary's second-to-second change exceeds
    _MAX_PHYSIOLOGICAL_DELTA[field] on entry and/or exit (a glitch looks like a spike: jump away,
    hold at an implausible level, jump back - not a smooth ramp), confirmed as a dropout only when
    short (<= _MAX_RUN_SECONDS) - except heartrate runs held above _MAX_PLAUSIBLE_HR, confirmed at
    any length (the freeze case) - and reference (same-timeline corroborating stream) stays smooth
    and plausible throughout the run - no reference, no confirmation (unlike dropout_detection's
    cadence fallback, there's no reliable single-stream signature for an HR glitch, so this
    returns nothing confirmed without a reference rather than guessing). field must be
    "heartrate" or "cadence" - "power" keeps going through dropout_detection.py. Mirrors
    backend_java's ImplausibleJumpDetector - keep in sync."""
    if field not in _MAX_PHYSIOLOGICAL_DELTA:
        raise ValueError(f"detect_implausible_jumps does not support field '{field}'.")
    max_delta = _MAX_PHYSIOLOGICAL_DELTA[field]

    confirmed_ts: set[int] = set()
    confirmed_runs: list[ImplausibleRun] = []
    for run, entry_boundary, exit_boundary in _find_runs(primary, max_delta):
        held_level = _median([primary[t] for t in run])
        long_freeze_exempt = field == "heartrate" and held_level > _MAX_PLAUSIBLE_HR
        if len(run) > _MAX_RUN_SECONDS and not long_freeze_exempt:
            continue

        if reference is None:
            continue
        reference_values = [reference.get(t) for t in run]
        if any(v is None for v in reference_values):
            continue
        # Only checked across genuinely adjacent seconds in the run (a gap, if any, isn't a
        # real per-second rate to hold reference to - same adjacency-only stance _boundary_ts
        # itself takes).
        smooth = all(
            abs(reference_values[i] - reference_values[i - 1]) <= max_delta
            for i in range(1, len(reference_values))
            if run[i] == run[i - 1] + 1
        )
        plausible = field != "heartrate" or all(v <= _MAX_PLAUSIBLE_HR for v in reference_values)
        if not (smooth and plausible):
            continue

        entry_delta = abs(primary[entry_boundary] - primary[entry_boundary - 1])
        exit_delta = abs(primary[exit_boundary] - primary[exit_boundary - 1])
        confirmed_ts.update(run)
        confirmed_runs.append(
            ImplausibleRun(start_t=entry_boundary, length=len(run), peak_delta=max(entry_delta, exit_delta))
        )
    return confirmed_ts, confirmed_runs
