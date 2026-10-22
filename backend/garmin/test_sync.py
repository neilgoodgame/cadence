import io
import json
import threading
import zipfile
from datetime import UTC, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

from django.test import TestCase, override_settings
from rest_framework.test import APIClient

from accounts.models import User
from activities.models import Activity
from authn.oauth_utils import issue_token_pair
from gear.models import Shoe, ShoeModel, ShoeModelVersion
from uploads.models import Upload, UploadBatch

from . import crypto
from .models import GarminConnection, GarminSyncRun

KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
GOOD_KEY = "garmin-sync-test-key-0001"
FIXTURES = Path(__file__).resolve().parent.parent / "uploads" / "tests_fixtures"
# activity id -> (start time GMT, fixture). Garmin lists newest first.
ACTIVITIES = {
    103: ("2026-10-03 10:00:00", "running_treadmill.fit"),
    102: ("2026-10-02 10:00:00", "running_outdoor_marathon.fit"),
    101: ("2026-10-01 10:00:00", "cycling_indoor.fit"),
}


class _StubGarmin(BaseHTTPRequestHandler):
    def log_message(self, *args):  # keep test output quiet
        pass

    def _send(self, code, body: bytes, content_type="text/plain"):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        ok = self.headers.get("Authorization") == f"ApiKey {GOOD_KEY}"
        self._send(200 if ok else 401, b"stub-jwt" if ok else b"nope")

    def do_GET(self):
        if self.path.startswith("/activitylist-service/activities/search/activities"):
            # Newest first, like Garmin, which honours `limit`.
            ids = list(ACTIVITIES)[:1] if "limit=1" in self.path else list(ACTIVITIES)
            page = (
                [{"activityId": i, "startTimeGMT": ACTIVITIES[i][0], "activityName": "x"} for i in ids]
                if "start=0" in self.path
                else []
            )
            self._send(200, json.dumps(page).encode(), "application/json")
        elif self.path.startswith("/download-service/files/activity/"):
            activity_id = int(self.path.rsplit("/", 1)[-1])
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, "w") as archive:
                archive.writestr(f"{activity_id}_ACTIVITY.fit", (FIXTURES / ACTIVITIES[activity_id][1]).read_bytes())
            self._send(200, buffer.getvalue(), "application/zip")
        else:
            self._send(404, b"")


def _bearer_client(user, scope="activities:read activities:write"):
    access_token, _ = issue_token_pair(user, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access_token.token}")
    return client


