"""Issue #541: activities.dropout_detection is pure computation (no DB), so these are plain
functions, not a TestCase - conftest.py's auto-tagging puts them in the `unit` bucket. Mirrors
backend_java's DropoutDetectorTest."""

from ..dropout_detection import DropoutRun, detect_dropouts


def _constant(n: int, value: int) -> dict[int, int]:
    return dict.fromkeys(range(n), value)


def test_a_short_zero_run_with_a_strong_reference_is_a_confirmed_dropout():
    primary = {**_constant(10, 200), 4: 0, 5: 0, 6: 0}
    reference = _constant(10, 220)  # pedalling hard throughout, on the other stream

    dropout_ts, runs = detect_dropouts(primary, reference, cadence=None)

    assert dropout_ts == {4, 5, 6}
    assert runs == [DropoutRun(start_t=4, length=3)]


def test_mutual_near_zero_is_not_a_dropout():
    """Genuine coasting/a real stop - both streams agree it's near-zero, so there's no
    corroborating evidence of a glitch."""
    primary = {**_constant(10, 200), 4: 0, 5: 0, 6: 0}
    reference = {**_constant(10, 200), 4: 2, 5: 0, 6: 1}  # also near-zero at the same seconds

    dropout_ts, runs = detect_dropouts(primary, reference, cadence=None)

    assert dropout_ts == set()
    assert runs == []


def test_a_run_longer_than_five_seconds_is_not_a_dropout_even_with_a_strong_reference():
    primary = {**_constant(12, 200), 2: 0, 3: 0, 4: 0, 5: 0, 6: 0, 7: 0}  # 6 s, too long
    reference = _constant(12, 220)

    dropout_ts, runs = detect_dropouts(primary, reference, cadence=None)

    assert dropout_ts == set()
    assert runs == []


def test_single_stream_fallback_confirms_with_cadence_and_strong_brackets():
    primary = {**_constant(10, 200), 4: 0, 5: 0}
    cadence = _constant(10, 85)  # still pedalling throughout, including the gap

    dropout_ts, runs = detect_dropouts(primary, reference=None, cadence=cadence)

    assert dropout_ts == {4, 5}
    assert runs == [DropoutRun(start_t=4, length=2)]


def test_single_stream_fallback_rejects_a_real_freewheel_stop():
    """Cadence at 0 throughout the gap means the rider actually stopped pedalling - not a
    dropout, even though the shape (short run, bracketed by real power) looks similar."""
    primary = {**_constant(10, 200), 4: 0, 5: 0}
    cadence = {**_constant(10, 85), 4: 0, 5: 0}

    dropout_ts, runs = detect_dropouts(primary, reference=None, cadence=cadence)

    assert dropout_ts == set()
    assert runs == []


def test_single_stream_fallback_rejects_a_run_at_the_very_start_with_no_leading_bracket():
    primary = {0: 0, 1: 0, 2: 200, 3: 200, 4: 200}
    cadence = _constant(5, 85)

    dropout_ts, runs = detect_dropouts(primary, reference=None, cadence=cadence)

    assert dropout_ts == set()
    assert runs == []


def test_no_reference_and_no_cadence_confirms_nothing():
    primary = {**_constant(10, 200), 4: 0, 5: 0}

    dropout_ts, runs = detect_dropouts(primary, reference=None, cadence=None)

    assert dropout_ts == set()
    assert runs == []


def test_two_separate_runs_are_reported_independently():
    primary = {**_constant(14, 200), 2: 0, 3: 0, 9: 0}
    reference = _constant(14, 220)

    dropout_ts, runs = detect_dropouts(primary, reference, cadence=None)

    assert dropout_ts == {2, 3, 9}
    assert runs == [DropoutRun(start_t=2, length=2), DropoutRun(start_t=9, length=1)]


def test_a_missing_sample_is_not_treated_as_a_zero():
    primary = {0: 200, 2: 200}  # t=1 simply absent, not a recorded 0
    reference = _constant(3, 220)

    dropout_ts, runs = detect_dropouts(primary, reference, cadence=None)

    assert dropout_ts == set()
    assert runs == []
