"""Issue #540 (phase 6): detects a wrist HR stream correlating with cadence instead of a
reference strap/pedal stream ("cadence lock" - a known wrist-optical HR failure mode: the sensor
loses a real pulse signal and the algorithm it runs falls back to arm-swing/cadence rhythm
instead). Genuinely new - no dropout_detection/implausible_jump_detection precedent to mirror
structurally; the thresholds below are this module's own proposal, untested against real TICKR
data (flagged in the PR, see issue #540's own design doc). Mirrors backend_java's
activities/calc/CadenceLockDetector - keep the two in sync when changing either.

Ship behind the same "always computed, never silently trusted" posture dropout_detection already
established (report confidence/evidence, let the athlete/UI judge) - this module never
auto-patches anything itself; it only ever reports what it found."""

from dataclasses import dataclass

from .stream_comparison import MIN_OVERLAP_SAMPLES, pearson

_WINDOW_SECONDS = 60
_STEP_SECONDS = 10  # sliding window hop
_LOCK_CORRELATION_THRESHOLD = 0.85  # Pearson r between HR and cadence within a window
_MIN_WINDOW_SAMPLES = MIN_OVERLAP_SAMPLES  # reuses stream_comparison's own floor, not a new value
_MIN_CADENCE_CV = 0.02  # coefficient of variation (std/mean) the window's cadence series must
# reach before its Pearson r is trusted - on a near-constant cadence (steady-state running, the
# common case) r between two low-variance series is noise-dominated and can spuriously cross the
# lock threshold, while the actual lock signature requires HR to track cadence *variation*, which
# needs variation. Untested guess, tuned alongside _LOCK_CORRELATION_THRESHOLD.


@dataclass(frozen=True)
class LockRun:
    start_t: int
    end_t: int  # note: DropoutRun/ImplausibleRun use start_t+length, not start_t+end_t - callers
    # assembling a combined streams/flags response normalize both shapes to one [start_t, end_t]
    # inclusive pair (see stream_flag_service.py).
    cadence_correlation: float
    reference_correlation: float | None  # None if no reference stream was available


def _coefficient_of_variation(values: list[float]) -> float:
    n = len(values)
    mean = sum(values) / n
    if mean == 0:
        return 0.0
    variance = sum((v - mean) ** 2 for v in values) / n
    return (variance**0.5) / mean


def _windows(start: int, end: int) -> list[tuple[int, int]]:
    """Half-open [window_start, window_start + _WINDOW_SECONDS) windows hopping by _STEP_SECONDS,
    covering [start, end]."""
    windows = []
    t = start
    while t < end:
        windows.append((t, t + _WINDOW_SECONDS))
        t += _STEP_SECONDS
    return windows


def _paired(
    hr: dict[int, int], cadence: dict[int, int], window_start: int, window_end: int
) -> list[tuple[int, float, float]]:
    return [(t, float(hr[t]), float(cadence[t])) for t in range(window_start, window_end) if t in hr and t in cadence]


def detect_cadence_lock(
    hr_primary: dict[int, int],
    cadence: dict[int, int],
    reference_hr: dict[int, int] | None,
) -> list[LockRun]:
    """Slides a _WINDOW_SECONDS window (hop _STEP_SECONDS) over hr_primary's overlap with
    cadence; windows whose cadence coefficient of variation (std/mean) is below _MIN_CADENCE_CV
    are skipped outright - on a near-constant cadence (steady-state running, the common case)
    Pearson r between two low-variance series is noise-dominated and can spuriously cross the
    lock threshold, while the actual lock signature requires HR to track cadence *variation*,
    which needs variation. For each remaining window with >= _MIN_WINDOW_SAMPLES paired seconds,
    computes Pearson r between hr_primary and cadence. Flags a window when that correlation >=
    _LOCK_CORRELATION_THRESHOLD AND, when reference_hr is available, the window's HR-vs-reference
    correlation is lower than its HR-vs-cadence correlation (HR is tracking the wrist's own
    cadence sensor better than it's tracking a presumed-truth HR source - the actual lock
    signature). With no reference_hr, flags on the cadence-correlation threshold alone - weaker
    evidence, same "no reference, no confirmation" caution as detect_implausible_jumps, so the
    response/UI must visibly distinguish reference-confirmed flags from reference_hr=None ones
    rather than presenting both identically. Adjacent/overlapping flagged windows are merged into
    one LockRun. Mirrors backend_java's CadenceLockDetector - keep in sync."""
    overlap_ts = sorted(set(hr_primary) & set(cadence))
    if not overlap_ts:
        return []

    flagged: list[tuple[int, int, float, float | None]] = []
    for window_start, window_end in _windows(overlap_ts[0], overlap_ts[-1] + 1):
        pairs = _paired(hr_primary, cadence, window_start, window_end)
        if len(pairs) < _MIN_WINDOW_SAMPLES:
            continue
        cadence_values = [c for _, _, c in pairs]
        if _coefficient_of_variation(cadence_values) < _MIN_CADENCE_CV:
            continue
        cadence_correlation = pearson([(h, c) for _, h, c in pairs])
        if cadence_correlation < _LOCK_CORRELATION_THRESHOLD:
            continue

        reference_correlation: float | None = None
        if reference_hr is not None:
            reference_pairs = [(hr_primary[t], reference_hr[t]) for t, _, _ in pairs if t in reference_hr]
            if len(reference_pairs) < _MIN_WINDOW_SAMPLES:
                continue
            reference_correlation = pearson(reference_pairs)
            if reference_correlation >= cadence_correlation:
                continue

        window_ts = [t for t, _, _ in pairs]
        flagged.append((window_ts[0], window_ts[-1], cadence_correlation, reference_correlation))

    return _merge_runs(flagged)


def _merge_runs(flagged: list[tuple[int, int, float, float | None]]) -> list[LockRun]:
    """Adjacent/overlapping flagged windows merge into one LockRun, keeping the strongest
    (highest) cadence_correlation among the merged windows as the reported value, and the
    reference_correlation that goes with it - a merged run is "reference-confirmed" overall only
    if every window that makes it up was (matching this module's own "no reference, no
    confirmation" caution: a run can't borrow confirmation from a neighboring window)."""
    if not flagged:
        return []
    flagged = sorted(flagged, key=lambda f: f[0])
    runs: list[LockRun] = []
    current_start, current_end, best_corr, best_ref_corr = flagged[0]
    any_unconfirmed = best_ref_corr is None
    for start, end, corr, ref_corr in flagged[1:]:
        if start <= current_end:
            current_end = max(current_end, end)
            any_unconfirmed = any_unconfirmed or ref_corr is None
            if corr > best_corr:
                best_corr, best_ref_corr = corr, ref_corr
        else:
            runs.append(LockRun(current_start, current_end, best_corr, None if any_unconfirmed else best_ref_corr))
            current_start, current_end, best_corr, best_ref_corr = start, end, corr, ref_corr
            any_unconfirmed = ref_corr is None
    runs.append(LockRun(current_start, current_end, best_corr, None if any_unconfirmed else best_ref_corr))
    return runs
