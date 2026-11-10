import json
import math
from datetime import date, timedelta
from pathlib import Path

from django.test import SimpleTestCase

from .fixtures.decay.scenarios import build_scenarios
from .threshold_decay import DecayCandidate, DecaySettings, ManualEntry, simulate

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "decay"
JAVA_FIXTURES = (
    Path(__file__).resolve().parents[2] / "backend_java" / "src" / "test" / "resources" / "threshold-decay-fixtures"
)

DEFAULT_SETTINGS = DecaySettings(window_days=112, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=True)


def _parse_date(value: str | None) -> date | None:
    return date.fromisoformat(value) if value else None


class GoldenFixtureTests(SimpleTestCase):
    """expected.json is what backend_java's ThresholdDecayParityTest also checks against - a
    failure here after a deliberate change means regenerate.py, and the same change in
    ThresholdDecay.java."""

    def test_every_case_matches_the_golden_output(self):
        cases = json.loads((FIXTURES / "expected.json").read_text())["cases"]
        self.assertGreaterEqual(len(cases), 10)
        scenarios_by_name = {s["name"]: s for s in build_scenarios()}
        for case in cases:
            with self.subTest(name=case["name"]):
                scenario = scenarios_by_name[case["name"]]
                steps = simulate(
                    scenario["field"],
                    scenario["settings"],
                    scenario["seed_anchor_value"],
                    scenario["seed_anchor_date"],
                    scenario["seed_anchor_kind"],
                    scenario["candidates"],
                    scenario["manual_entries"],
                    scenario["load_by_day"],
                    scenario["start"],
                    scenario["end"],
                )
                actual = [
                    {
                        "value": s.value,
                        "kind": s.kind,
                        "activity_id": s.activity_id,
                        "effective_from": s.effective_from.isoformat(),
                        "current_from": s.current_from.isoformat(),
                    }
                    for s in steps
                ]
                self.assertEqual(actual, case["steps"])

    def test_java_copy_of_the_fixture_is_identical(self):
        if not JAVA_FIXTURES.exists():
            self.skipTest("backend_java not checked out alongside backend")
        self.assertEqual((JAVA_FIXTURES / "expected.json").read_bytes(), (FIXTURES / "expected.json").read_bytes())


