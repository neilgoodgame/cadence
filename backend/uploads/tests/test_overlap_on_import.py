from datetime import UTC, datetime

from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import TestCase

from accounts.models import User
from activities.models import Activity

from ..fixtures_helpers import build_tcx, build_zip
from .helpers import _bearer_client


class OverlapOnImportTests(TestCase):
    """End-to-end coverage for issue #498's on-ingest side: the `on_overlap` request param on
    POST /v1/activities/batch -> services.create_batch_from_files resolving it against the
    athlete's `overlap_handling` default -> processing.ingest_upload running
    activities.overlap_detection for each newly-created activity -> the result landing on the
    Upload row. The detection and primary-selection logic itself is covered directly (and far
    more cheaply) by activities/tests/test_overlap_detection.py; this only exists to prove the
    wiring between the HTTP-adjacent layer and that module actually happens."""

    def setUp(self):
        self.start = datetime(2026, 6, 1, 18, 0, 0, tzinfo=UTC)

    def _overlapping_zip(self):
        # Identical start time and duration on both files - the simplest possible full overlap.
        content_a = build_tcx(self.start, duration_s=60, power=200)
        content_b = build_tcx(self.start, duration_s=60, power=205, hr=150)
        return build_zip({"a.tcx": content_a, "b.tcx": content_b})

    def test_on_overlap_mark_on_the_request_marks_one_copy_as_a_duplicate_of_the_other(self):
        # The athlete's own default is off - the request's on_overlap must be what actually
        # drives this, not a coincidentally-matching default.
        athlete = User.objects.create_user(email="e2e-overlap-mark@example.cc", password="x", overlap_handling="off")
        client = _bearer_client(athlete)

        response = client.post(
            "/v1/activities/batch",
            {"file": SimpleUploadedFile("dupes.zip", self._overlapping_zip()), "on_overlap": "mark"},
            format="multipart",
        )
        self.assertEqual(response.status_code, 202)
        batch = client.get(f"/v1/uploads/batches/{response.json()['id']}").json()
        self.assertEqual(batch["status"], "completed")

        activities = list(Activity.objects.filter(athlete=athlete).order_by("id"))
        self.assertEqual(len(activities), 2)
        primaries = [a for a in activities if a.primary_activity_id is None]
        duplicates = [a for a in activities if a.primary_activity_id is not None]
        self.assertEqual(len(primaries), 1)
        self.assertEqual(len(duplicates), 1)
        self.assertEqual(duplicates[0].primary_activity_id, primaries[0].id)

        with_action = [u for u in batch["uploads"] if u["overlap_action"] is not None]
        self.assertEqual(len(with_action), 1)
        self.assertEqual(with_action[0]["overlap_action"], "marked")
        self.assertIsNotNone(with_action[0]["overlap_other_activity_id"])

    def test_on_overlap_off_on_the_request_skips_detection_even_when_the_athlete_default_is_mark(self):
        athlete = User.objects.create_user(
            email="e2e-overlap-off-override@example.cc", password="x", overlap_handling="mark"
        )
        client = _bearer_client(athlete)

        response = client.post(
            "/v1/activities/batch",
            {"file": SimpleUploadedFile("dupes.zip", self._overlapping_zip()), "on_overlap": "off"},
            format="multipart",
        )
        batch = client.get(f"/v1/uploads/batches/{response.json()['id']}").json()

        for upload in batch["uploads"]:
            self.assertIsNone(upload["overlap_action"])
        for activity in Activity.objects.filter(athlete=athlete):
            self.assertIsNone(activity.primary_activity_id)

    def test_no_request_override_falls_back_to_the_athletes_own_overlap_handling_default(self):
        athlete = User.objects.create_user(
            email="e2e-overlap-athlete-default@example.cc", password="x", overlap_handling="flag"
        )
        client = _bearer_client(athlete)

        # No on_overlap in the request at all - mirrors garmin/sync.py's call site, which has no
        # per-sync override either.
        response = client.post(
            "/v1/activities/batch",
            {"file": SimpleUploadedFile("dupes.zip", self._overlapping_zip())},
            format="multipart",
        )
        batch = client.get(f"/v1/uploads/batches/{response.json()['id']}").json()

        with_action = [u for u in batch["uploads"] if u["overlap_action"] is not None]
        self.assertEqual(len(with_action), 1)
        # FLAG never writes to the activity, even though a pair was found.
        self.assertEqual(with_action[0]["overlap_action"], "flagged")
        for activity in Activity.objects.filter(athlete=athlete):
            self.assertIsNone(activity.primary_activity_id)
