"""Integration tests for the decay-enabled wiring (issue #485, slice 3) - ingest, manual edit,
refresh, and rebuild all going through threshold_decay_recompute.resimulate_suffix instead of
the cliff-based current_window_value/_recompute_and_record/replay_full_history_into path, for a
decay-enabled athlete. The pure simulate() mechanics themselves are already covered by
test_threshold_decay.py - this file is about the DB-backed plumbing around it: seeding from the
real ledger, the delete-then-regenerate, set_by attribution, and that a decay-disabled athlete
(the default) is completely unaffected.
"""

from datetime import UTC, date, datetime, timedelta

from django.test import TestCase

from accounts.models import User
from activities.models import Activity, Record

from .models import ThresholdHistory
from .tasks import apply_threshold_decay
from .threshold_decay import ManualEntry as DecayManualEntry
from .threshold_decay_recompute import gather_preview_steps, resimulate_suffix
from .threshold_history import recompute_for_activity, record_manual_value, refresh_field


def _decay_athlete(**overrides):
    defaults = {
        "email": "decay-athlete@example.cc",
        "password": "x",
        "name": "Decay Athlete",
        "threshold_decay_enabled": True,
        "threshold_window_days": 10,
        "threshold_sanity_pct": 50,
        "threshold_decay_tau_days": 5,
        "threshold_decay_min_pct": 3,
        "threshold_decay_max_pct": 12,
    }
    defaults.update(overrides)
    return User.objects.create_user(**defaults)


def _make_power_activity(athlete, start_date, power, duration_seconds=1200):
    activity = Activity.objects.create(
        athlete=athlete, sport="bike", name="Ride", start_date=start_date, moving_time=duration_seconds
    )
    for t in range(duration_seconds):
        Record.objects.create(activity=activity, t=t, ts=start_date + timedelta(seconds=t), power=power)
    return activity


class IngestWiringTests(TestCase):
    def test_recompute_for_activity_creates_a_measured_anchor_for_a_decay_enabled_athlete(self):
        # recompute_for_activity resimulates the whole suffix through the real today (design doc
        # 4.2) - an activity dated this far in the past (relative to window_days=10) will have
        # long since decayed away from its own anchor value by the time the real clock catches
        # up, so this only checks the FIRST (measured, anchor) row, not the athlete's live value.
        athlete = _decay_athlete()
        activity = _make_power_activity(athlete, datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=260)

        recompute_for_activity(activity)

        entry = ThresholdHistory.objects.filter(athlete=athlete, field="ftp").order_by("effective_from").first()
        self.assertEqual(entry.kind, "measured")
        self.assertEqual(entry.value_numeric, round(0.95 * 260))
        self.assertEqual(entry.source_activity_id, activity.id)
        self.assertEqual(entry.effective_from, date(2026, 1, 1))

    def test_decay_disabled_athlete_is_unaffected_by_the_new_wiring(self):
        """The default - confirms the branch in recompute_for_activity actually branches."""
        athlete = User.objects.create_user(email="old-path@example.cc", password="x", name="Old Path")
        self.assertFalse(athlete.threshold_decay_enabled)
        activity = _make_power_activity(athlete, datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=260)

        recompute_for_activity(activity)

        entry = ThresholdHistory.objects.get(athlete=athlete, field="ftp")
        self.assertEqual(entry.kind, "measured")
        self.assertEqual(entry.effective_from, entry.current_from)


