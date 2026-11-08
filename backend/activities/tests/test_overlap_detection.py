import math
from datetime import UTC, datetime, timedelta

from django.test import TestCase

from accounts.models import User
from gear.models import Bike

from ..models import Record
from ..overlap_detection import choose_primary, detect_and_resolve_overlap
from .helpers import _make_activity


class OverlapDetectionTests(TestCase):
    """Issue #498's on-ingest side of overlap detection - mirrors backend_java's
    ActivityOverlapDetectionServiceIntegrationTest; see overlap_detection.py's own docstring for
    the primary-selection rule (mark) and the saved-rule/alignment-threshold decision (merge,
    issue #501 phase 4)."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="overlap-detection@example.cc", password="x", name="Athlete")
        self.start = datetime(2026, 6, 1, 18, 0, 0, tzinfo=UTC)

    def _bike(self, **kwargs):
        defaults = {"sport": "bike", "elapsed_seconds": 3600, "moving_time": 3600, "avg_hr": None}
        defaults.update(kwargs)
        return _make_activity(self.athlete, **defaults)

    def _add_correlated_cadence(self, activity, start, count, phase_offset):
        """A genuinely correlated cadence stream - see
        ActivityOverlapDetectionServiceIntegrationTest's own copy of this helper."""
        Record.objects.bulk_create(
            Record(
                activity=activity,
                t=t,
                ts=start + timedelta(seconds=t),
                cadence=round(80 + 5 * math.sin((t + phase_offset) / 30.0)),
            )
            for t in range(count)
        )

    def test_off_mode_does_nothing_at_all(self):
        self._bike(device="Zwift", start_date=self.start)
        garmin = self._bike(device="Garmin", start_date=self.start + timedelta(seconds=5))

        results = detect_and_resolve_overlap(garmin, "off")

        self.assertEqual(results, [])
        garmin.refresh_from_db()
        self.assertIsNone(garmin.primary_activity_id)

    def test_flag_mode_reports_the_pair_but_changes_nothing(self):
        zwift = self._bike(device="Zwift", start_date=self.start)
        garmin = self._bike(device="Garmin", start_date=self.start + timedelta(seconds=5))

        results = detect_and_resolve_overlap(garmin, "flag")

        self.assertEqual(
            results,
            [{"other_activity_id": zwift.id, "action": "flagged", "primary_activity_id": None, "merge_id": None}],
        )
        garmin.refresh_from_db()
        zwift.refresh_from_db()
        self.assertIsNone(garmin.primary_activity_id)
        self.assertIsNone(zwift.primary_activity_id)

    def test_merge_mode_merges_a_zwift_garmin_pair_when_alignment_score_clears_the_threshold(self):
        zwift = self._bike(device="Zwift", start_date=self.start)
        garmin = self._bike(device="Garmin", start_date=self.start + timedelta(seconds=5))
        self._add_correlated_cadence(zwift, self.start, 120, 0)
        self._add_correlated_cadence(garmin, self.start + timedelta(seconds=5), 120, 5)

        results = detect_and_resolve_overlap(garmin, "merge")

        self.assertEqual(len(results), 1)
        result = results[0]
        self.assertEqual(result["other_activity_id"], zwift.id)
        self.assertEqual(result["action"], "merged")
        self.assertEqual(result["primary_activity_id"], zwift.id)  # zwift is the rule's target
        self.assertIsNotNone(result["merge_id"])
        garmin.refresh_from_db()
        self.assertEqual(garmin.primary_activity_id, zwift.id)  # default rule hides the source too

    def test_merge_mode_skips_low_score_and_leaves_the_pair_unresolved(self):
        # No Record rows at all - the alignment calculator can't correlate anything, so the
        # score is 0.0 (well below the threshold) even though the rule's own device patterns
        # match zwift/garmin.
        self._bike(device="Zwift", start_date=self.start)
        garmin = self._bike(device="Garmin", start_date=self.start + timedelta(seconds=5))

        results = detect_and_resolve_overlap(garmin, "merge")

        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["action"], "merge_skipped_low_score")
        garmin.refresh_from_db()
        self.assertIsNone(garmin.primary_activity_id)

    def test_merge_mode_flags_when_no_rule_exists_for_the_sport(self):
        # No built-in default for run, and none saved here either.
        self._bike(device="Zwift", start_date=self.start, sport="run")
        garmin = self._bike(device="Garmin", start_date=self.start + timedelta(seconds=5), sport="run")

        results = detect_and_resolve_overlap(garmin, "merge")

        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["action"], "flagged")

    def test_merge_mode_flags_when_neither_side_matches_the_rules_device_patterns(self):
        self._bike(device="Edge 530", start_date=self.start)
        other = self._bike(device="Edge 530", start_date=self.start + timedelta(seconds=5))

        results = detect_and_resolve_overlap(other, "merge")

        self.assertEqual(len(results), 1)
        self.assertEqual(results[0]["action"], "flagged")

    def test_mark_mode_picks_the_zwift_copy_as_primary_regardless_of_which_side_is_newly_ingested(self):
        zwift = self._bike(device="Zwift", start_date=self.start)
        garmin = self._bike(device="Garmin", start_date=self.start + timedelta(seconds=5))

        results = detect_and_resolve_overlap(garmin, "mark")

        self.assertEqual(
            results,
            [{"other_activity_id": zwift.id, "action": "marked", "primary_activity_id": zwift.id, "merge_id": None}],
        )
        garmin.refresh_from_db()
        zwift.refresh_from_db()
        self.assertEqual(garmin.primary_activity_id, zwift.id)
        self.assertIsNone(zwift.primary_activity_id)

    def test_mark_mode_retracts_the_marked_duplicates_gear_mileage(self):
        # Issue #498: the Garmin copy's distance must not double-count on the bike's mileage
        # total alongside the Zwift copy's once it's marked as a duplicate.
        bike = Bike.objects.create(athlete=self.athlete, name="Kickr bike", distance_km=142)
        self._bike(device="Zwift", start_date=self.start, distance_km=42.0, bike=bike)
        garmin = self._bike(device="Garmin", start_date=self.start + timedelta(seconds=5), distance_km=42.0, bike=bike)

        detect_and_resolve_overlap(garmin, "mark")

        bike.refresh_from_db()
        self.assertEqual(bike.distance_km, 100)

    def test_mark_mode_picks_the_zwift_copy_as_primary_even_when_the_zwift_copy_is_the_newly_ingested_one(self):
        # Order of arrival shouldn't matter - the Garmin copy often arrives first (e.g. an
        # earlier sync) with the Zwift copy imported later, and Zwift must still end up primary.
        garmin = self._bike(device="Garmin", start_date=self.start)
        zwift = self._bike(device="Zwift", start_date=self.start + timedelta(seconds=5))

        results = detect_and_resolve_overlap(zwift, "mark")

        self.assertEqual(
            results,
            [{"other_activity_id": garmin.id, "action": "marked", "primary_activity_id": zwift.id, "merge_id": None}],
        )
        garmin.refresh_from_db()
        self.assertEqual(garmin.primary_activity_id, zwift.id)

    def test_mark_mode_with_neither_side_from_zwift_prefers_more_sensor_channels(self):
        few_sensors = self._bike(device="Edge 530", start_date=self.start, avg_power=200)
        many_sensors = self._bike(
            device="Fenix 8", start_date=self.start + timedelta(seconds=5), avg_power=205, avg_hr=140, avg_cadence=85
        )

        results = detect_and_resolve_overlap(few_sensors, "mark")

        self.assertEqual(
            results,
            [
                {
                    "other_activity_id": many_sensors.id,
                    "action": "marked",
                    "primary_activity_id": many_sensors.id,
                    "merge_id": None,
                }
            ],
        )
        few_sensors.refresh_from_db()
        self.assertEqual(few_sensors.primary_activity_id, many_sensors.id)

    def test_mark_mode_falls_back_to_flag_when_the_other_side_is_already_a_duplicate_of_a_third_activity(self):
        # Deliberately nowhere near self.start - this pre-existing manual link doesn't need to
        # itself overlap anything, and must not show up as a second overlap pair for zwift below.
        third_primary = self._bike(device="Wahoo", start_date=datetime(2026, 1, 1, tzinfo=UTC))
        garmin = self._bike(device="Garmin", start_date=self.start, primary_activity=third_primary)
        zwift = self._bike(device="Zwift", start_date=self.start + timedelta(seconds=5))

        results = detect_and_resolve_overlap(zwift, "mark")

        self.assertEqual(
            results,
            [{"other_activity_id": garmin.id, "action": "flagged", "primary_activity_id": None, "merge_id": None}],
        )
        zwift.refresh_from_db()
        self.assertIsNone(zwift.primary_activity_id)

    def test_multisport_activities_are_never_detected_at_all(self):
        self._bike(device="Zwift", start_date=self.start)
        multisport = self._bike(device="Garmin", start_date=self.start + timedelta(seconds=5), sport="multisport")

        self.assertEqual(detect_and_resolve_overlap(multisport, "mark"), [])

    def test_choose_primary_is_deterministic_when_neither_zwift_nor_sensors_decide_it(self):
        earlier = self._bike(device="Edge 530", start_date=self.start)
        later = self._bike(device="Edge 530", start_date=self.start + timedelta(seconds=5))

        self.assertEqual(choose_primary(earlier, later).id, earlier.id)
        self.assertEqual(choose_primary(later, earlier).id, earlier.id)
