"""Issue #501 (phase 1): activities.merge_alignment is pure computation (no DB), so these are
plain functions, not a TestCase - conftest.py's auto-tagging puts them in the `unit` bucket.
Mirrors backend_java's ActivityMergeAlignmentCalculatorTest."""

import random

from ..merge_alignment import align


# A deterministic-but-non-periodic signal keyed by t. A periodic signal (e.g. a sine wave) is the
# wrong fixture here: any offset differing from the true one by a multiple of the period produces
# an equally perfect correlation ("aliasing"), so the test would pass even if the search picked a
# plausible-looking wrong answer. This has no repeating structure, so only the true offset lines
# a shifted copy up with itself.
def _profile(t: int, seed: int = 0, base: float = 80.0, amplitude: float = 10.0) -> int:
    return round(base + amplitude * random.Random(seed * 100_000 + t).uniform(-1, 1))


def _records(duration_s: int, cadence_fn=None, power_fn=None) -> list[tuple[int, int | None, int | None]]:
    return [(t, cadence_fn(t) if cadence_fn else None, power_fn(t) if power_fn else None) for t in range(duration_s)]


def _shifted_records(duration_s: int, real_offset: int, cadence_fn=None, power_fn=None):
    """Builds a "secondary" activity's own (t, cadence, power) rows, where its local time t
    represents the same real-world moment as the primary's local time (t + real_offset) - i.e.
    the secondary started `real_offset` seconds after the primary."""
    return [
        (t, cadence_fn(t + real_offset) if cadence_fn else None, power_fn(t + real_offset) if power_fn else None)
        for t in range(duration_s)
    ]


def test_recovers_a_known_offset_from_correlated_cadence():
    real_offset = 45
    primary = _records(300, cadence_fn=_profile)
    secondary = _shifted_records(300, real_offset, cadence_fn=_profile)

    result = align(primary, secondary, initial_offset_seconds=real_offset)

    assert result.signal == "cadence"
    assert abs(result.offset_seconds - real_offset) <= 1
    assert result.score > 0.9


def test_refines_a_slightly_wrong_initial_guess_to_the_true_offset():
    # A caller's initial guess (start_date delta) can be off by a few seconds of clock skew -
    # the +/-120 s search must still land on the real offset, not just echo back a bad guess.
    real_offset = 45
    primary = _records(300, cadence_fn=_profile)
    secondary = _shifted_records(300, real_offset, cadence_fn=_profile)

    result = align(primary, secondary, initial_offset_seconds=real_offset - 15)

    assert abs(result.offset_seconds - real_offset) <= 1
    assert result.score > 0.9


def test_falls_back_to_power_when_neither_side_has_cadence():
    real_offset = 20
    primary = _records(300, power_fn=lambda t: _profile(t, seed=1, base=200, amplitude=50))
    secondary = _shifted_records(300, real_offset, power_fn=lambda t: _profile(t, seed=1, base=200, amplitude=50))

    result = align(primary, secondary, initial_offset_seconds=real_offset)

    assert result.signal == "power"
    assert abs(result.offset_seconds - real_offset) <= 1
    assert result.score > 0.9


def test_prefers_cadence_over_power_when_both_are_present():
    real_offset = 10
    rng = random.Random(7)
    # Power is present on both sides but deliberately uncorrelated - only cadence should drive
    # the result if cadence really is tried first.
    primary = [(t, _profile(t, seed=2), rng.randint(100, 300)) for t in range(300)]
    secondary = [(t, _profile(t + real_offset, seed=2), rng.randint(100, 300)) for t in range(300)]

    result = align(primary, secondary, initial_offset_seconds=real_offset)

    assert result.signal == "cadence"
    assert abs(result.offset_seconds - real_offset) <= 1
    assert result.score > 0.9


def test_low_score_for_uncorrelated_cadence():
    rng = random.Random(42)
    primary = _records(300, cadence_fn=lambda t: rng.randint(60, 100))
    rng2 = random.Random(99)
    secondary = _records(300, cadence_fn=lambda t: rng2.randint(60, 100))

    result = align(primary, secondary, initial_offset_seconds=0)

    assert result.signal == "cadence"
    assert result.score < 0.3


def test_returns_none_signal_and_zero_score_when_theres_no_data_at_all():
    result = align([], [], initial_offset_seconds=30)

    assert result.signal is None
    assert result.score == 0.0
    assert result.overlap_seconds == 0
    assert result.offset_seconds == 30


def test_insufficient_overlap_falls_back_to_the_initial_offset():
    # Only 10 s of overlap at any candidate offset - below MIN_OVERLAP_SAMPLES (30), so no
    # candidate is scorable at all.
    primary = _records(10, cadence_fn=_profile)
    secondary = _shifted_records(10, 0, cadence_fn=_profile)

    result = align(primary, secondary, initial_offset_seconds=0)

    assert result.signal is None
    assert result.score == 0.0
    assert result.offset_seconds == 0
