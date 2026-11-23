"""Issue #501 (phase 1, part 4): the POST .../merge endpoint, backed by merge_service.run_merge.
Mirrors backend_java's ActivityMergeControllerIntegrationTest."""

from datetime import UTC, datetime, timedelta
from unittest.mock import patch

from django.test import TestCase

from accounts.models import User

from .. import activity_source_service, stream_patch_service
from ..models import Merge, Record, StreamPatch
from .helpers import _bearer_client, _make_activity


class MergeServiceTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="merge-service@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(
            email="merge-service-outsider@example.cc", password="x", name="Outsider"
        )
        self.start = datetime(2026, 6, 1, 18, 0, 0, tzinfo=UTC)

    def _activity(self, athlete, **kwargs):
        return _make_activity(athlete, sport="bike", start_date=self.start, moving_time=600, **kwargs)

    def _add_records(self, activity, count, power):
        for t in range(count):
            Record.objects.create(activity=activity, t=t, ts=activity.start_date + timedelta(seconds=t), power=power)

    def _add_records_with(self, activity, count, **field_fns):
        """field_fns: field_name -> callable(t) -> value - issue #541's dropout tests need
        per-second power *and* cadence control the constant-value helper above doesn't give."""
        for t in range(count):
            Record.objects.create(
                activity=activity,
                t=t,
                ts=activity.start_date + timedelta(seconds=t),
                **{field: fn(t) for field, fn in field_fns.items()},
            )

    def test_merges_and_records_an_audit_row(self):
        target = self._activity(self.athlete, name="Target")
        source = self._activity(self.athlete, name="Source")
        self._add_records(target, 10, power=100)
        self._add_records(source, 10, power=250)

        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{target.id}/merge",
            {"source_id": source.id, "fields": ["power"], "null_policy": "keep_original", "offset_seconds": 0},
            format="json",
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertIsNotNone(body["merge_id"])
        self.assertEqual(body["offset_seconds"], 0)
        self.assertEqual(body["field_counts"]["power"]["copied"], 10)
        self.assertEqual(body["after"]["avg_power"], 250)

        self.assertEqual(Merge.objects.count(), 1)
        merge = Merge.objects.get(pk=body["merge_id"])
        self.assertEqual(merge.target_activity_id, target.id)
        self.assertEqual(merge.source_activity_id, source.id)

        target.refresh_from_db()
        self.assertEqual(target.avg_power, 250)

    def test_an_overwrite_merge_invalidates_an_active_patch_on_the_same_field(self):
        """Code review finding: apply_merge_fields' own Record rewrite had no awareness of an
        active StreamPatch on the same field - a later unpatch_stream would have restored the
        patch's now-stale previous_values right back over the merge's legitimate result. The
        merge now invalidates (undone_at, not restored) any active patch on every field it
        overwrites."""
        target = self._activity(self.athlete, name="Target")
        reference = self._activity(self.athlete, name="Reference")
        self._add_records_with(target, 10, power=lambda t: 0 if t in (4, 5) else 200)
        self._add_records(reference, 10, power=220)
        reference_attach = activity_source_service.attach_stream(
            target.id, reference.id, "power", offset_seconds=0, preview=False
        )
        patch = stream_patch_service.patch_stream(
            target.id, "power", reference_attach.source_id, segments=[(4, 5)], preview=False
        )

        source = self._activity(self.athlete, name="Source")
        self._add_records(source, 10, power=300)

        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{target.id}/merge",
            {"source_id": source.id, "fields": ["power"], "null_policy": "keep_original", "offset_seconds": 0},
            format="json",
        )

        self.assertEqual(response.status_code, 200)
        saved_patch = StreamPatch.objects.get(pk=patch.patch_id)
        self.assertIsNotNone(saved_patch.undone_at)
        for record in target.records.all():
            self.assertEqual(record.power, 300)  # the merge's data, not the patch's restored snapshot

    def test_preview_has_no_side_effects(self):
        target = self._activity(self.athlete, name="Target")
        source = self._activity(self.athlete, name="Source")
        self._add_records(target, 10, power=100)
        self._add_records(source, 10, power=250)

        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{target.id}/merge",
            {
                "source_id": source.id,
                "fields": ["power"],
                "null_policy": "keep_original",
                "offset_seconds": 0,
                "preview": True,
            },
            format="json",
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertIsNone(body["merge_id"])
        self.assertEqual(body["after"]["avg_power"], 250)  # the preview still reports the would-be outcome

        self.assertEqual(Merge.objects.count(), 0)
        target.refresh_from_db()
        self.assertIsNone(target.avg_power)  # nothing actually written
        self.assertEqual([r.power for r in target.records.order_by("t")], [100] * 10)

    def test_hides_source_as_a_duplicate_when_requested(self):
        target = self._activity(self.athlete, name="Target")
        source = self._activity(self.athlete, name="Source")
        self._add_records(target, 10, power=100)
        self._add_records(source, 10, power=250)

        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{target.id}/merge",
            {
                "source_id": source.id,
                "fields": ["power"],
                "null_policy": "keep_original",
                "offset_seconds": 0,
                "hide_source": True,
            },
            format="json",
        )

        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json()["source_hidden"])
        source.refresh_from_db()
        self.assertEqual(source.primary_activity_id, target.id)

    def test_merges_when_the_source_is_already_hidden_under_this_target(self):
        target = self._activity(self.athlete, name="Target")
        source = self._activity(self.athlete, name="Source")
        self._add_records(target, 10, power=100)
        self._add_records(source, 10, power=250)
        source.primary_activity = target  # e.g. auto-marked at import by on_overlap=mark
        source.save(update_fields=["primary_activity"])

        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{target.id}/merge",
            {
                "source_id": source.id,
                "fields": ["power"],
                "null_policy": "keep_original",
                "offset_seconds": 0,
                "hide_source": True,
            },
            format="json",
        )

        self.assertEqual(response.status_code, 200)
        source.refresh_from_db()
        self.assertEqual(source.primary_activity_id, target.id)
        # The merge didn't hide anything itself, so undoing it must not un-hide the source.
        self.assertFalse(Merge.objects.get(pk=response.json()["merge_id"]).source_hidden)

    def test_still_rejects_hiding_a_source_that_is_a_duplicate_of_a_different_activity(self):
        target = self._activity(self.athlete, name="Target")
        other = self._activity(self.athlete, name="Other primary")
        source = self._activity(self.athlete, name="Source")
        source.primary_activity = other
        source.save(update_fields=["primary_activity"])

        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{target.id}/merge",
            {"source_id": source.id, "fields": ["power"], "null_policy": "keep_original", "hide_source": True},
            format="json",
        )

        self.assertEqual(response.status_code, 400)

    def test_a_preview_never_runs_the_athlete_wide_cascade(self):
        target = self._activity(self.athlete, name="Target")
        source = self._activity(self.athlete, name="Source")
        self._add_records(target, 10, power=100)
        self._add_records(source, 10, power=250)

        with (
            patch("activities.merge_service.recompute_after_merge") as cascade,
            patch("activities.merge_service.recompute_activity_level_only") as level_only,
        ):
            response = _bearer_client(self.athlete).post(
                f"/v1/activities/{target.id}/merge",
                {
                    "source_id": source.id,
                    "fields": ["power"],
                    "null_policy": "keep_original",
                    "offset_seconds": 0,
                    "preview": True,
                },
                format="json",
            )

        self.assertEqual(response.status_code, 200)
        cascade.assert_not_called()
        level_only.assert_called_once_with(target.id)

    def test_a_real_bike_merge_only_rebuilds_the_bike_threshold_field(self):
        target = self._activity(self.athlete, name="Target")
        source = self._activity(self.athlete, name="Source")
        self._add_records(target, 10, power=100)
        self._add_records(source, 10, power=250)

        with patch("activities.merge_service.recompute_after_merge") as cascade:
            response = _bearer_client(self.athlete).post(
                f"/v1/activities/{target.id}/merge",
                {"source_id": source.id, "fields": ["power"], "null_policy": "keep_original", "offset_seconds": 0},
                format="json",
            )

        self.assertEqual(response.status_code, 200)
        cascade.assert_called_once_with(target.id, ["ftp"])

    def test_rejects_an_unrecognized_field(self):
        target = self._activity(self.athlete, name="Target")
        source = self._activity(self.athlete, name="Source")

        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{target.id}/merge",
            {"source_id": source.id, "fields": ["not_a_real_field"], "null_policy": "overwrite"},
            format="json",
        )

        self.assertEqual(response.status_code, 400)

    def test_rejects_merging_an_activity_with_itself(self):
        target = self._activity(self.athlete, name="Target")

        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{target.id}/merge",
            {"source_id": target.id, "fields": ["power"], "null_policy": "overwrite"},
            format="json",
        )

        self.assertEqual(response.status_code, 400)

    def test_rejects_a_source_belonging_to_another_athlete(self):
        target = self._activity(self.athlete, name="Target")
        other_athletes_activity = self._activity(self.outsider, name="Not yours")

        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{target.id}/merge",
            {"source_id": other_athletes_activity.id, "fields": ["power"], "null_policy": "overwrite"},
            format="json",
        )

        self.assertEqual(response.status_code, 403)

    def test_zero_policy_masks_a_confirmed_dropout_by_default(self):
        target = self._activity(self.athlete, name="Target")
        source = self._activity(self.athlete, name="Source")
        self._add_records_with(target, 10, power=lambda t: 200 + t)
        self._add_records_with(
            source,
            10,
            power=lambda t: 0 if t in (4, 5) else 300 + t,
            cadence=lambda t: 85,
        )

        # zero_policy omitted entirely - confirms the documented default (as_null) applies.
        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{target.id}/merge",
            {"source_id": source.id, "fields": ["power"], "null_policy": "keep_original", "offset_seconds": 0},
            format="json",
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["field_counts"]["power"]["dropouts_nulled"], 2)
        self.assertEqual(body["field_counts"]["power"]["copied"], 8)

    def test_zero_policy_keep_copies_the_dropout_through(self):
        target = self._activity(self.athlete, name="Target")
        source = self._activity(self.athlete, name="Source")
        self._add_records_with(target, 10, power=lambda t: 200 + t)
        self._add_records_with(
            source,
            10,
            power=lambda t: 0 if t in (4, 5) else 300 + t,
            cadence=lambda t: 85,
        )

        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{target.id}/merge",
            {
                "source_id": source.id,
                "fields": ["power"],
                "null_policy": "keep_original",
                "offset_seconds": 0,
                "zero_policy": "keep",
            },
            format="json",
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["field_counts"]["power"]["dropouts_nulled"], 0)
        self.assertEqual(body["field_counts"]["power"]["copied"], 10)