class ResimulateSuffixIntegrationTests(TestCase):
    """Exercises resimulate_suffix directly over a multi-day range against real DB state - what
    the (not-yet-built) daily job will eventually do, and what distinguishes this from the pure
    simulate() unit tests, which never touch the database."""

    def test_decayed_rows_accumulate_over_a_multi_day_resimulation(self):
        athlete = _decay_athlete()
        anchor_date = date(2026, 1, 1)
        _make_power_activity(athlete, datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=260)
        recompute_for_activity(Activity.objects.get(athlete=athlete))

        expiry = anchor_date + timedelta(days=athlete.threshold_window_days + 1)
        end = expiry + timedelta(days=30)
        result = resimulate_suffix(athlete, "ftp", expiry, end=end)

        self.assertTrue(result.changed)
        decayed = ThresholdHistory.objects.filter(athlete=athlete, field="ftp", kind="decayed").order_by(
            "effective_from"
        )
        self.assertGreater(decayed.count(), 0)
        values = [e.value_numeric for e in decayed]
        self.assertEqual(values, sorted(values, reverse=True))  # strictly easing down
        self.assertLess(values[-1], 260)

        athlete.refresh_from_db()
        self.assertEqual(athlete.ftp, values[-1])

    def test_rerates_activities_after_a_retroactive_change(self):
        """Design doc 4.6: stored TSS/IF must not drift from the ledger after a decay step."""
        athlete = _decay_athlete()
        anchor_date = date(2026, 1, 1)
        activity = _make_power_activity(athlete, datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=260)
        recompute_for_activity(activity)

        expiry = anchor_date + timedelta(days=athlete.threshold_window_days + 1)
        later_activity = _make_power_activity(
            athlete,
            datetime(2026, 1, 1, 7, 0, tzinfo=UTC) + timedelta(days=athlete.threshold_window_days + 20),
            power=185,
        )
        old_tss = later_activity.tss

        resimulate_suffix(athlete, "ftp", expiry, end=expiry + timedelta(days=25))

        later_activity.refresh_from_db()
        self.assertNotEqual(later_activity.tss, old_tss)

    def test_qualification_diff_detects_a_ride_that_would_now_qualify(self):
        # Issue #494's decay-on half: a manual anchor correction can make a later ride, rejected
        # under the old anchor, newly plausible - collect_qualification_diff must surface that as
        # a would_requalify entry even though nothing is persisted differently about the ride
        # itself (it simply has no measured row yet, before or after the correction decides it).
        athlete = _decay_athlete(threshold_window_days=10, threshold_sanity_pct=20)
        _make_power_activity(athlete, datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=400)  # implies 380
        recompute_for_activity(Activity.objects.get(athlete=athlete))
        probe = _make_power_activity(athlete, datetime(2026, 1, 8, 7, 0, tzinfo=UTC), power=632)  # implies 600

        # Without the correction, 600 is 57.9% off the 380 anchor - well outside the 20% band.
        baseline = resimulate_suffix(athlete, "ftp", date(2026, 1, 5), end=date(2026, 1, 10))
        self.assertFalse(ThresholdHistory.objects.filter(athlete=athlete, field="ftp", source_activity=probe).exists())

        result = resimulate_suffix(
            athlete,
            "ftp",
            date(2026, 1, 5),
            end=date(2026, 1, 10),
            new_manual_entry=DecayManualEntry(date=date(2026, 1, 5), value=580, already_recorded=False),
            collect_qualification_diff=True,
        )

        self.assertEqual(baseline.rerated_count, 0)  # sanity-check the "before" state above is real
        self.assertEqual(len(result.would_requalify), 1)
        diff = result.would_requalify[0]
        self.assertIsNone(diff.old_activity_id)
        self.assertEqual(diff.new_activity_id, probe.id)
        self.assertIsNone(diff.old_value)
        self.assertEqual(diff.new_value, 600)
        self.assertEqual(diff.current_from, date(2026, 1, 8))
        probe_row = ThresholdHistory.objects.get(athlete=athlete, field="ftp", source_activity=probe)
        self.assertEqual(probe_row.value_numeric, 600)  # the diff matches what was actually persisted

    def test_qualification_diff_is_empty_when_nothing_would_requalify(self):
        athlete = _decay_athlete(threshold_window_days=10, threshold_sanity_pct=20)
        _make_power_activity(athlete, datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=260)
        recompute_for_activity(Activity.objects.get(athlete=athlete))

        result = resimulate_suffix(
            athlete,
            "ftp",
            date(2026, 1, 5),
            end=date(2026, 1, 10),
            new_manual_entry=DecayManualEntry(date=date(2026, 1, 5), value=247, already_recorded=False),
            collect_qualification_diff=True,
        )

        self.assertEqual(result.would_requalify, [])


