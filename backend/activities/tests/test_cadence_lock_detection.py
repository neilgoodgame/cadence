"""Issue #540 (phase 6): activities.cadence_lock_detection is pure computation (no DB), so these
are plain functions, not a TestCase - conftest.py's auto-tagging puts them in the `unit` bucket.
Mirrors backend_java's CadenceLockDetectorTest.

All scenarios use a 60-second series (t=0..59) with values alternating by parity so Pearson r is
exactly computable by hand (a perfect two-value alternation in lockstep is r=1.0; against a
constant series it's r=0.0, since the formula's denominator is zero for a no-variance series)."""

from ..cadence_lock_detection import LockRun, detect_cadence_lock


def _alternating(n: int, even_value: float, odd_value: float) -> dict[int, float]:
    return {t: (even_value if t % 2 == 0 else odd_value) for t in range(n)}


def _constant(n: int, value: float) -> dict[int, float]:
    return dict.fromkeys(range(n), value)


def test_a_locked_window_confirmed_by_an_uncorrelated_reference_is_flagged():
    cadence = _alternating(60, 90, 70)  # mean 80, std 10, cv 0.125 - well above the guard
    hr = _alternating(60, 152, 148)  # same parity as cadence -> perfectly correlated with it
    reference_hr = _constant(60, 150)  # a real strap reading flat throughout -> r=0 against hr

    runs = detect_cadence_lock(hr, cadence, reference_hr)

    assert runs == [LockRun(start_t=0, end_t=59, cadence_correlation=1.0, reference_correlation=0.0)]


def test_without_a_reference_flags_on_cadence_correlation_alone():
    cadence = _alternating(60, 90, 70)
    hr = _alternating(60, 152, 148)

    runs = detect_cadence_lock(hr, cadence, reference_hr=None)

    assert runs == [LockRun(start_t=0, end_t=59, cadence_correlation=1.0, reference_correlation=None)]


def test_a_reference_that_tracks_hr_as_well_as_cadence_does_is_not_confirmed():
    """The actual lock signature is HR tracking cadence *better* than it tracks the reference -
    here the reference matches hr exactly, so there's no such gap and nothing is flagged."""
    cadence = _alternating(60, 90, 70)
    hr = _alternating(60, 152, 148)
    reference_hr = dict(hr)  # tracks hr perfectly too - r=1.0, same as cadence_correlation

    runs = detect_cadence_lock(hr, cadence, reference_hr)

    assert runs == []


def test_a_steady_state_window_is_not_flagged_despite_high_spurious_correlation():
    """cadence varies by only +/-0.5 around 80 (cv ~0.0062, below _MIN_CADENCE_CV) while hr
    happens to alternate in lockstep (r=1.0 if computed naively) - the near-constant-cadence case
    the variance guard exists for: real steady-state running, not a lock."""
    cadence = _alternating(60, 81, 80)
    hr = _alternating(60, 151, 150)

    runs = detect_cadence_lock(hr, cadence, reference_hr=None)

    assert runs == []


def test_too_little_data_for_a_single_window_confirms_nothing():
    cadence = _alternating(20, 90, 70)  # only 20 paired seconds, below _MIN_WINDOW_SAMPLES
    hr = _alternating(20, 152, 148)

    runs = detect_cadence_lock(hr, cadence, reference_hr=None)

    assert runs == []


def test_no_overlap_between_hr_and_cadence_confirms_nothing():
    hr = _alternating(60, 152, 148)
    cadence: dict[int, float] = {}

    runs = detect_cadence_lock(hr, cadence, reference_hr=None)

    assert runs == []
