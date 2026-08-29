from datetime import UTC, date, datetime, timedelta

from django.test import TestCase

from accounts.models import User
from activities.models import Activity, ActivityTag, BestEffort, Tag
from scheduling.models import ScheduledWorkout
from workouts.models import Workout

from ..processing import BEST_EFFORT_TRIM_PERIOD_DAYS, _trim_kind_window, attempt_workout_match, update_best_efforts


class BestEffortUpsertTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="be@example.cc", password="x", name="BE Athlete", ftp=1)

    def _activity(self, suffix):
        return Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name=f"Ride {suffix}",
            start_date=datetime(2026, 6, 10, 7, 0, tzinfo=UTC),
        )

    def _best(self, kind, window):
        return BestEffort.objects.filter(athlete=self.athlete, kind=kind, window=window).order_by("-value").first()

    def test_improves_then_holds_then_improves_again(self):
        a1 = self._activity("1")
        update_best_efforts(a1, self.athlete, [200] * 60, [], [])
        self.assertEqual(self._best("cycling_power", "1min").value, 200.0)
        self.assertEqual(self._best("cycling_power", "1min").activity_id, a1.id)

        a2 = self._activity("2")
        update_best_efforts(a2, self.athlete, [150] * 60, [], [])
        self.assertEqual(self._best("cycling_power", "1min").value, 200.0)
        self.assertEqual(self._best("cycling_power", "1min").activity_id, a1.id)

        a3 = self._activity("3")
        update_best_efforts(a3, self.athlete, [250] * 60, [], [])
        self.assertEqual(self._best("cycling_power", "1min").value, 250.0)
        self.assertEqual(self._best("cycling_power", "1min").activity_id, a3.id)


class RunningPowerBestEffortSourceGateTests(TestCase):
    """A run activity whose power_source no longer matches the athlete's current
    running_power_source preference is excluded from running_power best efforts entirely -
    see Activity.matches_running_power_preference."""

    def setUp(self):
        self.athlete = User.objects.create_user(
            email="be-power-source@example.cc",
            password="x",
            name="Athlete",
            critical_run_power=1,
            running_power_source="stryd",
        )

    def _run(self, power_source):
        return Activity.objects.create(
            athlete=self.athlete,
            sport="run",
            name="Run",
            power_source=power_source,
            start_date=datetime(2026, 6, 10, 7, 0, tzinfo=UTC),
        )

    def test_mismatched_source_produces_no_running_power_best_effort(self):
        activity = self._run(power_source="native")
        update_best_efforts(activity, self.athlete, [300] * 60, [], [])
        self.assertIsNone(BestEffort.objects.filter(athlete=self.athlete, kind="running_power").first())

    def test_matching_source_produces_a_running_power_best_effort(self):
        activity = self._run(power_source="stryd")
        update_best_efforts(activity, self.athlete, [300] * 60, [], [])
        best = BestEffort.objects.filter(athlete=self.athlete, kind="running_power").first()
        self.assertIsNotNone(best)
        self.assertEqual(best.activity_id, activity.id)

    def test_untagged_source_is_trusted_as_before(self):
        # A pre-feature activity (power_source="") - not newly excluded by this preference.
        activity = self._run(power_source="")
        update_best_efforts(activity, self.athlete, [300] * 60, [], [])
        best = BestEffort.objects.filter(athlete=self.athlete, kind="running_power").first()
        self.assertIsNotNone(best)