class RefreshFieldWiringTests(TestCase):
    def test_refresh_is_append_only_and_emits_a_decayed_row_once_past_expiry(self):
        # refresh_field always resimulates through the real date.today() internally, with no way
        # to inject a fixed "today" - so this brings the ledger up to a known state as of
        # yesterday (relative to the real clock) via a directly-controlled resimulate_suffix
        # call first, then calls the real refresh_field and confirms it only ever appends.
        # Just a few days past expiry (not many) - with tau_days=5, decay flattens out close to
        # its floor quickly, and this test needs refresh's one extra day to still move the
        # rounded value, not land after it's already fully settled.
        athlete = _decay_athlete()
        anchor_date = date.today() - timedelta(days=athlete.threshold_window_days + 3)
        _make_power_activity(athlete, datetime.combine(anchor_date, datetime.min.time(), tzinfo=UTC), power=260)
        yesterday = date.today() - timedelta(days=1)
        resimulate_suffix(athlete, "ftp", anchor_date, end=yesterday)
        rows_before = set(ThresholdHistory.objects.filter(athlete=athlete, field="ftp").values_list("pk", flat=True))
        self.assertGreater(len(rows_before), 1)  # already decaying past expiry

        changed = refresh_field(athlete, "ftp")

        self.assertTrue(changed)
        rows_after = ThresholdHistory.objects.filter(athlete=athlete, field="ftp")
        new_rows = rows_after.exclude(pk__in=rows_before)
        self.assertEqual(new_rows.count(), 1)
        self.assertEqual(new_rows.get().current_from, date.today())
        self.assertEqual(new_rows.get().kind, "decayed")
        # Append-only: every pre-existing row survives untouched.
        self.assertEqual(rows_after.filter(pk__in=rows_before).count(), len(rows_before))