@patch("garmin.sync.DOWNLOAD_PAUSE_S", 0)
class GarminSyncTests(TestCase):
    @classmethod
    def setUpClass(cls):
        super().setUpClass()
        cls.server = ThreadingHTTPServer(("localhost", 0), _StubGarmin)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.overrides = override_settings(
            GARMIN_ENCRYPTION_KEY=KEY,
            GARMIN_API_BASE_URL=f"http://localhost:{cls.server.server_address[1]}",
        )
        cls.overrides.enable()

    @classmethod
    def tearDownClass(cls):
        cls.overrides.disable()
        cls.server.shutdown()
        cls.server.server_close()
        super().tearDownClass()

    def _connected(self, email, key=GOOD_KEY):
        athlete = User.objects.create_user(email=email, password="x", name="Athlete")
        GarminConnection.objects.create(
            owner=athlete, garmin_email=email, api_key_encrypted=crypto.encrypt(key, athlete.id)
        )
        return athlete, _bearer_client(athlete)

    def _batch_counts(self, client, response):
        return client.get(response["Location"]).json()["counts"]

    def test_imports_through_the_real_pipeline_and_a_second_sync_skips_everything(self):
        athlete, client = self._connected("garmin-sync-happy@example.cc")

        first = client.post("/v1/garmin/sync", {}, format="json")
        self.assertEqual(first.status_code, 202)
        body = first.json()
        self.assertEqual(body["object"], "garmin_sync")
        self.assertEqual((body["found"], body["requested"], body["failed"], body["truncated"]), (3, 3, 0, False))
        self.assertEqual(body["batch"]["object"], "upload_batch")
        counts = self._batch_counts(client, first)
        self.assertEqual((counts["total"], counts["ready"], counts["duplicate"]), (3, 3, 0))
        self.assertIsNotNone(GarminConnection.objects.get(owner=athlete).last_synced_at)

        # Every id is now known, so nothing is downloaded or imported - not even as duplicates.
        second = client.post("/v1/garmin/sync", {}, format="json")
        self.assertEqual(second.status_code, 202)
        body = second.json()
        self.assertEqual((body["found"], body["already_synced"], body["requested"]), (3, 3, 0))
        self.assertIsNone(body["batch"])

    def test_a_deleted_activity_is_not_brought_back_by_the_next_sync(self):
        athlete, client = self._connected("garmin-sync-deleted@example.cc")
        first = client.post("/v1/garmin/sync", {}, format="json")
        batch = UploadBatch.objects.get(pk=first.json()["batch"]["id"])
        activities = [u.activity for u in batch.uploads.select_related("activity")]
        self.assertEqual(len(activities), 3)
        for activity in activities:
            activity.delete()

        body = client.post("/v1/garmin/sync", {}, format="json").json()

        self.assertEqual((body["already_synced"], body["requested"]), (3, 0))
        self.assertIsNone(body["batch"])
        self.assertFalse(Activity.objects.filter(athlete=athlete).exists())

    def test_history_records_what_each_run_synced_and_the_connection_exposes_the_latest(self):
        _, client = self._connected("garmin-sync-history@example.cc")
        client.post("/v1/garmin/sync", {}, format="json")

        runs = client.get("/v1/garmin/syncs").json()["data"]

        self.assertEqual(len(runs), 1)
        run = runs[0]
        self.assertEqual(run["found"], 3)
        self.assertIsNone(run["error"])
        self.assertEqual(run["batch"]["counts"]["ready"], 3)
        self.assertEqual(len(run["activities"]), 3)
        first = run["activities"][0]  # oldest first, each resolved to its imported Cadence activity
        self.assertEqual(first["garmin_activity_id"], 101)
        self.assertEqual(first["upload_status"], "ready")
        self.assertIsNotNone(first["activity_id"])
        self.assertIsNotNone(first["sport"])

        connection = client.get("/v1/garmin/connection").json()
        self.assertIsNotNone(connection["last_run_at"])
        self.assertEqual(connection["last_activity"]["garmin_activity_id"], 103)
        self.assertTrue(connection["last_activity"]["start_time"].startswith("2026-10-03T10:00:00"))

    def test_a_failed_run_is_recorded_with_its_reason_but_never_the_key(self):
        _, client = self._connected("garmin-sync-failedrun@example.cc", key="garmin-sync-WRONG-key-9999")
        self.assertEqual(client.post("/v1/garmin/sync", {}, format="json").status_code, 422)

        history = client.get("/v1/garmin/syncs")

        runs = history.json()["data"]
        self.assertEqual(len(runs), 1)
        self.assertIn("rejected", runs[0]["error"])
        self.assertIsNone(runs[0]["batch"])
        self.assertNotIn("WRONG-key", history.content.decode())

    def test_a_worker_interrupt_mid_sync_closes_the_run_instead_of_leaving_it_in_flight(self):
        _, client = self._connected("garmin-sync-interrupt@example.cc")
        with patch("garmin.sync._do_sync", side_effect=SystemExit(1)):
            with self.assertRaises(SystemExit):
                client.post("/v1/garmin/sync", {}, format="json")

        run = client.get("/v1/garmin/syncs").json()["data"][0]

        self.assertIsNotNone(run["finished_at"])
        self.assertIn("interrupted", run["error"])

    def test_only_the_most_recent_fifty_runs_are_kept(self):
        athlete, client = self._connected("garmin-sync-prune@example.cc")
        base = datetime(2026, 1, 1, tzinfo=UTC)
        for i in range(52):
            GarminSyncRun.objects.create(owner=athlete, started_at=base + timedelta(seconds=i))

        client.post("/v1/garmin/sync", {}, format="json")

        self.assertEqual(GarminSyncRun.objects.filter(owner=athlete).count(), 50)

    def test_a_limit_takes_the_oldest_and_holds_the_marker_at_the_last_one_taken(self):
        athlete, client = self._connected("garmin-sync-limit@example.cc")

        response = client.post("/v1/garmin/sync", {"limit": 2}, format="json")

        self.assertEqual(response.status_code, 202)
        body = response.json()
        self.assertEqual((body["found"], body["requested"], body["truncated"]), (3, 2, True))
        self.assertEqual(
            GarminConnection.objects.get(owner=athlete).last_synced_at, datetime(2026, 10, 2, 10, 0, tzinfo=UTC)
        )

    def test_a_limit_above_the_hard_maximum_is_rejected(self):
        _, client = self._connected("garmin-sync-cap@example.cc")
        self.assertEqual(client.post("/v1/garmin/sync", {"limit": 101}, format="json").status_code, 400)
        self.assertEqual(client.post("/v1/garmin/sync", {"limit": 0}, format="json").status_code, 400)

    def test_the_athletes_saved_sync_limit_is_the_default_for_a_run(self):
        athlete, client = self._connected("garmin-sync-savedlimit@example.cc")
        GarminConnection.objects.filter(owner=athlete).update(sync_limit=2)

        body = client.post("/v1/garmin/sync", {}, format="json").json()

        self.assertEqual((body["requested"], body["truncated"]), (2, True))

    def test_latest_syncs_only_the_newest_activity_with_its_session_details_and_leaves_the_window_alone(self):
        athlete, client = self._connected("garmin-sync-latest@example.cc")
        model = ShoeModel.objects.create(manufacturer="GarminSyncCo", model="Runner")
        version = ShoeModelVersion.objects.create(shoe_model=model, version="v1")
        shoe = Shoe.objects.create(athlete=athlete, shoe_model_version=version, name="Garmin sync trainer")

        response = client.post(
            "/v1/garmin/sync",
            {
                "latest": True,
                "weight_before_kg": 72.4,
                "weight_after_kg": 71.0,
                "fluids_ml": 750,
                "shoe_id": shoe.id,
            },
            format="json",
        )

        self.assertEqual(response.status_code, 202)
        body = response.json()
        self.assertEqual((body["found"], body["requested"], body["truncated"]), (1, 1, False))
        upload = UploadBatch.objects.get(pk=body["batch"]["id"]).uploads.get()
        self.assertEqual(upload.filename, "103_ACTIVITY.fit")
        self.assertEqual(self._batch_counts(client, response)["ready"], 1)
        activity = Activity.objects.get(pk=Upload.objects.get(pk=upload.pk).activity_id)
        self.assertEqual((activity.start_weight_kg, activity.end_weight_kg, activity.fluids_ml), (72.4, 71.0, 750))
        self.assertIsNone(GarminConnection.objects.get(owner=athlete).last_synced_at)

        # The same activity again is skipped; a normal sync then picks up only the two older ones.
        again = client.post("/v1/garmin/sync", {"latest": True}, format="json").json()
        self.assertEqual((again["already_synced"], again["requested"], again["batch"]), (1, 0, None))
        rest = client.post("/v1/garmin/sync", {}, format="json").json()
        self.assertEqual((rest["found"], rest["already_synced"], rest["requested"]), (3, 1, 2))

    def test_session_details_without_latest_are_rejected(self):
        _, client = self._connected("garmin-sync-detailsonly@example.cc")
        self.assertEqual(client.post("/v1/garmin/sync", {"weight_before_kg": 72.4}, format="json").status_code, 400)

    def test_an_unknown_shoe_is_rejected_before_any_run_is_recorded(self):
        athlete, client = self._connected("garmin-sync-badshoe@example.cc")
        response = client.post("/v1/garmin/sync", {"latest": True, "shoe_id": "shoe_nope"}, format="json")
        self.assertEqual(response.status_code, 400)
        self.assertFalse(GarminSyncRun.objects.filter(owner=athlete).exists())

    def test_a_future_since_date_is_rejected(self):
        _, client = self._connected("garmin-sync-future@example.cc")
        since = (datetime.now(UTC).date() + timedelta(days=2)).isoformat()
        self.assertEqual(client.post("/v1/garmin/sync", {"since": since}, format="json").status_code, 400)

    def test_without_a_connection_it_is_404(self):
        athlete = User.objects.create_user(email="garmin-sync-noconn@example.cc", password="x", name="Athlete")
        self.assertEqual(_bearer_client(athlete).post("/v1/garmin/sync", {}, format="json").status_code, 404)

    def test_an_expired_key_is_409(self):
        athlete, client = self._connected("garmin-sync-expired@example.cc")
        GarminConnection.objects.filter(owner=athlete).update(key_expires_on=datetime.now(UTC).date() - timedelta(1))
        self.assertEqual(client.post("/v1/garmin/sync", {}, format="json").status_code, 409)

    def test_a_key_garmin_rejects_is_422_and_the_key_is_never_echoed(self):
        athlete, client = self._connected("garmin-sync-badkey@example.cc", key="garmin-sync-WRONG-key-9999")

        response = client.post("/v1/garmin/sync", {}, format="json")

        self.assertEqual(response.status_code, 422)
        self.assertNotIn("WRONG-key", response.content.decode())
        self.assertIsNone(GarminConnection.objects.get(owner=athlete).last_synced_at)
