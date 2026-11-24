from datetime import UTC, date, datetime, timedelta

from django.test import TestCase

from accounts.models import User
from activities.models import Activity, BestEffort

from ..processing import update_best_efforts

# Outside every BEST_EFFORT_TRIM_PERIOD_DAYS cutoff (28/90/112/365), so only the all-time
# ranking - not a narrower period's own top-N - decides which rows survive trim.
OUTSIDE_EVERY_PERIOD_DAYS = 1000


class BestEffortTopNPerKindTests(TestCase):
    """Issue #597: running_pace defaults to unlimited retention (independent of the flat
    best_effort_top_n), so an embedded split within a longer activity - which almost never
    ranks in an athlete's all-time top N for that specific window - is no longer silently
    deleted the moment it's computed. Power keeps the pre-existing trimmed-by-default behavior
    unless an athlete opts into its own override. Mirrors the Java backend's
    BestEffortTopNPerKindIntegrationTest."""

    def setUp(self):
        self.athlete = User.objects.create_user(
            email="be-topn-per-kind@example.cc", password="x", name="Athlete", ftp=200
        )

    def _make_row(self, kind, window, value, days_ago):
        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="bike" if kind == "cycling_power" else "run",
            name=f"Fixture -{days_ago}d",
            start_date=datetime.now(UTC) - timedelta(days=days_ago),
        )
        return BestEffort.objects.create(
            athlete=self.athlete,
            kind=kind,
            window=window,
            value=value,
            unit="sec_per_km" if kind == "running_pace" else "watts",
            date=date.today() - timedelta(days=days_ago),
            activity=activity,
        )

    def _new_long_run(self, days_ago):
        return Activity.objects.create(
            athlete=self.athlete,
            sport="run",
            name=f"Long run -{days_ago}d",
            start_date=datetime.now(UTC) - timedelta(days=days_ago),
        )

    def _new_ride(self, days_ago):
        return Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name="Mediocre ride",
            start_date=datetime.now(UTC) - timedelta(days=days_ago),
        )

    def test_an_embedded_pace_split_survives_by_default_even_when_not_all_time_top_ten(self):
        self.assertEqual(self.athlete.best_effort_top_n, 10)  # flat default, unchanged
        self.assertEqual(self.athlete.best_effort_top_n_running_pace, 0)  # pace's own default: unlimited

        # Ten standalone 1 km efforts, all faster than the embedded split below - these alone
        # would fill the old, shared top-10 under the pre-#597 behavior.
        for i in range(10):
            self._make_row("running_pace", "1km", 200.0 + i, OUTSIDE_EVERY_PERIOD_DAYS)

        # An 11th, slower 1 km split - computed (not pre-inserted) through the real entry
        # point, same as a marathon's internal 1 km segment would be.
        long_run = self._new_long_run(OUTSIDE_EVERY_PERIOD_DAYS)
        samples = 42200  # one sample per metre at a flat 400 sec/km pace
        t_series = [round(i * 0.4) for i in range(samples + 1)]
        distance_series = [i / 1000.0 for i in range(samples + 1)]

        update_best_efforts(long_run, self.athlete, [], t_series, distance_series)

        rows = BestEffort.objects.filter(athlete=self.athlete, kind="running_pace", window="1km")
        self.assertEqual(rows.count(), 11)
        self.assertTrue(rows.filter(activity=long_run).exists())

    def test_power_stays_trimmed_by_default_unless_overridden(self):
        self.assertIsNone(self.athlete.best_effort_top_n_power)  # defers to the flat setting

        for i in range(10):
            self._make_row("cycling_power", "5min", 300.0 - i, OUTSIDE_EVERY_PERIOD_DAYS)
        ride = self._new_ride(OUTSIDE_EVERY_PERIOD_DAYS)
        power_series = [150] * 600  # worse than every fixture row above

        update_best_efforts(ride, self.athlete, power_series, [], [])

        rows = BestEffort.objects.filter(athlete=self.athlete, kind="cycling_power", window="5min")
        self.assertEqual(rows.count(), 10)  # the flat top_n=10 - the new, worse row was trimmed back out
        self.assertFalse(rows.filter(activity=ride).exists())

    def test_power_override_keeps_everything_once_set(self):
        self.athlete.best_effort_top_n_power = 0
        self.athlete.save()

        for i in range(10):
            self._make_row("cycling_power", "5min", 300.0 - i, OUTSIDE_EVERY_PERIOD_DAYS)
        ride = self._new_ride(OUTSIDE_EVERY_PERIOD_DAYS)
        power_series = [150] * 600

        update_best_efforts(ride, self.athlete, power_series, [], [])

        rows = BestEffort.objects.filter(athlete=self.athlete, kind="cycling_power", window="5min")
        self.assertEqual(rows.count(), 11)
