from datetime import UTC, datetime

from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import TestCase

from accounts.models import User
from activities.models import Activity, BestEffort, DurationCurve, Lap, Record

from ..fixtures_helpers import build_gpx, build_tcx
from .helpers import FIXTURES_DIR, _bearer_client, _delegated_client


class ActivityUploadViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_upload_gpx_returns_202_then_ready_on_poll(self):
        start = datetime(2026, 6, 1, 8, 0, tzinfo=UTC)
        content = build_gpx(start, sport="running", duration_s=30, hr=150)
        client = _bearer_client(self.athlete)

        response = client.post("/v1/activities", {"file": SimpleUploadedFile("run.gpx", content)}, format="multipart")
        self.assertEqual(response.status_code, 202)
        body = response.json()
        self.assertEqual(body["object"], "upload")
        self.assertEqual(body["status"], "queued")
        upload_id = body["id"]
        self.assertEqual(response["Location"], f"/v1/uploads/{upload_id}")

        poll = client.get(f"/v1/uploads/{upload_id}")
        self.assertEqual(poll.status_code, 200)
        poll_body = poll.json()
        self.assertEqual(poll_body["status"], "ready")
        self.assertIsNotNone(poll_body["activity_id"])

        activity = Activity.objects.get(pk=poll_body["activity_id"])
        self.assertEqual(activity.sport, "run")
        self.assertEqual(activity.environment, "outdoor")
        self.assertEqual(Record.objects.filter(activity=activity).count(), 30)

    def test_duplicate_upload_returns_409_referencing_existing_activity(self):
        start = datetime(2026, 6, 2, 8, 0, tzinfo=UTC)
        content = build_gpx(start, duration_s=20)
        client = _bearer_client(self.athlete)

        first = client.post("/v1/activities", {"file": SimpleUploadedFile("a.gpx", content)}, format="multipart")
        first_upload_id = first.json()["id"]
        activity_id = client.get(f"/v1/uploads/{first_upload_id}").json()["activity_id"]

        second = client.post("/v1/activities", {"file": SimpleUploadedFile("a.gpx", content)}, format="multipart")
        self.assertEqual(second.status_code, 409)
        body = second.json()
        self.assertEqual(body["status"], "duplicate")
        self.assertEqual(body["activity_id"], activity_id)

    def test_unsupported_extension_returns_400(self):
        response = _bearer_client(self.athlete).post(
            "/v1/activities", {"file": SimpleUploadedFile("workout.csv", b"a,b,c")}, format="multipart"
        )
        self.assertEqual(response.status_code, 400)

    def test_missing_file_returns_400(self):
        response = _bearer_client(self.athlete).post("/v1/activities", {}, format="multipart")
        self.assertEqual(response.status_code, 400)

    def test_outsider_cannot_upload_for_another_athlete(self):
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        content = build_gpx(datetime(2026, 6, 3, 8, 0, tzinfo=UTC), duration_s=10)
        response = client.post("/v1/activities", {"file": SimpleUploadedFile("x.gpx", content)}, format="multipart")
        self.assertEqual(response.status_code, 403)


class HrBasedTssIngestionTests(TestCase):
    def test_gpx_run_without_power_uses_hr_zone_fallback(self):
        athlete = User.objects.create_user(email="hr@example.cc", password="x", name="HR Athlete", lthr=160)
        start = datetime(2026, 6, 13, 6, 0, tzinfo=UTC)
        content = build_gpx(start, sport="running", duration_s=600, hr=160)
        client = _bearer_client(athlete)

        response = client.post("/v1/activities", {"file": SimpleUploadedFile("run.gpx", content)}, format="multipart")
        upload_id = response.json()["id"]
        poll = client.get(f"/v1/uploads/{upload_id}").json()
        activity = Activity.objects.get(pk=poll["activity_id"])

        self.assertIsNone(activity.norm_power)
        self.assertEqual(activity.tss, 16)  # round(600/3600 * 98)

        curve = DurationCurve.objects.get(activity=activity, metric="heartrate")
        self.assertEqual(curve.points, {"60": 160.0, "300": 160.0, "600": 160.0})


class TcxPowerIngestionTests(TestCase):
    def test_tcx_ride_computes_np_tss_and_best_effort(self):
        athlete = User.objects.create_user(email="power@example.cc", password="x", name="Power Athlete", ftp=200)
        start = datetime(2026, 6, 14, 7, 0, tzinfo=UTC)
        content = build_tcx(start, sport="Biking", duration_s=300, power=200, hr=140, distance_m=1500)
        client = _bearer_client(athlete)

        response = client.post("/v1/activities", {"file": SimpleUploadedFile("ride.tcx", content)}, format="multipart")
        upload_id = response.json()["id"]
        poll = client.get(f"/v1/uploads/{upload_id}").json()
        activity = Activity.objects.get(pk=poll["activity_id"])

        self.assertEqual(activity.avg_power, 200)
        self.assertEqual(activity.norm_power, 200)
        self.assertEqual(activity.intensity, 1.0)
        self.assertEqual(activity.tss, 8)  # round(300*200*1 / (200*3600) * 100)

        curve = DurationCurve.objects.get(activity=activity, metric="power")
        for key in ("5", "15", "30", "60", "300"):
            self.assertEqual(curve.points[key], 200.0)

        effort = BestEffort.objects.get(athlete=athlete, kind="cycling_power", window="1min")
        self.assertEqual(effort.value, 200.0)
        self.assertEqual(effort.activity_id, activity.id)


