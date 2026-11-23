"""Issue #540 (phase 6): activities.implausible_jump_detection is pure computation (no DB), so
these are plain functions, not a TestCase - conftest.py's auto-tagging puts them in the `unit`
bucket. Mirrors backend_java's ImplausibleJumpDetectorTest."""

import pytest

from ..implausible_jump_detection import ImplausibleRun, detect_implausible_jumps


def _constant(n: int, value: int, start: int = 0) -> dict[int, int]:
    return {start + i: value for i in range(n)}


def _spike(baseline: int, before: int, spike_value: int, spike_len: int, after: int) -> dict[int, int]:
    """before seconds of baseline, then spike_len seconds at spike_value, then after seconds back
    at baseline - seconds 0..(before + spike_len + after - 1)."""
    series: dict[int, int] = {}
    series.update(_constant(before, baseline, start=0))
    series.update(_constant(spike_len, spike_value, start=before))
    series.update(_constant(after, baseline, start=before + spike_len))
    return series


def test_a_short_heartrate_spike_with_a_smooth_plausible_reference_is_confirmed():
    primary = _spike(baseline=80, before=4, spike_value=180, spike_len=2, after=4)
    reference = _constant(10, 150)  # chest strap stays smooth and plausible throughout

    confirmed_ts, runs = detect_implausible_jumps(primary, "heartrate", reference)

    assert confirmed_ts == {4, 5}
    assert runs == [ImplausibleRun(start_t=4, length=2, peak_delta=100.0)]


def test_cadence_spike_also_confirms_symmetry_only():
    primary = _spike(baseline=85, before=4, spike_value=180, spike_len=2, after=4)
    reference = _constant(10, 86)

    confirmed_ts, runs = detect_implausible_jumps(primary, "cadence", reference)

    assert confirmed_ts == {4, 5}
    assert runs == [ImplausibleRun(start_t=4, length=2, peak_delta=95.0)]


def test_no_reference_confirms_nothing():
    primary = _spike(baseline=80, before=4, spike_value=180, spike_len=2, after=4)

    confirmed_ts, runs = detect_implausible_jumps(primary, "heartrate", reference=None)

    assert confirmed_ts == set()
    assert runs == []


def test_a_reference_that_also_jumps_is_not_confirmed():
    """Both streams spike together - no corroboration of a one-sided glitch, so unconfirmed, same
    "mutual" reasoning dropout_detection applies to mutual near-zero."""
    primary = _spike(baseline=80, before=4, spike_value=180, spike_len=2, after=4)
    reference = _spike(baseline=150, before=4, spike_value=250, spike_len=2, after=4)

    confirmed_ts, runs = detect_implausible_jumps(primary, "heartrate", reference)

    assert confirmed_ts == set()
    assert runs == []


def test_a_reference_missing_data_in_the_run_does_not_confirm():
    primary = _spike(baseline=80, before=4, spike_value=180, spike_len=2, after=4)
    reference = _constant(10, 150)
    del reference[5]

    confirmed_ts, runs = detect_implausible_jumps(primary, "heartrate", reference)

    assert confirmed_ts == set()
    assert runs == []


def test_a_run_with_no_closing_boundary_is_not_reported():
    """The spike never jumps back before the data ends - no exit bracket, same "no trailing
    bracket, no confirmation" stance dropout_detection's single-stream fallback takes."""
    primary = {**_constant(4, 80), 4: 180, 5: 180}
    reference = _constant(6, 150)

    confirmed_ts, runs = detect_implausible_jumps(primary, "heartrate", reference)

    assert confirmed_ts == set()
    assert runs == []


def test_a_long_heartrate_freeze_held_above_max_plausible_hr_is_confirmed_despite_the_length_cap():
    """The canonical TICKR failure: a long freeze at an implausible level must not be dismissed as
    "unusual physiology" the way a long run normally would be."""
    primary = _spike(baseline=80, before=4, spike_value=230, spike_len=12, after=4)
    reference = _constant(20, 150)  # the athlete's real HR stays smooth and plausible throughout

    confirmed_ts, runs = detect_implausible_jumps(primary, "heartrate", reference)

    assert confirmed_ts == set(range(4, 16))
    assert runs == [ImplausibleRun(start_t=4, length=12, peak_delta=150.0)]


def test_the_same_long_run_at_a_plausible_held_level_is_not_confirmed():
    """Same shape and length as the freeze test above, but the held level (180 bpm) is itself
    plausible - a long run at a plausible level is unusual physiology, not a sensor glitch, same
    reasoning dropout_detection applies to its own longer-zero-run rejection."""
    primary = _spike(baseline=80, before=4, spike_value=180, spike_len=12, after=4)
    reference = _constant(20, 150)

    confirmed_ts, runs = detect_implausible_jumps(primary, "heartrate", reference)

    assert confirmed_ts == set()
    assert runs == []


def test_a_long_cadence_run_gets_no_freeze_exemption():
    """The _MAX_PLAUSIBLE_HR exemption is heartrate-only - cadence keeps the plain length cap
    regardless of how high the held level is."""
    primary = _spike(baseline=85, before=4, spike_value=230, spike_len=12, after=4)
    reference = _constant(20, 86)

    confirmed_ts, runs = detect_implausible_jumps(primary, "cadence", reference)

    assert confirmed_ts == set()
    assert runs == []


def test_an_unsupported_field_raises():
    with pytest.raises(ValueError):
        detect_implausible_jumps({0: 0, 1: 300}, "power", reference=None)
