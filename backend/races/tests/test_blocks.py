from datetime import UTC, date, datetime, timedelta

from django.test import TestCase

from accounts.models import User
from activities.models import ActivityTag, Tag
from activities.tests.helpers import _make_activity
from races.blocks import (
    a_race_block_range,
    find_comparison_race,
    find_parent_a_race,
    get_race_block,
    key_sessions,
    phases,
    riegel_prediction,
)
from races.models import Race


def _monday(d: date) -> date:
    return d - timedelta(days=d.weekday())


class ARaceBlockRangeTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")

    def test_default_block_is_the_full_preference_length(self):
        race = Race.objects.create(athlete=self.athlete, name="Marathon", date=date(2026, 10, 12), priority="A")
        block = a_race_block_range(race, block_weeks=16)
        self.assertEqual(block["weeks"], 16)
        self.assertEqual(block["end_week"], _monday(race.date))
        self.assertIsNone(block["truncated_by_race_id"])
        self.assertEqual(block["start_week"], _monday(race.date) - timedelta(weeks=15))

    def test_block_truncated_by_an_earlier_a_race(self):
        earlier = Race.objects.create(athlete=self.athlete, name="Spring Marathon", date=date(2026, 3, 1), priority="A")
        later = Race.objects.create(athlete=self.athlete, name="Autumn Marathon", date=date(2026, 6, 1), priority="A")

        block = a_race_block_range(later, block_weeks=16)
        self.assertTrue(block["weeks"] < 16)
        self.assertEqual(block["truncated_by_race_id"], earlier.id)
        self.assertEqual(block["start_week"], _monday(earlier.date) + timedelta(weeks=1))


class ParentARaceLookupTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")

    def test_b_race_inside_a_blocks_window_finds_parent(self):
        a_race = Race.objects.create(athlete=self.athlete, name="Goal Marathon", date=date(2026, 10, 12), priority="A")
        b_race = Race.objects.create(athlete=self.athlete, name="Tune-up Half", date=date(2026, 9, 14), priority="B")

        parent = find_parent_a_race(b_race, block_weeks=16)
        self.assertEqual(parent.id, a_race.id)

    def test_b_race_outside_any_block_has_no_parent(self):
        Race.objects.create(athlete=self.athlete, name="Goal Marathon", date=date(2026, 10, 12), priority="A")
        standalone = Race.objects.create(athlete=self.athlete, name="Lone 10k", date=date(2026, 1, 5), priority="B")

        self.assertIsNone(find_parent_a_race(standalone, block_weeks=16))


class ComparisonBlockTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")

    def test_finds_most_recent_same_distance_a_race(self):
        older = Race.objects.create(
            athlete=self.athlete,
            name="Spring Marathon",
            date=date(2025, 10, 1),
            priority="A",
            sport="run",
            distance_km=42.2,
        )
        newer_candidate = Race.objects.create(
            athlete=self.athlete,
            name="Summer Marathon",
            date=date(2026, 4, 1),
            priority="A",
            sport="run",
            distance_km=42.0,
        )
        this_race = Race.objects.create(
            athlete=self.athlete,
            name="Autumn Marathon",
            date=date(2026, 10, 12),
            priority="A",
            sport="run",
            distance_km=42.2,
        )

        comparison = find_comparison_race(this_race)
        self.assertEqual(comparison.id, newer_candidate.id)
        self.assertNotEqual(comparison.id, older.id)

    def test_distance_outside_tolerance_is_not_a_match(self):
        Race.objects.create(
            athlete=self.athlete, name="Half", date=date(2026, 1, 1), priority="A", sport="run", distance_km=21.1
        )
        this_race = Race.objects.create(
            athlete=self.athlete, name="Marathon", date=date(2026, 10, 12), priority="A", sport="run", distance_km=42.2
        )
        self.assertIsNone(find_comparison_race(this_race))


class RiegelPredictionTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")

    def test_picks_the_fastest_prediction(self):
        a_race = Race.objects.create(
            athlete=self.athlete,
            name="Goal Marathon",
            date=date(2026, 10, 12),
            priority="A",
            sport="run",
            distance_km=42.2,
        )
        slow_source = Race.objects.create(
            athlete=self.athlete,
            name="Slow 10k",
            date=date(2026, 9, 1),
            priority="C",
            sport="run",
            distance_km=10.0,
            result_time=timedelta(minutes=45),
        )
        fast_source = Race.objects.create(
            athlete=self.athlete,
            name="Fast Half",
            date=date(2026, 9, 15),
            priority="B",
            sport="run",
            distance_km=21.1,
            result_time=timedelta(hours=1, minutes=25),
        )

        start_week = _monday(a_race.date) - timedelta(weeks=15)
        prediction = riegel_prediction(a_race, start_week, _monday(a_race.date))

        self.assertIsNotNone(prediction)
        # A 1:25 half predicts a faster marathon than a 45:00 10k does, via Riegel.
        self.assertEqual(prediction["from_race_id"], fast_source.id)
        self.assertNotEqual(prediction["from_race_id"], slow_source.id)


