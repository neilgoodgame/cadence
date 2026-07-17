from datetime import UTC, datetime, timedelta
from unittest.mock import patch

from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import TestCase

from accounts.models import User
from activities.models import Activity

from ..fixtures_helpers import build_gpx, build_tcx, build_zip
from .helpers import _bearer_client, _delegated_client


class ActivityBatchUploadViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_batch_upload_processes_all_files(self):
        start = datetime(2026, 6, 4, 7, 0, tzinfo=UTC)
        zip_bytes = build_zip(
            {
                "ride1.tcx": build_tcx(start, duration_s=30, power=180),
                "ride2.tcx": build_tcx(start + timedelta(hours=2), duration_s=30, power=190),
            }
        )
        client = _bearer_client(self.athlete)
        response = client.post(
            "/v1/activities/batch", {"file": SimpleUploadedFile("rides.zip", zip_bytes)}, format="multipart"
        )
        self.assertEqual(response.status_code, 202)
        body = response.json()
        self.assertEqual(body["object"], "upload_batch")
        batch_id = body["id"]
        self.assertEqual(response["Location"], f"/v1/uploads/batches/{batch_id}")

        poll = client.get(f"/v1/uploads/batches/{batch_id}")
        poll_body = poll.json()
        self.assertEqual(poll_body["status"], "completed")
        self.assertEqual(poll_body["counts"], {"total": 2, "ready": 2, "processing": 0, "failed": 0, "duplicate": 0})
        self.assertEqual(poll_body["progress"], 1.0)
        self.assertEqual(Activity.objects.filter(athlete=self.athlete).count(), 2)

    def test_duplicate_file_within_batch_is_skipped(self):
        start = datetime(2026, 6, 5, 7, 0, tzinfo=UTC)
        content = build_tcx(start, duration_s=20, power=150)
        zip_bytes = build_zip({"a.tcx": content, "b.tcx": content})
        client = _bearer_client(self.athlete)

        response = client.post(
            "/v1/activities/batch",
            {"file": SimpleUploadedFile("dupes.zip", zip_bytes), "on_duplicate": "skip"},
            format="multipart",
        )
        batch_id = response.json()["id"]
        poll = client.get(f"/v1/uploads/batches/{batch_id}").json()

        self.assertEqual(poll["counts"]["ready"], 1)
        self.assertEqual(poll["counts"]["duplicate"], 1)
        self.assertEqual(Activity.objects.filter(athlete=self.athlete).count(), 1)

        statuses = {u["filename"]: u["status"] for u in poll["uploads"]}
        self.assertEqual(statuses["a.tcx"], "ready")
        self.assertEqual(statuses["b.tcx"], "duplicate")

    def test_on_duplicate_replace_recreates_activity(self):
        start = datetime(2026, 6, 6, 7, 0, tzinfo=UTC)
        content = build_tcx(start, duration_s=20, power=160)
        client = _bearer_client(self.athlete)

        first = client.post(
            "/v1/activities/batch",
            {"file": SimpleUploadedFile("first.zip", build_zip({"ride.tcx": content}))},
            format="multipart",
        )
        first_batch = client.get(f"/v1/uploads/batches/{first.json()['id']}").json()
        original_activity_id = first_batch["uploads"][0]["activity_id"]
        self.assertIsNotNone(original_activity_id)

        second = client.post(
            "/v1/activities/batch",
            {
                "file": SimpleUploadedFile("second.zip", build_zip({"ride.tcx": content})),
                "on_duplicate": "replace",
            },
            format="multipart",
        )
        second_batch = client.get(f"/v1/uploads/batches/{second.json()['id']}").json()
        new_activity_id = second_batch["uploads"][0]["activity_id"]

        self.assertNotEqual(new_activity_id, original_activity_id)
        self.assertFalse(Activity.objects.filter(pk=original_activity_id).exists())
        self.assertTrue(Activity.objects.filter(pk=new_activity_id).exists())
        self.assertEqual(Activity.objects.filter(athlete=self.athlete).count(), 1)

    def test_all_duplicates_batch_completes_and_fires_webhook(self):
        start = datetime(2026, 6, 5, 9, 0, tzinfo=UTC)
        content = build_tcx(start, duration_s=20, power=150)
        client = _bearer_client(self.athlete)
        client.post(
            "/v1/activities/batch",
            {"file": SimpleUploadedFile("first.zip", build_zip({"ride.tcx": content}))},
            format="multipart",
        )

        with patch("uploads.tasks.fire_event") as mock_fire:
            response = client.post(
                "/v1/activities/batch",
                {"file": SimpleUploadedFile("again.zip", build_zip({"ride.tcx": content})), "on_duplicate": "skip"},
                format="multipart",
            )

        body = response.json()
        self.assertEqual(body["status"], "completed")
        self.assertEqual(body["counts"], {"total": 1, "ready": 0, "processing": 0, "failed": 0, "duplicate": 1})
        events = [call.args[0] for call in mock_fire.call_args_list]
        self.assertIn("upload_batch.completed", events)

    def test_bad_zip_returns_400(self):
        response = _bearer_client(self.athlete).post(
            "/v1/activities/batch", {"file": SimpleUploadedFile("bad.zip", b"not a zip")}, format="multipart"
        )
        self.assertEqual(response.status_code, 400)

    def test_zip_with_no_supported_files_returns_400(self):
        zip_bytes = build_zip({"readme.txt": b"hello"})
        response = _bearer_client(self.athlete).post(
            "/v1/activities/batch", {"file": SimpleUploadedFile("empty.zip", zip_bytes)}, format="multipart"
        )
        self.assertEqual(response.status_code, 400)

    def test_batch_file_count_limit(self):
        zip_bytes = build_zip({f"f{i}.gpx": b"" for i in range(10001)})
        response = _bearer_client(self.athlete).post(
            "/v1/activities/batch", {"file": SimpleUploadedFile("toomany.zip", zip_bytes)}, format="multipart"
        )
        self.assertEqual(response.status_code, 400)

    def test_outsider_cannot_batch_upload(self):
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        zip_bytes = build_zip({"a.tcx": build_tcx(datetime(2026, 6, 7, 7, 0, tzinfo=UTC), duration_s=5)})
        response = client.post(
            "/v1/activities/batch", {"file": SimpleUploadedFile("x.zip", zip_bytes)}, format="multipart"
        )
        self.assertEqual(response.status_code, 403)


class UploadPollingPermissionTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_outsider_cannot_poll_upload(self):
        content = build_gpx(datetime(2026, 6, 8, 7, 0, tzinfo=UTC), duration_s=5)
        client = _bearer_client(self.athlete)
        upload_id = client.post(
            "/v1/activities", {"file": SimpleUploadedFile("a.gpx", content)}, format="multipart"
        ).json()["id"]

        outsider_client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = outsider_client.get(f"/v1/uploads/{upload_id}")
        self.assertEqual(response.status_code, 403)

    def test_outsider_cannot_poll_batch(self):
        zip_bytes = build_zip({"a.tcx": build_tcx(datetime(2026, 6, 9, 7, 0, tzinfo=UTC), duration_s=5)})
        client = _bearer_client(self.athlete)
        batch_id = client.post(
            "/v1/activities/batch", {"file": SimpleUploadedFile("z.zip", zip_bytes)}, format="multipart"
        ).json()["id"]

        outsider_client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = outsider_client.get(f"/v1/uploads/batches/{batch_id}")
        self.assertEqual(response.status_code, 403)