class RecordManualValueWiringTests(TestCase):
    def test_manual_edit_creates_its_own_row_with_set_by_via_resimulation(self):
        athlete = _decay_athlete()
        coach = User.objects.create_user(email="coach@example.cc", password="x", name="Coach", is_coach=True)

        result = record_manual_value(athlete, "ftp", 255, set_by=coach)

        self.assertTrue(result.changed)
        self.assertTrue(result.became_current)
        self.assertFalse(result.is_correction_only)
        entry = ThresholdHistory.objects.get(athlete=athlete, field="ftp")
        self.assertEqual(entry.kind, "manual")
        self.assertEqual(entry.value_numeric, 255)
        self.assertEqual(entry.set_by_id, coach.id)
        athlete.refresh_from_db()
        self.assertEqual(athlete.ftp, 255)

    def test_correction_only_edit_still_uses_the_direct_write_path(self):
        """A backdated edit that never becomes current doesn't need a suffix resimulation - it
        stays on today's direct-write path regardless of threshold_decay_enabled."""
        athlete = _decay_athlete()
        later_date = date(2026, 6, 1)
        record_manual_value(athlete, "ftp", 250, effective_from=later_date, as_of=later_date)

        backdated_result = record_manual_value(
            athlete, "ftp", 230, effective_from=later_date - timedelta(days=5), as_of=later_date
        )

        self.assertTrue(backdated_result.changed)
        self.assertTrue(backdated_result.is_correction_only)
        self.assertFalse(backdated_result.became_current)
        correction_row = ThresholdHistory.objects.get(athlete=athlete, field="ftp", value_numeric=230)
        self.assertTrue(correction_row.is_correction_only)
        # The live value is still the later, non-correction edit - untouched by the backdated one.
        athlete.refresh_from_db()
        self.assertEqual(athlete.ftp, 250)

    def _under_read_anchor_and_probe(self):
        """Issue #605 setup: a 300 W anchor (an under-reading sensor) and a 390 W ride four days
        later that's 30% off it - rejected by the 20% band, so the ledger as of `as_of` has no
        row for it."""
        athlete = _decay_athlete(threshold_window_days=10, threshold_sanity_pct=20)
        _make_power_activity(athlete, datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=316)  # implies 300
        probe = _make_power_activity(athlete, datetime(2026, 1, 5, 7, 0, tzinfo=UTC), power=411)  # implies 390
        as_of = date(2026, 1, 20)
        resimulate_suffix(athlete, "ftp", date(2026, 1, 1), end=as_of)
        self.assertFalse(ThresholdHistory.objects.filter(athlete=athlete, field="ftp", source_activity=probe).exists())
        return athlete, probe, as_of

    def test_correction_only_preview_reports_a_ride_that_would_requalify(self):
        """Issue #605: before, would_requalify was always empty for this combination."""
        athlete, probe, as_of = self._under_read_anchor_and_probe()
        rows_before = list(ThresholdHistory.objects.filter(athlete=athlete, field="ftp").values_list("pk", flat=True))

        # Jan 2 is 18 days before as_of, past the 10-day window - stale on arrival, so correction-only.
        result = record_manual_value(athlete, "ftp", 400, effective_from=date(2026, 1, 2), as_of=as_of, preview=True)

        self.assertTrue(result.is_correction_only)
        self.assertEqual(len(result.would_requalify), 1)
        diff = result.would_requalify[0]
        self.assertIsNone(diff.old_activity_id)
        self.assertEqual(diff.new_activity_id, probe.id)
        self.assertEqual(diff.new_value, 390)
        self.assertEqual(diff.current_from, date(2026, 1, 5))
        # Preview: nothing persisted, not even the correction row itself.
        self.assertEqual(
            list(ThresholdHistory.objects.filter(athlete=athlete, field="ftp").values_list("pk", flat=True)),
            rows_before,
        )

    def test_correction_only_preview_is_empty_when_nothing_would_requalify(self):
        athlete, _probe, as_of = self._under_read_anchor_and_probe()

        # 310 is barely a correction - the probe is still 25.8% off it.
        result = record_manual_value(athlete, "ftp", 310, effective_from=date(2026, 1, 2), as_of=as_of, preview=True)

        self.assertTrue(result.is_correction_only)
        self.assertEqual(result.would_requalify, [])

    def test_saving_a_correction_applies_it_from_its_own_date(self):
        """Issue #605 review: a real correction-only save resimulates from the correction's own
        date straight away - no later ingest/refresh needed, so nothing is left half-applied.
        The preview's prediction holds, and the correction itself never becomes a ledger value."""
        athlete, probe, as_of = self._under_read_anchor_and_probe()

        record_manual_value(athlete, "ftp", 400, effective_from=date(2026, 1, 2), as_of=as_of)

        probe_row = ThresholdHistory.objects.get(athlete=athlete, field="ftp", source_activity=probe)
        self.assertEqual(probe_row.value_numeric, 390)
        self.assertEqual(probe_row.kind, "measured")
        self.assertFalse(
            ThresholdHistory.objects.filter(athlete=athlete, field="ftp", value_numeric=400)
            .exclude(is_correction_only=True)
            .exists()
        )

    def test_correction_only_preview_ignores_drift_it_did_not_cause(self):
        """Issue #605 review: a correction stored before #605 (never applied, so the ledger still
        lacks the probe's row) must not show up as a consequence of an unrelated new edit."""
        athlete, probe, as_of = self._under_read_anchor_and_probe()
        ThresholdHistory.objects.create(
            athlete=athlete,
            field="ftp",
            value_numeric=400,
            effective_from=date(2026, 1, 2),
            current_from=date(2026, 1, 2),
            kind="manual",
            is_correction_only=True,
        )

        # Dated before the old correction, so it changes nothing from Jan 2 on.
        result = record_manual_value(athlete, "ftp", 305, effective_from=date(2026, 1, 1), as_of=as_of, preview=True)

        self.assertTrue(result.is_correction_only)
        self.assertEqual(result.would_requalify, [])
        # Sanity-check the premise: the stored ledger really is stale relative to a rebuild.
        self.assertFalse(ThresholdHistory.objects.filter(athlete=athlete, field="ftp", source_activity=probe).exists())

    def test_preview_rolls_back_everything_including_the_resimulated_rows(self):
        athlete = _decay_athlete()
        before_count = ThresholdHistory.objects.filter(athlete=athlete, field="ftp").count()

        result = record_manual_value(athlete, "ftp", 240, preview=True)

        self.assertTrue(result.changed)
        self.assertEqual(ThresholdHistory.objects.filter(athlete=athlete, field="ftp").count(), before_count)
        athlete.refresh_from_db()
        self.assertIsNone(athlete.ftp)


class RebuildWiringTests(TestCase):
    def test_rebuild_keeps_manual_rows_and_regenerates_measured_and_decayed(self):
        athlete = _decay_athlete()
        manual_date = date(2026, 1, 1)
        # Lower than the activity's own implied value (round(0.95*260)=247) below, so the
        # activity legitimately beats it as an upward reset - otherwise the stronger manual
        # anchor would correctly never be replaced (the 1.1 regression protection), and no
        # "measured" row would ever appear, which isn't what this test is after.
        record_manual_value(athlete, "ftp", 200, effective_from=manual_date, as_of=manual_date)
        activity = _make_power_activity(athlete, datetime(2026, 1, 10, 7, 0, tzinfo=UTC), power=260)
        recompute_for_activity(activity)
        stray_decayed = ThresholdHistory.objects.create(
            athlete=athlete,
            field="ftp",
            value_numeric=240,
            effective_from=date(2026, 2, 1),
            current_from=date(2026, 2, 1),
            kind="decayed",
        )

        from .threshold_history import rebuild_history_stream

        list(rebuild_history_stream(athlete, "ftp"))

        self.assertTrue(ThresholdHistory.objects.filter(pk=ThresholdHistory.objects.get(value_numeric=200).pk).exists())
        self.assertFalse(ThresholdHistory.objects.filter(pk=stray_decayed.pk).exists())
        self.assertTrue(ThresholdHistory.objects.filter(athlete=athlete, field="ftp", kind="measured").exists())


