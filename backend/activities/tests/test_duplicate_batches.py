from datetime import UTC, datetime, timedelta

from django.http import Http404
from django.test import TestCase
from rest_framework.exceptions import ValidationError

from accounts.models import User
from gear.models import Bike

from ..duplicate_batches import resolve_duplicates_batch, undo_batch
from ..models import DuplicateResolutionBatch
from .helpers import _make_activity


class ResolveDuplicatesBatchTests(TestCase):
    """Issue #498's resolve_duplicates_batch/undo_batch - the primary-selection/mark/flag logic
    itself is covered directly (and far more cheaply) by OverlapDetectionTests; this exercises
    the batch's own pairs-vs-query selection, dry-run preview, apply/persist/recompute wiring,
    and undo."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="dup-batch@example.cc", password="x", name="Athlete")
        self.other_athlete = User.objects.create_user(
            email="dup-batch-other@example.cc", password="x", name="Other Athlete"
        )
        self.start = datetime(2026, 6, 1, 18, 0, 0, tzinfo=UTC)

    def _bike(self, athlete, **kwargs):
        defaults = {"sport": "bike", "elapsed_seconds": 3600, "moving_time": 3600, "avg_hr": None}
        defaults.update(kwargs)
        return _make_activity(athlete, **defaults)

    def test_dry_run_with_explicit_pairs_previews_without_mutating_anything(self):
        zwift = self._bike(self.athlete, device="Zwift", start_date=self.start)
        garmin = self._bike(self.athlete, device="Garmin", start_date=self.start + timedelta(seconds=5))

        result = resolve_duplicates_batch(self.athlete, "mark", pairs=[[garmin.id, zwift.id]])

        self.assertTrue(result["dry_run"])
        self.assertIsNone(result["batch_id"])
        self.assertEqual(
            result["results"],
            [
                {
                    "other_activity_id": zwift.id,
                    "action": "marked",
                    "primary_activity_id": zwift.id,
                    "merge_id": None,
                    "activity_id": garmin.id,
                }
            ],
        )
        garmin.refresh_from_db()
        self.assertIsNone(garmin.primary_activity_id)
        self.assertEqual(DuplicateResolutionBatch.objects.count(), 0)

    def test_apply_with_explicit_pairs_marks_and_persists_an_undoable_batch(self):
        zwift = self._bike(self.athlete, device="Zwift", start_date=self.start)
        garmin = self._bike(self.athlete, device="Garmin", start_date=self.start + timedelta(seconds=5))

        result = resolve_duplicates_batch(self.athlete, "mark", pairs=[[garmin.id, zwift.id]], dry_run=False)

        self.assertFalse(result["dry_run"])
        self.assertIsNotNone(result["batch_id"])
        self.assertEqual(result["results"][0]["action"], "marked")
        garmin.refresh_from_db()
        self.assertEqual(garmin.primary_activity_id, zwift.id)
        batch = DuplicateResolutionBatch.objects.get(pk=result["batch_id"])
        self.assertEqual(batch.athlete_id, self.athlete.id)
        self.assertEqual(batch.action, "mark")
        self.assertEqual(batch.entries.count(), 1)
        entry = batch.entries.first()
        self.assertEqual(entry.activity_id, garmin.id)
        self.assertEqual(entry.outcome, "marked")
        self.assertEqual(entry.primary_activity_id, zwift.id)

    def test_apply_retracts_the_marked_duplicates_gear_mileage(self):
        bike = Bike.objects.create(athlete=self.athlete, name="Kickr bike", distance_km=142)
        zwift = self._bike(self.athlete, device="Zwift", start_date=self.start, distance_km=42.0, bike=bike)
        garmin = self._bike(
            self.athlete, device="Garmin", start_date=self.start + timedelta(seconds=5), distance_km=42.0, bike=bike
        )

        resolve_duplicates_batch(self.athlete, "mark", pairs=[[garmin.id, zwift.id]], dry_run=False)

        bike.refresh_from_db()
        self.assertEqual(bike.distance_km, 100)

    def test_apply_using_the_overlap_querys_own_filters_instead_of_pairs(self):
        zwift = self._bike(self.athlete, device="Zwift", start_date=self.start)
        garmin = self._bike(self.athlete, device="Garmin", start_date=self.start + timedelta(seconds=5))

        result = resolve_duplicates_batch(self.athlete, "mark", sport="bike", dry_run=False)

        self.assertEqual(len(result["results"]), 1)
        entry = result["results"][0]
        self.assertEqual(entry["action"], "marked")
        self.assertEqual(entry["primary_activity_id"], zwift.id)
        self.assertEqual({entry["activity_id"], entry["other_activity_id"]}, {garmin.id, zwift.id})
        garmin.refresh_from_db()
        self.assertEqual(garmin.primary_activity_id, zwift.id)

    def test_merge_action_skips_low_score_without_any_correlated_data(self):
        # No Record rows - see OverlapDetectionTests's own equivalent test for why this is 0.0
        # (below threshold), not a null/error.
        zwift = self._bike(self.athlete, device="Zwift", start_date=self.start)
        garmin = self._bike(self.athlete, device="Garmin", start_date=self.start + timedelta(seconds=5))

        result = resolve_duplicates_batch(self.athlete, "merge", pairs=[[garmin.id, zwift.id]], dry_run=False)

        self.assertEqual(result["results"][0]["action"], "merge_skipped_low_score")
        garmin.refresh_from_db()
        self.assertIsNone(garmin.primary_activity_id)

    def test_rejects_an_unknown_action(self):
        with self.assertRaises(ValidationError):
            resolve_duplicates_batch(self.athlete, "delete", pairs=[["a", "b"]])

    def test_rejects_passing_both_pairs_and_query_filters(self):
        with self.assertRaises(ValidationError):
            resolve_duplicates_batch(self.athlete, "mark", pairs=[["a", "b"]], sport="bike")

    def test_rejects_a_pair_referencing_another_athletes_activity(self):
        mine = self._bike(self.athlete, device="Zwift", start_date=self.start)
        theirs = self._bike(self.other_athlete, device="Garmin", start_date=self.start + timedelta(seconds=5))

        with self.assertRaises(Http404):
            resolve_duplicates_batch(self.athlete, "mark", pairs=[[mine.id, theirs.id]], dry_run=False)

    def test_undo_batch_reverts_a_marked_pair_and_restores_gear_mileage(self):
        bike = Bike.objects.create(athlete=self.athlete, name="Kickr bike", distance_km=142)
        zwift = self._bike(self.athlete, device="Zwift", start_date=self.start, distance_km=42.0, bike=bike)
        garmin = self._bike(
            self.athlete, device="Garmin", start_date=self.start + timedelta(seconds=5), distance_km=42.0, bike=bike
        )
        result = resolve_duplicates_batch(self.athlete, "mark", pairs=[[garmin.id, zwift.id]], dry_run=False)
        bike.refresh_from_db()
        self.assertEqual(bike.distance_km, 100)

        undo_result = undo_batch(self.athlete, result["batch_id"])

        self.assertEqual(undo_result["reverted_count"], 1)
        self.assertEqual(undo_result["skipped_count"], 0)
        garmin.refresh_from_db()
        self.assertIsNone(garmin.primary_activity_id)
        bike.refresh_from_db()
        self.assertEqual(bike.distance_km, 142)

    def test_undo_batch_skips_an_entry_thats_been_re_resolved_since(self):
        zwift = self._bike(self.athlete, device="Zwift", start_date=self.start)
        garmin = self._bike(self.athlete, device="Garmin", start_date=self.start + timedelta(seconds=5))
        result = resolve_duplicates_batch(self.athlete, "mark", pairs=[[garmin.id, zwift.id]], dry_run=False)
        other_primary = self._bike(self.athlete, device="Wahoo", start_date=datetime(2026, 1, 1, tzinfo=UTC))
        garmin.refresh_from_db()
        garmin.primary_activity = other_primary
        garmin.save(update_fields=["primary_activity"])

        undo_result = undo_batch(self.athlete, result["batch_id"])

        self.assertEqual(undo_result["reverted_count"], 0)
        self.assertEqual(undo_result["skipped_count"], 1)
        garmin.refresh_from_db()
        self.assertEqual(garmin.primary_activity_id, other_primary.id)

    def test_undo_batch_rejects_undoing_the_same_batch_twice(self):
        zwift = self._bike(self.athlete, device="Zwift", start_date=self.start)
        garmin = self._bike(self.athlete, device="Garmin", start_date=self.start + timedelta(seconds=5))
        result = resolve_duplicates_batch(self.athlete, "mark", pairs=[[garmin.id, zwift.id]], dry_run=False)
        undo_batch(self.athlete, result["batch_id"])

        with self.assertRaises(ValidationError):
            undo_batch(self.athlete, result["batch_id"])

    def test_undo_batch_rejects_another_athletes_batch(self):
        zwift = self._bike(self.athlete, device="Zwift", start_date=self.start)
        garmin = self._bike(self.athlete, device="Garmin", start_date=self.start + timedelta(seconds=5))
        result = resolve_duplicates_batch(self.athlete, "mark", pairs=[[garmin.id, zwift.id]], dry_run=False)

        with self.assertRaises(Http404):
            undo_batch(self.other_athlete, result["batch_id"])

    def test_undo_batch_leaves_a_merge_skipped_low_score_entry_alone(self):
        # merge_skipped_low_score matches neither undo_batch filter (marked nor merged) -
        # nothing to revert or skip, same as a plain flagged entry would be.
        zwift = self._bike(self.athlete, device="Zwift", start_date=self.start)
        garmin = self._bike(self.athlete, device="Garmin", start_date=self.start + timedelta(seconds=5))
        result = resolve_duplicates_batch(self.athlete, "merge", pairs=[[garmin.id, zwift.id]], dry_run=False)

        undo_result = undo_batch(self.athlete, result["batch_id"])

        self.assertEqual(undo_result["reverted_count"], 0)
        self.assertEqual(undo_result["skipped_count"], 0)