class BestEffortTrimPeriodTests(TestCase):
    """_trim_kind_window keeps a row if it's a top-N record within ANY tracked period
    (BEST_EFFORT_TRIM_PERIOD_DAYS, or all-time), not just the all-time top-N."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="trim@example.cc", password="x", name="Trim Athlete")
        self.top_n = 2

    def _make(self, value, days_ago, window="10km"):
        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="run",
            name=f"Run -{days_ago}d",
            start_date=datetime.now(UTC) - timedelta(days=days_ago),
        )
        return BestEffort.objects.create(
            athlete=self.athlete,
            kind="running_pace",
            window=window,
            value=value,
            unit="sec_per_km",
            date=date.today() - timedelta(days=days_ago),
            activity=activity,
        )

    def _survivor_ids(self, window="10km"):
        return set(
            BestEffort.objects.filter(athlete=self.athlete, kind="running_pace", window=window).values_list(
                "id", flat=True
            )
        )

    def test_recent_effort_survives_even_when_not_all_time_top_n(self):
        # Two very fast, very old efforts fill the all-time top-2 (lower value = better pace).
        old1 = self._make(200.0, days_ago=1000)
        old2 = self._make(210.0, days_ago=900)
        # 3rd all-time, but the only effort in the last 28 days - should survive on that basis.
        recent = self._make(280.0, days_ago=10)

        _trim_kind_window(self.athlete.id, "running_pace", "10km", True, self.top_n)

        self.assertEqual(self._survivor_ids(), {old1.id, old2.id, recent.id})

    def test_row_deleted_once_it_loses_every_period(self):
        self._make(200.0, days_ago=1000)
        self._make(210.0, days_ago=900)
        recent = self._make(280.0, days_ago=10)
        _trim_kind_window(self.athlete.id, "running_pace", "10km", True, self.top_n)
        self.assertIn(recent.id, self._survivor_ids())

        # Two faster, similarly-recent efforts now fill every period's top-2 ahead of `recent` -
        # it's no longer a record in the 28-day window, and every wider period it also belongs
        # to (90/112/365/all) prefers these two over it as well.
        self._make(150.0, days_ago=5)
        self._make(160.0, days_ago=4)
        _trim_kind_window(self.athlete.id, "running_pace", "10km", True, self.top_n)

        self.assertNotIn(recent.id, self._survivor_ids())

    def test_trim_bound_respects_top_n_times_period_count(self):
        # One pair of rows per tracked period, deliberately slower the more recent the band -
        # so each period's own top-2 are exactly that band's two rows, none of which intrude on
        # a narrower (more recent) period's top-2. Plus one extra row that isn't fast enough to
        # win any period, to prove it's the one that gets dropped rather than the bound being
        # exceeded.
        bands_oldest_first = [
            max(BEST_EFFORT_TRIM_PERIOD_DAYS) + 35,
            *sorted(BEST_EFFORT_TRIM_PERIOD_DAYS, reverse=True),
        ]
        expected_survivors = set()
        for band_index, days_ago in enumerate(bands_oldest_first):
            base_value = band_index * 10
            expected_survivors.add(self._make(base_value, days_ago=days_ago - 5).id)
            expected_survivors.add(self._make(base_value + 1, days_ago=days_ago - 4).id)
        loser = self._make(999.0, days_ago=3)

        _trim_kind_window(self.athlete.id, "running_pace", "10km", True, self.top_n)

        survivors = self._survivor_ids()
        self.assertEqual(len(survivors), self.top_n * (len(BEST_EFFORT_TRIM_PERIOD_DAYS) + 1))
        self.assertEqual(survivors, expected_survivors)
        self.assertNotIn(loser.id, survivors)


class WorkoutMatchingTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="wm@example.cc", password="x", name="WM Athlete")

    def test_links_and_tags_matching_scheduled_workout(self):
        workout = Workout.objects.create(created_by=self.athlete, name="Tempo run", sport="run")
        scheduled = ScheduledWorkout.objects.create(workout=workout, athlete=self.athlete, date=date(2026, 6, 11))

        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="run",
            name="Morning run",
            start_date=datetime(2026, 6, 11, 6, 30, tzinfo=UTC),
        )
        attempt_workout_match(activity, self.athlete)

        scheduled.refresh_from_db()
        activity.refresh_from_db()
        self.assertEqual(scheduled.status, "completed")
        self.assertEqual(scheduled.activity_id, activity.id)
        self.assertEqual(activity.workout_id, workout.id)
        self.assertTrue(
            ActivityTag.objects.filter(activity=activity, tag__name="Auto-matched", tag__origin="auto").exists()
        )

    def test_does_not_match_different_sport(self):
        workout = Workout.objects.create(created_by=self.athlete, name="Tempo run", sport="run")
        scheduled = ScheduledWorkout.objects.create(workout=workout, athlete=self.athlete, date=date(2026, 6, 12))

        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name="Easy ride",
            start_date=datetime(2026, 6, 12, 6, 30, tzinfo=UTC),
        )
        attempt_workout_match(activity, self.athlete)

        scheduled.refresh_from_db()
        self.assertEqual(scheduled.status, "planned")
        self.assertIsNone(scheduled.activity_id)

    def test_leaves_name_untouched_by_default(self):
        workout = Workout.objects.create(created_by=self.athlete, name="Tempo run", sport="run")
        ScheduledWorkout.objects.create(workout=workout, athlete=self.athlete, date=date(2026, 6, 13))
        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="run",
            name="Morning run",
            start_date=datetime(2026, 6, 13, 6, 30, tzinfo=UTC),
        )

        attempt_workout_match(activity, self.athlete)

        activity.refresh_from_db()
        self.assertEqual(activity.name, "Morning run")

    def test_renames_to_workout_name_when_preference_enabled(self):
        self.athlete.rename_matched_activities = True
        self.athlete.save(update_fields=["rename_matched_activities"])
        workout = Workout.objects.create(created_by=self.athlete, name="Tempo run", sport="run")
        ScheduledWorkout.objects.create(workout=workout, athlete=self.athlete, date=date(2026, 6, 14))
        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="run",
            name="Morning run",
            start_date=datetime(2026, 6, 14, 6, 30, tzinfo=UTC),
        )

        attempt_workout_match(activity, self.athlete)

        activity.refresh_from_db()
        self.assertEqual(activity.name, "Tempo run")

    def test_appends_date_only_when_both_preferences_enabled(self):
        self.athlete.rename_matched_activities = True
        self.athlete.append_match_date_to_name = True
        self.athlete.save(update_fields=["rename_matched_activities", "append_match_date_to_name"])
        workout = Workout.objects.create(created_by=self.athlete, name="Tempo run", sport="run")
        ScheduledWorkout.objects.create(workout=workout, athlete=self.athlete, date=date(2026, 6, 15))
        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="run",
            name="Morning run",
            start_date=datetime(2026, 6, 15, 6, 30, tzinfo=UTC),
        )

        attempt_workout_match(activity, self.athlete)

        activity.refresh_from_db()
        self.assertEqual(activity.name, "Tempo run - 2026-06-15")

    def test_append_date_preference_has_no_effect_when_rename_is_off(self):
        self.athlete.append_match_date_to_name = True
        self.athlete.save(update_fields=["append_match_date_to_name"])
        workout = Workout.objects.create(created_by=self.athlete, name="Tempo run", sport="run")
        ScheduledWorkout.objects.create(workout=workout, athlete=self.athlete, date=date(2026, 6, 16))
        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="run",
            name="Morning run",
            start_date=datetime(2026, 6, 16, 6, 30, tzinfo=UTC),
        )

        attempt_workout_match(activity, self.athlete)

        activity.refresh_from_db()
        self.assertEqual(activity.name, "Morning run")

    def test_does_not_copy_workout_tags_by_default(self):
        workout = Workout.objects.create(
            created_by=self.athlete, name="Tempo run", sport="run", tags=["Speedwork", "Race prep"]
        )
        ScheduledWorkout.objects.create(workout=workout, athlete=self.athlete, date=date(2026, 6, 17))
        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="run",
            name="Morning run",
            start_date=datetime(2026, 6, 17, 6, 30, tzinfo=UTC),
        )

        attempt_workout_match(activity, self.athlete)

        self.assertFalse(ActivityTag.objects.filter(activity=activity, tag__name="Speedwork").exists())

    def test_copies_workout_tags_when_preference_enabled(self):
        self.athlete.copy_matched_workout_tags = True
        self.athlete.save(update_fields=["copy_matched_workout_tags"])
        workout = Workout.objects.create(
            created_by=self.athlete, name="Tempo run", sport="run", tags=["Speedwork", "Race prep"]
        )
        ScheduledWorkout.objects.create(workout=workout, athlete=self.athlete, date=date(2026, 6, 18))
        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="run",
            name="Morning run",
            start_date=datetime(2026, 6, 18, 6, 30, tzinfo=UTC),
        )

        attempt_workout_match(activity, self.athlete)

        tag_names = set(ActivityTag.objects.filter(activity=activity).values_list("tag__name", flat=True))
        self.assertEqual(tag_names, {"Auto-matched", "Speedwork", "Race prep"})
        self.assertEqual(Tag.objects.get(athlete=self.athlete, name="Speedwork").origin, "auto")

    def test_reuses_an_existing_tag_with_the_same_name_instead_of_duplicating(self):
        self.athlete.copy_matched_workout_tags = True
        self.athlete.save(update_fields=["copy_matched_workout_tags"])
        existing = Tag.objects.create(athlete=self.athlete, name="Speedwork", origin="manual")
        workout = Workout.objects.create(created_by=self.athlete, name="Tempo run", sport="run", tags=["Speedwork"])
        ScheduledWorkout.objects.create(workout=workout, athlete=self.athlete, date=date(2026, 6, 19))
        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="run",
            name="Morning run",
            start_date=datetime(2026, 6, 19, 6, 30, tzinfo=UTC),
        )

        attempt_workout_match(activity, self.athlete)

        self.assertEqual(Tag.objects.filter(athlete=self.athlete, name="Speedwork").count(), 1)
        self.assertTrue(ActivityTag.objects.filter(activity=activity, tag=existing).exists())
        existing.refresh_from_db()
        self.assertEqual(existing.origin, "manual")

    def test_handles_workout_with_no_tags_gracefully(self):
        self.athlete.copy_matched_workout_tags = True
        self.athlete.save(update_fields=["copy_matched_workout_tags"])
        workout = Workout.objects.create(created_by=self.athlete, name="Tempo run", sport="run")
        ScheduledWorkout.objects.create(workout=workout, athlete=self.athlete, date=date(2026, 6, 20))
        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="run",
            name="Morning run",
            start_date=datetime(2026, 6, 20, 6, 30, tzinfo=UTC),
        )

        attempt_workout_match(activity, self.athlete)

        tag_names = set(ActivityTag.objects.filter(activity=activity).values_list("tag__name", flat=True))
        self.assertEqual(tag_names, {"Auto-matched"})