class PhasesTests(TestCase):
    def test_phases_sum_to_the_block_length(self):
        result = phases(16)
        self.assertEqual(result[-1]["end_week"], 16)
        self.assertEqual(result[0]["start_week"], 1)
        names = [p["name"] for p in result]
        self.assertEqual(names, ["Base", "Build", "Peak", "Taper"])


class KeySessionsTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")

    def test_returns_every_candidate_not_just_a_capped_subset(self):
        """key_sessions no longer trims to the athlete's race_key_session_cap - the frontend
        paginates the full candidate list client-side instead, using that preference as its
        page size. items must always equal total_candidates."""
        race = Race.objects.create(
            athlete=self.athlete,
            name="Goal Marathon",
            date=date(2026, 10, 12),
            priority="A",
            sport="run",
            distance_km=42.2,
        )
        window_start = date(2026, 9, 1)
        window_end = date(2026, 10, 11)

        for i in range(6):
            _make_activity(
                self.athlete,
                name=f"Long run {i}",
                start_date=datetime(2026, 9, 2 + i, 7, 0, tzinfo=UTC),
                distance_km=25.0 + i,
                sport="run",
            )

        result = key_sessions(race, window_start, window_end)
        self.assertGreaterEqual(result["total_candidates"], 4)  # 4 "long" candidates kept at most
        self.assertEqual(len(result["items"]), result["total_candidates"])

    def test_races_in_window_are_always_included(self):
        race = Race.objects.create(
            athlete=self.athlete,
            name="Goal Marathon",
            date=date(2026, 10, 12),
            priority="A",
            sport="run",
            distance_km=42.2,
        )
        tune_up = Race.objects.create(
            athlete=self.athlete,
            name="Tune-up 10k",
            date=date(2026, 10, 5),
            priority="C",
            sport="run",
            distance_km=10.0,
        )
        result = key_sessions(race, date(2026, 9, 1), date(2026, 10, 11))
        names = [item["name"] for item in result["items"]]
        self.assertIn(tune_up.name, names)

    def test_each_item_carries_its_own_sport_for_frontend_filtering(self):
        race = Race.objects.create(
            athlete=self.athlete,
            name="Goal Marathon",
            date=date(2026, 10, 12),
            priority="A",
            sport="run",
            distance_km=42.2,
        )
        tag = Tag.objects.create(athlete=self.athlete, name="Key session")
        bike_tagged = _make_activity(
            self.athlete,
            name="Tagged ride",
            start_date=datetime(2026, 9, 5, 7, 0, tzinfo=UTC),
            distance_km=40.0,
            sport="bike",
        )
        ActivityTag.objects.create(activity=bike_tagged, tag=tag)
        run_tagged = _make_activity(
            self.athlete,
            name="Tagged run",
            start_date=datetime(2026, 9, 6, 7, 0, tzinfo=UTC),
            distance_km=12.0,
            sport="run",
        )
        ActivityTag.objects.create(activity=run_tagged, tag=tag)

        result = key_sessions(race, date(2026, 9, 1), date(2026, 10, 11))
        by_id = {item["activity_id"]: item for item in result["items"]}
        self.assertEqual(by_id[bike_tagged.id]["sport"], "bike")
        self.assertEqual(by_id[run_tagged.id]["sport"], "run")

    def test_races_own_key_session_tag_overrides_the_default_tag(self):
        """A race with its own key_session_tag set pulls "tagged" candidates from that tag
        instead of the athlete-wide tag literally named "Key session" - lets different goal
        races use different tags rather than sharing one global name."""
        default_tag = Tag.objects.create(athlete=self.athlete, name="Key session")
        marathon_tag = Tag.objects.create(athlete=self.athlete, name="Marathon key")
        race = Race.objects.create(
            athlete=self.athlete,
            name="Goal Marathon",
            date=date(2026, 10, 12),
            priority="A",
            sport="run",
            distance_km=42.2,
            key_session_tag=marathon_tag,
        )
        tagged_with_default = _make_activity(
            self.athlete,
            name="Tagged with default",
            start_date=datetime(2026, 9, 5, 7, 0, tzinfo=UTC),
            distance_km=10.0,
            sport="run",
        )
        ActivityTag.objects.create(activity=tagged_with_default, tag=default_tag)
        tagged_with_marathon = _make_activity(
            self.athlete,
            name="Tagged with marathon key",
            start_date=datetime(2026, 9, 6, 7, 0, tzinfo=UTC),
            distance_km=11.0,
            sport="run",
        )
        ActivityTag.objects.create(activity=tagged_with_marathon, tag=marathon_tag)

        result = key_sessions(race, date(2026, 9, 1), date(2026, 10, 11))
        by_id = {item["activity_id"]: item for item in result["items"] if item["activity_id"]}

        self.assertNotEqual(by_id[tagged_with_default.id]["reason"], "tagged")
        self.assertEqual(by_id[tagged_with_marathon.id]["reason"], "tagged")
        self.assertEqual(by_id[tagged_with_marathon.id]["label"], "Marathon key")