class RealFitIngestionTests(TestCase):
    """End-to-end ingestion of real device FIT files through the HTTP upload pipeline."""

    def setUp(self):
        self.athlete = User.objects.create_user(
            email="realfit@example.cc", password="x", name="Real Fit Athlete", ftp=250, critical_run_power=280
        )

    def _upload_fixture(self, name):
        content = (FIXTURES_DIR / name).read_bytes()
        client = _bearer_client(self.athlete)
        response = client.post("/v1/activities", {"file": SimpleUploadedFile(name, content)}, format="multipart")
        self.assertEqual(response.status_code, 202)
        upload_id = response.json()["id"]
        poll = client.get(f"/v1/uploads/{upload_id}").json()
        self.assertEqual(poll["status"], "ready", poll)
        return Activity.objects.get(pk=poll["activity_id"])

    def test_cycling_indoor_ingests_with_power_based_tss(self):
        activity = self._upload_fixture("cycling_indoor.fit")
        self.assertEqual(activity.sport, "bike")
        self.assertEqual(activity.environment, "indoor")
        self.assertEqual(Record.objects.filter(activity=activity).count(), 4283)
        self.assertEqual(Lap.objects.filter(activity=activity).count(), 8)
        self.assertIsNotNone(activity.norm_power)
        self.assertGreater(activity.norm_power, 0)
        self.assertGreater(activity.tss, 0)
        self.assertTrue(DurationCurve.objects.filter(activity=activity, metric="power").exists())
        self.assertTrue(BestEffort.objects.filter(athlete=self.athlete, kind="cycling_power").exists())

    def test_cycling_indoor_ingests_core_sensor_fields_but_no_avg_air_temp(self):
        activity = self._upload_fixture("cycling_indoor.fit")
        self.assertTrue(Record.objects.filter(activity=activity, core_temp__isnull=False).exists())
        self.assertTrue(Record.objects.filter(activity=activity, skin_temp__isnull=False).exists())
        self.assertTrue(Record.objects.filter(activity=activity, heat_strain__isnull=False).exists())
        # Bike rides never carry a Stryd footpod, so the run-only avg fields stay null.
        self.assertIsNone(activity.avg_air_temp)
        self.assertIsNone(activity.avg_humidity)

    def test_running_outdoor_marathon_derives_avg_air_temp_and_humidity_from_stryd(self):
        activity = self._upload_fixture("running_outdoor_marathon.fit")
        self.assertTrue(Record.objects.filter(activity=activity, air_temp__isnull=False).exists())
        self.assertTrue(Record.objects.filter(activity=activity, humidity__isnull=False).exists())
        self.assertFalse(Record.objects.filter(activity=activity, core_temp__isnull=False).exists())
        self.assertAlmostEqual(activity.avg_air_temp, 17.9, places=1)
        self.assertEqual(activity.avg_humidity, 58)

    def test_running_treadmill_ingests_with_stryd_power(self):
        activity = self._upload_fixture("running_treadmill.fit")
        self.assertEqual(activity.sport, "run")
        self.assertEqual(activity.environment, "indoor")
        self.assertEqual(Record.objects.filter(activity=activity).count(), 5299)
        self.assertIsNotNone(activity.norm_power)
        self.assertGreater(activity.tss, 0)
        self.assertTrue(BestEffort.objects.filter(athlete=self.athlete, kind="running_power").exists())

    def test_running_treadmill_derives_avg_env_fields_from_stryd_and_stores_core_records(self):
        activity = self._upload_fixture("running_treadmill.fit")
        self.assertTrue(Record.objects.filter(activity=activity, core_temp__isnull=False).exists())
        self.assertTrue(Record.objects.filter(activity=activity, skin_temp__isnull=False).exists())
        self.assertTrue(Record.objects.filter(activity=activity, heat_strain__isnull=False).exists())
        self.assertAlmostEqual(activity.avg_air_temp, 20.8, places=1)
        self.assertEqual(activity.avg_humidity, 35)

    def test_patch_avg_air_temp_ignored_after_stryd_ingestion(self):
        activity = self._upload_fixture("running_treadmill.fit")
        client = _bearer_client(self.athlete)
        response = client.patch(f"/v1/activities/{activity.id}", {"avg_air_temp": 99.0}, format="json")
        self.assertEqual(response.status_code, 200)
        self.assertAlmostEqual(response.json()["avg_air_temp"], 20.8, places=1)
