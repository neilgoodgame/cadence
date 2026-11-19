"""Issue #501 (phase 1, part 5): mirrors backend_java's ActivityUnmergeServiceIntegrationTest."""

from datetime import UTC, datetime, timedelta

from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import TestCase

from accounts.models import User
from core.exceptions import UnprocessableEntityError
from uploads.fixtures_helpers import build_tcx
from uploads.tests.helpers import _bearer_client as _upload_bearer_client

from ..merge_service import run_merge
from ..models import Activity, Merge, Record
from ..unmerge_service import undo_merge
from .helpers import _make_activity


class UnmergeServiceTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="unmerge@example.cc", password="x", name="Athlete", ftp=200)
        self.start = datetime(2026, 6, 1, 18, 0, 0, tzinfo=UTC)

    def _uploaded_target(self, power: int = 100, duration_s: int = 10) -> Activity:
        """A real, ingested-from-a-file activity - unlike _make_activity, this has a genuine
        Upload row with a stored_path, so undo_merge can actually re-parse it."""
        content = build_tcx(self.start, sport="Biking", duration_s=duration_s, power=power, hr=140)
        client = _upload_bearer_client(self.athlete)
        response = client.post("/v1/activities", {"file": SimpleUploadedFile("ride.tcx", content)}, format="multipart")
        upload_id = response.json()["id"]
        activity_id = client.get(f"/v1/uploads/{upload_id}").json()["activity_id"]
        return Activity.objects.get(pk=activity_id)

    def _source_with_records(self, power: int = 250, count: int = 10) -> Activity:
        source = _make_activity(self.athlete, sport="bike", name="Source", start_date=self.start)
        for t in range(count):
            Record.objects.create(activity=source, t=t, ts=source.start_date + timedelta(seconds=t), power=power)
        return source

    def test_restores_stream_and_activity_level_fields_and_marks_the_merge_undone(self):
        target = self._uploaded_target(power=100)
        original_name = target.name
        source = self._source_with_records(power=250)

        result = run_merge(target.id, source.id, ["power", "name"], "overwrite", offset_seconds=0)
        target.refresh_from_db()
        self.assertEqual(target.avg_power, 250)
        self.assertEqual(target.name, "Source")

        undo_merge(target.id)

        target.refresh_from_db()
        self.assertEqual([r.power for r in target.records.order_by("t")], [100] * 10)
        self.assertEqual(target.avg_power, 100)
        self.assertEqual(target.name, original_name)
        merge = Merge.objects.get(pk=result["merge_id"])
        self.assertIsNotNone(merge.undone_at)

    def test_unhides_the_source_when_the_merge_had_hidden_it(self):
        target = self._uploaded_target(power=100)
        source = self._source_with_records(power=250)

        run_merge(target.id, source.id, ["power"], "overwrite", offset_seconds=0, hide_source=True)
        source.refresh_from_db()
        self.assertEqual(source.primary_activity_id, target.id)

        undo_merge(target.id)

        source.refresh_from_db()
        self.assertIsNone(source.primary_activity_id)

    def test_raises_when_there_is_no_active_merge(self):
        target = self._uploaded_target(power=100)

        with self.assertRaises(UnprocessableEntityError):
            undo_merge(target.id)

    def test_raises_when_the_target_has_no_stored_upload_file(self):
        target = _make_activity(self.athlete, sport="bike", name="No file", start_date=self.start)
        for t in range(5):
            Record.objects.create(activity=target, t=t, ts=target.start_date + timedelta(seconds=t), power=100)
        source = self._source_with_records(power=250, count=5)
        run_merge(target.id, source.id, ["power"], "overwrite", offset_seconds=0)

        with self.assertRaises(UnprocessableEntityError):
            undo_merge(target.id)

    def test_blocks_undo_when_more_than_one_merge_is_active(self):
        target = self._uploaded_target(power=100)
        source_a = self._source_with_records(power=250)
        source_b = self._source_with_records(power=300)

        run_merge(target.id, source_a.id, ["power"], "overwrite", offset_seconds=0)
        run_merge(target.id, source_b.id, ["power"], "overwrite", offset_seconds=0)

        with self.assertRaises(UnprocessableEntityError):
            undo_merge(target.id)

    def test_undoes_a_non_primary_attach_by_deleting_the_source_without_touching_record(self):
        """Issue #542: an attach that never became primary is harmless while attached - undo
        just removes it, no Record/stats change."""
        target = self._uploaded_target(power=100)
        source = self._source_with_records(power=250)

        result = run_merge(target.id, source.id, ["power"], None, offset_seconds=0, copy_mode="attach")
        merge = Merge.objects.get(pk=result["merge_id"])
        self.assertEqual(merge.copy_mode, "attach")
        self.assertIsNone(merge.demoted_source_id)  # bike_power_source default is "any" - no auto-swap

        undo_merge(target.id)

        target.refresh_from_db()
        self.assertEqual([r.power for r in target.records.order_by("t")], [100] * 10)
        self.assertFalse(target.sources.filter(pk=merge.attached_source_id).exists())
        merge.refresh_from_db()
        self.assertIsNotNone(merge.undone_at)

    def test_undoes_a_promoted_attach_by_restoring_the_prior_primary_stream(self):
        """Issue #542: when the attach auto-promoted to primary (the athlete's sensor preference
        matched), undo must swap Record back to the pre-merge values, not just delete a row."""
        self.athlete.bike_power_source = "pedals"
        self.athlete.save(update_fields=["bike_power_source"])
        target = self._uploaded_target(power=100)
        target.device = "Zwift"  # classify() -> "trainer" - current primary isn't "pedals" yet
        target.save(update_fields=["device"])
        source = self._source_with_records(power=250)
        source.device = "Wahoo"  # classify() -> "pedals", matches the preference -> auto-swap
        source.save(update_fields=["device"])

        result = run_merge(target.id, source.id, ["power"], None, offset_seconds=0, copy_mode="attach")
        merge = Merge.objects.get(pk=result["merge_id"])
        self.assertIsNotNone(merge.demoted_source_id)
        target.refresh_from_db()
        self.assertEqual([r.power for r in target.records.order_by("t")], [250] * 10)

        undo_merge(target.id)

        target.refresh_from_db()
        self.assertEqual([r.power for r in target.records.order_by("t")], [100] * 10)
        self.assertFalse(target.sources.filter(pk=merge.attached_source_id).exists())
        merge.refresh_from_db()
        self.assertIsNotNone(merge.undone_at)

    def test_merge_id_disambiguates_between_two_simultaneous_merges(self):
        """Issue #542: an overwrite merge on one field and an attach merge on another can now
        both be active at once - undo must be told which one via merge_id."""
        target = self._uploaded_target(power=100)
        overwrite_source = self._source_with_records(power=250)
        attach_source = self._source_with_records(power=300)

        overwrite_result = run_merge(target.id, overwrite_source.id, ["name"], "overwrite", offset_seconds=0)
        attach_result = run_merge(target.id, attach_source.id, ["power"], None, offset_seconds=0, copy_mode="attach")

        with self.assertRaises(UnprocessableEntityError):
            undo_merge(target.id)

        undo_merge(target.id, merge_id=attach_result["merge_id"])
        self.assertIsNone(Merge.objects.get(pk=overwrite_result["merge_id"]).undone_at)
        self.assertIsNotNone(Merge.objects.get(pk=attach_result["merge_id"]).undone_at)