class GetRaceBlockSmokeTests(TestCase):
    """End-to-end smoke test of the top-level assembler - mostly checking it doesn't
    blow up and returns the right shape, not every rule's exact numbers (those are
    covered individually above)."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")

    def test_a_race_block_shape(self):
        race = Race.objects.create(
            athlete=self.athlete,
            name="Goal Marathon",
            date=date(2026, 10, 12),
            priority="A",
            sport="run",
            distance_km=42.2,
        )
        result = get_race_block(race)
        self.assertEqual(len(result["weeks"]), 16)
        self.assertEqual(len(result["phases"]), 4)
        self.assertIn("race_day_form", result)
        self.assertIn("key_sessions", result)
        self.assertIn("sweat_rate_l_per_h", result)
        self.assertEqual(result["sweat_rate_source"], "default")

    def test_b_race_block_shape(self):
        race = Race.objects.create(
            athlete=self.athlete,
            name="Tune-up Half",
            date=date(2026, 9, 14),
            priority="B",
            sport="run",
            distance_km=21.1,
        )
        result = get_race_block(race)
        self.assertEqual(len(result["weeks"]), 5)
        self.assertEqual(result["phases"], [])
        self.assertIn("parent", result)

    def test_b_race_with_parent_block_starts_with_parent_and_ends_at_race(self):
        a_race = Race.objects.create(
            athlete=self.athlete, name="Leeds Marathon", date=date(2026, 5, 10), priority="A", sport="run"
        )
        b_race = Race.objects.create(
            athlete=self.athlete, name="Brighton Marathon", date=date(2026, 4, 12), priority="B", sport="run"
        )

        result = get_race_block(b_race)
        parent_block = a_race_block_range(a_race, block_weeks=16)

        self.assertEqual(result["start"], parent_block["start_week"].isoformat())
        self.assertEqual(result["end"], _monday(b_race.date).isoformat())
        self.assertEqual(result["phases"], [])
        self.assertEqual(result["parent"]["race_id"], a_race.id)
        self.assertEqual(result["parent"]["week"], len(result["weeks"]))
        self.assertEqual(result["parent"]["of"], 16)
        self.assertEqual(result["weeks_requested"], len(result["weeks"]))

    def test_week_tss_is_split_by_sport(self):
        race = Race.objects.create(
            athlete=self.athlete, name="Spring Marathon", date=date(2026, 3, 1), priority="A", sport="run"
        )
        _make_activity(self.athlete, sport="run", tss=80, start_date=datetime(2026, 2, 17, 7, 0, tzinfo=UTC))
        _make_activity(self.athlete, sport="bike", tss=120, start_date=datetime(2026, 2, 18, 7, 0, tzinfo=UTC))
        _make_activity(self.athlete, sport="swim", tss=30, start_date=datetime(2026, 2, 19, 7, 0, tzinfo=UTC))

        result = get_race_block(race)
        week = next(w for w in result["weeks"] if w["week_start"] == "2026-02-16")
        self.assertEqual(week["tss"], 230)
        self.assertEqual(week["run_tss"], 80)
        self.assertEqual(week["ride_tss"], 120)

    def test_run_long_pace_is_the_longest_runs_own_pace(self):
        race = Race.objects.create(
            athlete=self.athlete, name="Spring Marathon", date=date(2026, 3, 1), priority="A", sport="run"
        )
        # Shorter run, faster pace - shouldn't win "long".
        _make_activity(
            self.athlete, distance_km=8.0, moving_time=2400, start_date=datetime(2026, 2, 17, 7, 0, tzinfo=UTC)
        )
        # The week's actual long run, at a slower pace.
        _make_activity(
            self.athlete, distance_km=20.0, moving_time=7200, start_date=datetime(2026, 2, 18, 7, 0, tzinfo=UTC)
        )

        result = get_race_block(race)
        week = next(w for w in result["weeks"] if w["week_start"] == "2026-02-16")
        self.assertEqual(week["run_long_km"], 20.0)
        self.assertEqual(week["run_long_pace_s_per_km"], 360)  # 7200s / 20km