class DecayCurveTests(SimpleTestCase):
    """Plan section 9.1's hand-written unit tests, trimmed to this slice's inputs (no histogram/
    load-filter/no-circularity cases - those need the real intensity histogram, deferred to the
    next #485 slice)."""

    def _flat_load(self, anchor: date, ratio: float, through: date) -> dict[date, float]:
        return {
            anchor + timedelta(days=i): (100.0 if i == 0 else 100.0 * ratio)
            for i in range(-5, (through - anchor).days + 5)
        }

    def _persistent_low_successor(self, anchor: date, value: float, through: date) -> list[DecayCandidate]:
        """A successor that never ages out of the trailing window, so `target` stays pinned to
        `floor` (the ratio/amplitude math) rather than falling through to the no-successor
        `A*(1-max_pct)` branch - see this slice's own fixtures/decay/scenarios.py for why a single
        fixed-date candidate isn't enough."""
        out = []
        d = anchor + timedelta(days=10)
        i = 0
        while d <= through:
            out.append(DecayCandidate(date=d, implied_value=value, activity_id=f"succ_{i}", accepted=True))
            d += timedelta(days=10)
            i += 1
        return out

    def test_decay_curve_matches_closed_form_at_t_14_28_56(self):
        settings = DecaySettings(window_days=112, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=True)
        anchor = date(2026, 1, 1)
        expiry = anchor + timedelta(days=settings.window_days + 1)
        end = expiry + timedelta(days=60)
        load = self._flat_load(anchor, 1.0, end)
        candidates = self._persistent_low_successor(anchor, 200.0, end)
        steps = simulate("ftp", settings, 260.0, anchor, "measured", candidates, [], load, anchor, end)

        target = 260.0 * (1 - settings.min_pct / 100)
        decay_factor = math.exp(-1 / settings.tau_days)

        def value_on(d: date) -> float:
            value = 260.0
            for step in steps:
                if step.current_from <= d:
                    value = step.value
                else:
                    break
            return value

        for t in (14, 28, 56):
            closed_form = target + (260.0 - target) * decay_factor**t
            with self.subTest(t=t):
                self.assertAlmostEqual(
                    value_on(expiry - timedelta(days=1) + timedelta(days=t)), round(closed_form), delta=1
                )
        # Emissions only happen on a rounded-value change.
        values = [s.value for s in steps]
        self.assertEqual(values, sorted(set(values), reverse=True))

    def test_moving_target_continuity(self):
        """The 1.3 case: day-over-day change stays bounded even as the successor's own window
        membership comes and goes, moving the target - no cliff when it expires."""
        settings = DecaySettings(window_days=112, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=True)
        anchor = date(2026, 1, 1)
        successor_date = anchor + timedelta(days=settings.window_days + 1 + 10)
        end = successor_date + timedelta(days=settings.window_days + 30)
        load = self._flat_load(anchor, 0.0, end)
        candidates = [DecayCandidate(date=successor_date, implied_value=240.0, activity_id="succ", accepted=True)]
        steps = simulate("ftp", settings, 260.0, anchor, "measured", candidates, [], load, anchor, end)

        max_step = (260.0 - 260.0 * (1 - settings.max_pct / 100)) * (1 - math.exp(-1 / settings.tau_days))
        for prev, curr in zip(steps, steps[1:], strict=False):
            with self.subTest(day=curr.current_from):
                self.assertLessEqual(abs(prev.value - curr.value), max_step + 1)

    def test_upward_reset_requires_at_least_the_current_rounded_value(self):
        settings = DecaySettings(window_days=112, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=True)
        anchor = date(2026, 1, 1)
        mid = anchor + timedelta(days=settings.window_days + 1 + 50)
        load = self._flat_load(anchor, 0.0, mid + timedelta(days=5))
        current = simulate("ftp", settings, 260.0, anchor, "measured", [], [], load, anchor, mid)[-1].value

        fails = DecayCandidate(date=mid + timedelta(days=1), implied_value=current - 1, activity_id="fail")
        passes = DecayCandidate(date=mid + timedelta(days=1), implied_value=current, activity_id="pass")
        end = mid + timedelta(days=1)

        fail_steps = simulate("ftp", settings, 260.0, anchor, "measured", [fails], [], load, anchor, end)
        pass_steps = simulate("ftp", settings, 260.0, anchor, "measured", [passes], [], load, anchor, end)

        self.assertEqual(fail_steps[-1].kind, "decayed")
        self.assertEqual(pass_steps[-1].kind, "measured")
        self.assertEqual(pass_steps[-1].activity_id, "pass")

    def test_manual_entry_resets_even_when_lower(self):
        settings = DecaySettings(window_days=112, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=True)
        anchor = date(2026, 1, 1)
        mid = anchor + timedelta(days=settings.window_days + 1 + 50)
        load = self._flat_load(anchor, 0.0, mid + timedelta(days=5))
        current = simulate("ftp", settings, 260.0, anchor, "measured", [], [], load, anchor, mid)[-1].value
        self.assertLess(current, 260.0)

        manual = ManualEntry(date=mid + timedelta(days=1), value=current - 10)
        steps = simulate(
            "ftp", settings, 260.0, anchor, "measured", [], [manual], load, anchor, mid + timedelta(days=1)
        )
        self.assertEqual(steps[-1].kind, "manual")
        self.assertEqual(steps[-1].value, current - 10)

    def test_never_eases_upward_when_load_recovers(self):
        settings = DecaySettings(window_days=112, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=True)
        anchor = date(2026, 1, 1)
        expiry = anchor + timedelta(days=settings.window_days + 1)
        end = expiry + timedelta(days=60)
        load = self._flat_load(anchor, 0.0, end)
        baseline_steps = simulate("ftp", settings, 260.0, anchor, "measured", [], [], load, anchor, end)
        values = [s.value for s in baseline_steps]
        # Never increases once decay has started.
        self.assertEqual(values, sorted(values, reverse=True))

        # Load fully recovers (ratio back to 1) a week before the end - floor rises above v, but v
        # must not follow it upward.
        recovered = dict(load)
        for i in range(53, 61):
            recovered[anchor + timedelta(days=i)] = 100.0
        recovered_steps = simulate("ftp", settings, 260.0, anchor, "measured", [], [], recovered, anchor, end)
        self.assertLessEqual(recovered_steps[-1].value, baseline_steps[-1].value + 0.0001)

    def test_no_successor_target_is_anchor_times_one_minus_max_pct(self):
        settings = DecaySettings(window_days=112, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=True)
        anchor = date(2026, 1, 1)
        expiry = anchor + timedelta(days=settings.window_days + 1)
        end = expiry + timedelta(days=400)
        load = self._flat_load(anchor, 0.0, end)
        steps = simulate("ftp", settings, 260.0, anchor, "measured", [], [], load, anchor, end)
        self.assertEqual(steps[-1].value, round(260.0 * (1 - settings.max_pct / 100)))

    def test_load_ratio_clamp_and_amplitude(self):
        settings = DecaySettings(window_days=112, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=True)
        anchor = date(2026, 1, 1)
        expiry = anchor + timedelta(days=settings.window_days + 1)
        end = expiry + timedelta(days=400)
        # A persistent low successor pins `target` to the ratio/amplitude-driven `floor` in every
        # case below, rather than letting it fall through to the no-successor `A*(1-max_pct)`
        # branch (which doesn't depend on ratio at all).
        candidates = self._persistent_low_successor(anchor, 200.0, end)

        # ratio > 1 clamps to 1 -> exactly min_pct (260 W -> 252.2, not 260).
        load_over = self._flat_load(anchor, 2.0, end)
        steps = simulate("ftp", settings, 260.0, anchor, "measured", candidates, [], load_over, anchor, end)
        self.assertEqual(steps[-1].value, round(260.0 * (1 - settings.min_pct / 100)))

        # ratio 0 -> max_pct.
        load_zero = self._flat_load(anchor, 0.0, end)
        steps = simulate("ftp", settings, 260.0, anchor, "measured", candidates, [], load_zero, anchor, end)
        self.assertEqual(steps[-1].value, round(260.0 * (1 - settings.max_pct / 100)))

        # L(a) < 1 -> ratio treated as 1 (min_pct), even though L(d) alone would suggest otherwise.
        load_tiny_anchor = dict(load_zero)
        load_tiny_anchor[anchor] = 0.5
        steps = simulate("ftp", settings, 260.0, anchor, "measured", candidates, [], load_tiny_anchor, anchor, end)
        self.assertEqual(steps[-1].value, round(260.0 * (1 - settings.min_pct / 100)))

        # min_pct = 0 reproduces "no decay with maintained load".
        no_min_settings = DecaySettings(
            window_days=112, sanity_pct=50, tau_days=25, min_pct=0, max_pct=12, enabled=True
        )
        load_maintained = self._flat_load(anchor, 1.0, end)
        steps = simulate(
            "ftp", no_min_settings, 260.0, anchor, "measured", candidates, [], load_maintained, anchor, end
        )
        self.assertEqual(steps, [])

    def test_minimum_drift_with_maintained_load(self):
        settings = DecaySettings(window_days=112, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=True)
        anchor = date(2026, 1, 1)
        expiry = anchor + timedelta(days=settings.window_days + 1)
        end = expiry + timedelta(days=60)
        load = self._flat_load(anchor, 1.0, end)
        candidates = self._persistent_low_successor(anchor, 200.0, end)
        steps = simulate("ftp", settings, 260.0, anchor, "measured", candidates, [], load, anchor, end)

        def value_on(d: date) -> float:
            value = 260.0
            for s in steps:
                if s.current_from <= d:
                    value = s.value
                else:
                    break
            return value

        base = expiry - timedelta(days=1)
        self.assertEqual(value_on(base + timedelta(days=14)), 257.0)
        self.assertEqual(value_on(base + timedelta(days=28)), 255.0)
        self.assertEqual(value_on(base + timedelta(days=56)), 253.0)

    def test_pace_decays_slower_and_floors_correctly(self):
        settings = DecaySettings(window_days=112, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=True)
        anchor = date(2026, 1, 1)
        expiry = anchor + timedelta(days=settings.window_days + 1)
        end = expiry + timedelta(days=400)
        load = self._flat_load(anchor, 0.0, end)
        steps = simulate("threshold_pace", settings, 240.0, anchor, "measured", [], [], load, anchor, end)
        values = [s.value for s in steps]
        self.assertEqual(values, sorted(values))  # non-decreasing in seconds
        self.assertEqual(steps[-1].value, 273.0)  # 240 / 0.88 = 272.7 -> 273

    def test_disabled_mode_reproduces_successor_takeover_shape(self):
        settings = DecaySettings(window_days=30, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=False)
        anchor = date(2026, 1, 1)
        later = DecayCandidate(date=anchor + timedelta(days=5), implied_value=240.0, activity_id="act_b")
        end = anchor + timedelta(days=settings.window_days + 5)
        steps = simulate("ftp", settings, 260.0, anchor, "measured", [later], [], {}, anchor, end)
        self.assertEqual(len(steps), 1)
        self.assertEqual(steps[0].kind, "measured")
        self.assertEqual(steps[0].value, 240.0)
        self.assertEqual(steps[0].activity_id, "act_b")
        self.assertEqual(steps[0].effective_from, later.date)

    def test_manual_anchor_before_expiry_not_replaced_by_lower_later_candidate(self):
        """The 1.1 regression, re-proven at the pure-function level."""
        settings = DecaySettings(window_days=112, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=True)
        manual_date = date(2026, 1, 1)
        manual = ManualEntry(date=manual_date, value=250.0)
        lower = DecayCandidate(date=manual_date + timedelta(days=5), implied_value=230.0, activity_id="low")
        end = manual_date + timedelta(days=30)
        steps = simulate("ftp", settings, None, None, None, [lower], [manual], {}, manual_date, end)
        self.assertEqual(len(steps), 1)
        self.assertEqual(steps[0].kind, "manual")
        self.assertEqual(steps[0].value, 250.0)

    def test_cold_start_with_no_seed_anchor(self):
        settings = DecaySettings(window_days=112, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=True)
        start = date(2026, 1, 1)
        candidate = DecayCandidate(date=start + timedelta(days=3), implied_value=200.0, activity_id="first")
        steps = simulate("ftp", settings, None, None, None, [candidate], [], {}, start, start + timedelta(days=10))
        self.assertEqual(len(steps), 1)
        self.assertEqual(steps[0].kind, "measured")

    def test_seed_v_value_resumes_from_an_already_decayed_value_not_the_anchor(self):
        """The slice-3 extension: resuming a suffix well after the anchor's own date (e.g. the
        daily job, resuming from "the day after the latest row's current_from") must continue
        decaying from that row's own value, not jump back up to the anchor."""
        settings = DecaySettings(window_days=112, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=True)
        anchor_date = date(2026, 1, 1)
        expiry = anchor_date + timedelta(days=settings.window_days + 1)
        # First, run the whole thing in one pass to find out what v would be at some later day.
        full_end = expiry + timedelta(days=30)
        load = {anchor_date + timedelta(days=i): 0.0 for i in range(-5, 400)}
        load[anchor_date] = 100.0
        full = simulate("ftp", settings, 260.0, anchor_date, "measured", [], [], load, anchor_date, full_end)
        resume_from = full[-1].current_from
        already_decayed_value = full[-1].value
        self.assertLess(already_decayed_value, 260.0)

        # Resuming from the day after, seeded with that already-decayed value (not 260), must
        # continue the SAME trajectory a continuous one-pass run would have taken.
        resume_start = resume_from + timedelta(days=1)
        resume_end = full_end + timedelta(days=20)
        resumed = simulate(
            "ftp",
            settings,
            260.0,
            anchor_date,
            "measured",
            [],
            [],
            load,
            resume_start,
            resume_end,
            seed_v_value=already_decayed_value,
        )
        one_pass = simulate("ftp", settings, 260.0, anchor_date, "measured", [], [], load, anchor_date, resume_end)

        def value_on(steps, d, initial):
            value = initial
            for s in steps:
                if s.current_from <= d:
                    value = s.value
                else:
                    break
            return value

        self.assertEqual(value_on(resumed, resume_end, already_decayed_value), value_on(one_pass, resume_end, 260.0))

        # Without the seed_v_value fix, resuming would incorrectly restart from the anchor (260).
        wrong_resume = simulate("ftp", settings, 260.0, anchor_date, "measured", [], [], load, resume_start, resume_end)
        self.assertNotEqual(wrong_resume[0].value, resumed[0].value)

    def test_already_recorded_manual_entry_suppresses_without_duplicating_a_step(self):
        """The slice-3 extension: a manual entry whose ledger row already exists (because it's
        outside this particular resimulation's own trigger) must still protect against a lower
        later candidate (1.1), but must not produce a second Step for itself."""
        settings = DecaySettings(window_days=112, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=True)
        manual_date = date(2026, 1, 1)
        manual = ManualEntry(date=manual_date, value=250.0, already_recorded=True)
        lower = DecayCandidate(date=manual_date + timedelta(days=5), implied_value=230.0, activity_id="low")
        end = manual_date + timedelta(days=30)
        steps = simulate("ftp", settings, None, None, None, [lower], [manual], {}, manual_date, end)
        # No Step at all - the manual entry isn't re-emitted, and the lower candidate correctly
        # fails the upward-reset test against it, so nothing changes.
        self.assertEqual(steps, [])