class ApplyThresholdDecayJobTests(TestCase):
    """The daily companion to the four real-time triggers (issue #548) - only matters for an
    athlete who goes quiet on one sport but not another, since ingest/manual-edit/refresh/rebuild
    already self-correct a stale ledger whenever any of them next fires for that field."""

    def test_skips_an_athlete_whose_anchor_has_not_expired_yet(self):
        athlete = _decay_athlete(email="decay-job-fresh@example.cc")
        start = datetime.now(tz=UTC)
        _make_power_activity(athlete, start, power=260)
        recompute_for_activity(Activity.objects.get(athlete=athlete))

        apply_threshold_decay()

        self.assertFalse(ThresholdHistory.objects.filter(athlete=athlete, field="ftp", kind="decayed").exists())
        athlete.refresh_from_db()
        self.assertEqual(athlete.ftp, round(0.95 * 260))

    def test_applies_decay_when_the_anchor_has_expired_for_a_quiet_sport(self):
        athlete = _decay_athlete(email="decay-job-stale@example.cc")
        start = datetime(2026, 1, 1, 7, 0, tzinfo=UTC)
        _make_power_activity(athlete, start, power=260)
        # A genuinely stale ledger: the athlete's last bike activity was 2026-01-01, and at that
        # time the ingest trigger resimulated only through 2026-01-01 (today then) - not through
        # the real today. recompute_for_activity would resimulate through the real today and
        # pre-decay the anchor to its floor, making apply_threshold_decay a no-op; resimulating
        # only through the activity's own date reproduces the ledger state a quiet athlete has.
        resimulate_suffix(athlete, "ftp", date(2026, 1, 1), end=date(2026, 1, 1))

        apply_threshold_decay()

        decayed = ThresholdHistory.objects.filter(athlete=athlete, field="ftp", kind="decayed")
        self.assertTrue(decayed.exists())
        latest = decayed.order_by("-effective_from").first()
        self.assertEqual(latest.current_from, date.today())
        athlete.refresh_from_db()
        self.assertLess(athlete.ftp, 260)

    def test_skips_a_decay_disabled_athlete_even_with_an_expired_anchor(self):
        athlete = User.objects.create_user(email="decay-job-disabled@example.cc", password="x", name="Disabled")
        start = datetime(2026, 1, 1, 7, 0, tzinfo=UTC)
        _make_power_activity(athlete, start, power=260)
        recompute_for_activity(Activity.objects.get(athlete=athlete))

        apply_threshold_decay()

        self.assertFalse(ThresholdHistory.objects.filter(athlete=athlete, field="ftp", kind="decayed").exists())

    def test_skips_a_field_with_no_ledger_rows_at_all(self):
        athlete = _decay_athlete(email="decay-job-no-ledger@example.cc")

        apply_threshold_decay()  # must not raise for a field with no anchor at all

        self.assertFalse(ThresholdHistory.objects.filter(athlete=athlete).exists())


def _ledger_row(athlete, effective_from, value, *, is_correction_only=False):
    return ThresholdHistory.objects.create(
        athlete=athlete,
        field="ftp",
        value_numeric=value,
        effective_from=effective_from,
        current_from=effective_from,
        kind="manual",
        is_correction_only=is_correction_only,
    )


class SeedExcludesCorrectionsTests(TestCase):
    """Issue #607: a correction-only row is only ever a sanity-band reference (#605), so it must
    never seed a resimulation's anchor or starting value, nor hold off the daily job."""

    def test_resimulation_seeds_from_the_latest_non_correction_row(self):
        athlete = _decay_athlete(email="decay-607-seed@example.cc")
        day0 = date(2026, 1, 1)
        _ledger_row(athlete, day0, 300)
        _ledger_row(athlete, day0 + timedelta(days=2), 400, is_correction_only=True)

        # Past the 10-day window, so it decays - away from 300, never from the 400 correction.
        end = day0 + timedelta(days=15)
        resimulate_suffix(athlete, "ftp", end, end=end)

        decayed = list(ThresholdHistory.objects.filter(athlete=athlete, field="ftp", kind="decayed"))
        self.assertTrue(decayed)
        self.assertTrue(all(row.value_numeric < 300 for row in decayed))
        athlete.refresh_from_db()
        self.assertLess(athlete.ftp, 300)

    def test_a_recent_correction_does_not_hold_off_the_daily_job(self):
        athlete = _decay_athlete(email="decay-607-job@example.cc")
        today = date.today()
        _ledger_row(athlete, today - timedelta(days=30), 300)
        _ledger_row(athlete, today - timedelta(days=2), 400, is_correction_only=True)

        apply_threshold_decay()

        latest = (
            ThresholdHistory.objects.filter(athlete=athlete, field="ftp", kind="decayed")
            .order_by("-effective_from")
            .first()
        )
        self.assertIsNotNone(latest)
        self.assertEqual(latest.current_from, today)
        self.assertLess(latest.value_numeric, 300)


class GatherPreviewStepsTests(TestCase):
    """Issue #487: the read-only half of resimulate_suffix, for preview_threshold_decay."""

    def test_never_writes_to_the_ledger_or_the_athletes_live_value(self):
        athlete = _decay_athlete(email="preview-no-write@example.cc")
        anchor_date = date(2026, 1, 1)
        _make_power_activity(athlete, datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=260)
        recompute_for_activity(Activity.objects.get(athlete=athlete))
        athlete.refresh_from_db()
        expiry = anchor_date + timedelta(days=athlete.threshold_window_days + 1)
        end = expiry + timedelta(days=30)
        rows_before = list(ThresholdHistory.objects.filter(athlete=athlete, field="ftp").values_list("id", flat=True))
        ftp_before = athlete.ftp

        steps, _settings = gather_preview_steps(athlete, "ftp", expiry, end)

        self.assertGreater(len(steps), 0)  # a non-trivial preview, not a vacuous pass
        self.assertEqual(
            list(ThresholdHistory.objects.filter(athlete=athlete, field="ftp").values_list("id", flat=True)),
            rows_before,
        )
        athlete.refresh_from_db()
        self.assertEqual(athlete.ftp, ftp_before)

    def test_defaults_to_the_athletes_own_settings_when_nothing_is_overridden(self):
        athlete = _decay_athlete(email="preview-defaults@example.cc")
        _make_power_activity(athlete, datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=260)
        recompute_for_activity(Activity.objects.get(athlete=athlete))

        _steps, settings = gather_preview_steps(athlete, "ftp", date(2026, 1, 1), date(2026, 2, 1))

        self.assertEqual(settings.tau_days, athlete.threshold_decay_tau_days)
        self.assertEqual(settings.min_pct, athlete.threshold_decay_min_pct)
        self.assertEqual(settings.max_pct, athlete.threshold_decay_max_pct)

    def test_overrides_only_the_params_actually_given(self):
        athlete = _decay_athlete(email="preview-partial-override@example.cc")
        _make_power_activity(athlete, datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=260)
        recompute_for_activity(Activity.objects.get(athlete=athlete))

        _steps, settings = gather_preview_steps(athlete, "ftp", date(2026, 1, 1), date(2026, 2, 1), tau_days=99)

        self.assertEqual(settings.tau_days, 99)
        self.assertEqual(settings.min_pct, athlete.threshold_decay_min_pct)
        self.assertEqual(settings.max_pct, athlete.threshold_decay_max_pct)

    def test_forces_enabled_even_for_a_decay_disabled_athlete(self):
        """_simulate_disabled (the cliff-based legacy path) can never emit a 'decayed' kind step -
        only _simulate_enabled can. Seeing one here proves the forced enabled=True actually took
        effect, not just that settings_used reports it."""
        athlete = _decay_athlete(email="preview-forces-enabled@example.cc", threshold_decay_enabled=False)
        anchor_date = date(2026, 1, 1)
        _make_power_activity(athlete, datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=260)
        recompute_for_activity(Activity.objects.get(athlete=athlete))
        self.assertFalse(athlete.threshold_decay_enabled)
        expiry = anchor_date + timedelta(days=athlete.threshold_window_days + 1)

        steps, settings = gather_preview_steps(athlete, "ftp", expiry, expiry + timedelta(days=30))

        self.assertTrue(settings.enabled)
        self.assertTrue(any(step.kind == "decayed" for step in steps))
