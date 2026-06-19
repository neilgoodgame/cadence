import tempfile
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import MagicMock, patch

from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import SimpleTestCase, TestCase
from rest_framework.test import APIClient

from accounts.models import User
from activities.models import Activity, ActivityTag, BestEffort, DurationCurve, Lap, Record
from authn.jwt_utils import mint_jwt
from authn.oauth_utils import issue_token_pair
from scheduling.models import ScheduledWorkout
from workouts.models import Workout

from .fixtures_helpers import build_gpx, build_tcx, build_zip
from .parsers import UnsupportedFileType, parse_file
from .parsers.fit import parse_fit
from .parsers.gpx import parse_gpx
from .parsers.tcx import parse_tcx
from .processing import (
    attempt_workout_match,
    compute_duration_curve,
    compute_normalized_power,
    compute_tss,
    update_best_efforts,
)

FIXTURES_DIR = Path(__file__).resolve().parent / "tests_fixtures"


def _bearer_client(user, scope="activities:read activities:write"):
    access_token, _ = issue_token_pair(user, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access_token.token}")
    return client


def _delegated_client(sub, athlete_id, scopes):
    token, _claims = mint_jwt(sub=sub.id, athlete_id=athlete_id.id, scopes=scopes, expires_in=60)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {token}")
    return client


def _fit_msg(**fields):
    msg = MagicMock()
    msg.get_value.side_effect = lambda key: fields.get(key)
    return msg


# ---------------------------------------------------------------------------
# Pure derived-field math
# ---------------------------------------------------------------------------


class ComputeNormalizedPowerTests(SimpleTestCase):
    def test_constant_power_equals_average(self):
        self.assertAlmostEqual(compute_normalized_power([200] * 100), 200, places=3)

    def test_short_series_falls_back_to_plain_mean(self):
        self.assertEqual(compute_normalized_power([100, 200, 300]), 200)

    def test_empty_series_returns_none(self):
        self.assertIsNone(compute_normalized_power([]))


class ComputeDurationCurveTests(SimpleTestCase):
    def test_picks_best_window_average(self):
        series = [100] * 10 + [300] * 5 + [100] * 10
        points = compute_duration_curve(series, [5])
        self.assertEqual(points["5"], 300.0)

    def test_omits_windows_longer_than_series(self):
        points = compute_duration_curve([100] * 10, [5, 20])
        self.assertIn("5", points)
        self.assertNotIn("20", points)


class ComputeTssTests(TestCase):
    def test_power_based_tss_one_hour_at_ftp_equals_100(self):
        athlete = User.objects.create_user(email="ftp@example.cc", password="x", name="FTP Athlete", ftp=200)
        activity = Activity(sport="bike", moving_time=3600)
        tss = compute_tss(activity, athlete, norm_power=200, heartrate_series=[])
        self.assertEqual(tss, 100)

    def test_hr_based_fallback_uses_zone_midpoint(self):
        athlete = User.objects.create_user(email="lthr@example.cc", password="x", name="LTHR Athlete", lthr=160)
        activity = Activity(sport="bike", moving_time=3600)
        tss = compute_tss(activity, athlete, norm_power=None, heartrate_series=[160] * 3600)
        # All samples sit at exactly 100% of LTHR -> Z4 Threshold (91-105%),
        # whose midpoint is 98% -> a full hour there is 98 hrTSS exactly.
        self.assertEqual(tss, 98)


# ---------------------------------------------------------------------------
# File parsers
# ---------------------------------------------------------------------------


class GpxParserTests(SimpleTestCase):
    def test_parses_track_points_and_hr_extension(self):
        start = datetime(2026, 6, 1, 8, 0, 0, tzinfo=timezone.utc)
        content = build_gpx(start, sport="running", duration_s=10, hr=150)
        with tempfile.NamedTemporaryFile(suffix=".gpx") as f:
            f.write(content)
            f.flush()
            result = parse_gpx(f.name)

        self.assertEqual(result["sport"], "run")
        self.assertTrue(result["has_gps"])
        self.assertEqual(len(result["samples"]), 10)
        self.assertEqual(result["samples"][0]["heartrate"], 150)
        self.assertEqual(result["start_date"], start)


class TcxParserTests(SimpleTestCase):
    def test_parses_trackpoints_power_and_laps(self):
        start = datetime(2026, 6, 1, 8, 0, 0, tzinfo=timezone.utc)
        content = build_tcx(start, sport="Biking", duration_s=10, power=200, hr=140, distance_m=50)
        with tempfile.NamedTemporaryFile(suffix=".tcx") as f:
            f.write(content)
            f.flush()
            result = parse_tcx(f.name)

        self.assertEqual(result["sport"], "bike")
        self.assertEqual(len(result["samples"]), 10)
        self.assertEqual(result["samples"][0]["power"], 200)
        self.assertEqual(len(result["laps"]), 1)
        self.assertEqual(result["laps"][0]["duration"], 10)


class FitParserMockedTests(SimpleTestCase):
    @patch("uploads.parsers.fit.FitFile")
    def test_parses_gps_semicircles_and_laps(self, mock_fitfile_cls):
        start = datetime(2026, 6, 1, 8, 0, 0)  # naive, like real fitparse timestamps
        lat_semicircles = 642065151
        lng_semicircles = -188432580

        records = [
            _fit_msg(
                timestamp=start,
                position_lat=lat_semicircles,
                position_long=lng_semicircles,
                heart_rate=140,
                power=200,
                cadence=85,
                distance=0.0,
                enhanced_speed=5.0,
                enhanced_altitude=10.0,
            ),
            _fit_msg(
                timestamp=start + timedelta(seconds=1),
                position_lat=lat_semicircles,
                position_long=lng_semicircles,
                heart_rate=141,
                power=205,
                cadence=86,
                distance=5.0,
                enhanced_speed=5.1,
                enhanced_altitude=10.5,
            ),
        ]
        laps = [_fit_msg(total_elapsed_time=2, total_distance=5.0, avg_heart_rate=140, avg_power=202)]
        sessions = [_fit_msg(sport="cycling")]

        instance = MagicMock()
        instance.get_messages.side_effect = lambda name: {
            "record": records,
            "lap": laps,
            "session": sessions,
        }[name]
        mock_fitfile_cls.return_value = instance

        result = parse_fit("/fake/path.fit")

        self.assertEqual(result["sport"], "bike")
        self.assertTrue(result["has_gps"])
        self.assertEqual(result["environment"], "outdoor")
        self.assertEqual(len(result["samples"]), 2)
        self.assertAlmostEqual(result["samples"][0]["lat"], lat_semicircles * (180 / 2**31), places=6)
        self.assertEqual(result["samples"][1]["t"], 1)
        self.assertEqual(result["samples"][1]["power"], 205)
        self.assertEqual(len(result["laps"]), 1)
        self.assertEqual(result["laps"][0]["avg_power"], 202)


class ParseFileDispatchTests(SimpleTestCase):
    def test_unsupported_extension_raises(self):
        with self.assertRaises(UnsupportedFileType):
            parse_file("/fake/path.csv", "data.csv")


class RealFitFixtureParserTests(SimpleTestCase):
    """Real-world FIT files (Garmin + Stryd run-power dev field), not synthetic ones."""

    def test_cycling_indoor_has_native_power_field(self):
        result = parse_fit(str(FIXTURES_DIR / "cycling_indoor.fit"))
        self.assertEqual(result["sport"], "bike")
        self.assertEqual(result["environment"], "indoor")
        self.assertFalse(result["has_gps"])
        self.assertEqual(len(result["samples"]), 4283)
        self.assertEqual(len(result["laps"]), 8)
        self.assertTrue(all(s["power"] is not None for s in result["samples"]))

    def test_running_outdoor_marathon_falls_back_to_stryd_power_field(self):
        result = parse_fit(str(FIXTURES_DIR / "running_outdoor_marathon.fit"))
        self.assertEqual(result["sport"], "run")
        self.assertEqual(result["environment"], "outdoor")
        self.assertTrue(result["has_gps"])
        self.assertEqual(len(result["samples"]), 12241)
        self.assertTrue(all(s["power"] is not None for s in result["samples"]))

    def test_running_treadmill_falls_back_to_stryd_power_field(self):
        result = parse_fit(str(FIXTURES_DIR / "running_treadmill.fit"))
        self.assertEqual(result["sport"], "run")
        self.assertEqual(result["environment"], "indoor")
        self.assertFalse(result["has_gps"])
        self.assertEqual(len(result["samples"]), 5299)
        self.assertTrue(all(s["power"] is not None for s in result["samples"]))


# ---------------------------------------------------------------------------
# Best-effort upsert + workout matching (direct, no file parsing)
# ---------------------------------------------------------------------------


class BestEffortUpsertTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="be@example.cc", password="x", name="BE Athlete", ftp=1)

    def _activity(self, suffix):
        return Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name=f"Ride {suffix}",
            start_date=datetime(2026, 6, 10, 7, 0, tzinfo=timezone.utc),
        )

    def test_improves_then_holds_then_improves_again(self):
        a1 = self._activity("1")
        update_best_efforts(a1, self.athlete, [200] * 60, [])
        effort = BestEffort.objects.get(athlete=self.athlete, kind="cycling_power", window="1min")
        self.assertEqual(effort.value, 200.0)
        self.assertEqual(effort.activity_id, a1.id)

        a2 = self._activity("2")
        update_best_efforts(a2, self.athlete, [150] * 60, [])
        effort.refresh_from_db()
        self.assertEqual(effort.value, 200.0)
        self.assertEqual(effort.activity_id, a1.id)

        a3 = self._activity("3")
        update_best_efforts(a3, self.athlete, [250] * 60, [])
        effort.refresh_from_db()
        self.assertEqual(effort.value, 250.0)
        self.assertEqual(effort.activity_id, a3.id)


class WorkoutMatchingTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="wm@example.cc", password="x", name="WM Athlete")

    def test_links_and_tags_matching_scheduled_workout(self):
        workout = Workout.objects.create(created_by=self.athlete, name="Tempo run", sport="run")
        scheduled = ScheduledWorkout.objects.create(workout=workout, athlete=self.athlete, date=date(2026, 6, 11))

        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="run",
            name="Morning run",
            start_date=datetime(2026, 6, 11, 6, 30, tzinfo=timezone.utc),
        )
        attempt_workout_match(activity, self.athlete)

        scheduled.refresh_from_db()
        activity.refresh_from_db()
        self.assertEqual(scheduled.status, "completed")
        self.assertEqual(scheduled.activity_id, activity.id)
        self.assertEqual(activity.workout_id, workout.id)
        self.assertTrue(
            ActivityTag.objects.filter(activity=activity, tag__name="Auto-matched", tag__origin="auto").exists()
        )

    def test_does_not_match_different_sport(self):
        workout = Workout.objects.create(created_by=self.athlete, name="Tempo run", sport="run")
        scheduled = ScheduledWorkout.objects.create(workout=workout, athlete=self.athlete, date=date(2026, 6, 12))

        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name="Easy ride",
            start_date=datetime(2026, 6, 12, 6, 30, tzinfo=timezone.utc),
        )
        attempt_workout_match(activity, self.athlete)

        scheduled.refresh_from_db()
        self.assertEqual(scheduled.status, "planned")
        self.assertIsNone(scheduled.activity_id)


# ---------------------------------------------------------------------------
# Full HTTP upload flow: POST /v1/activities
# ---------------------------------------------------------------------------


class ActivityUploadViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_upload_gpx_returns_202_then_ready_on_poll(self):
        start = datetime(2026, 6, 1, 8, 0, tzinfo=timezone.utc)
        content = build_gpx(start, sport="running", duration_s=30, hr=150)
        client = _bearer_client(self.athlete)

        response = client.post(
            "/v1/activities", {"file": SimpleUploadedFile("run.gpx", content)}, format="multipart"
        )
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
        start = datetime(2026, 6, 2, 8, 0, tzinfo=timezone.utc)
        content = build_gpx(start, duration_s=20)
        client = _bearer_client(self.athlete)

        first = client.post(
            "/v1/activities", {"file": SimpleUploadedFile("a.gpx", content)}, format="multipart"
        )
        first_upload_id = first.json()["id"]
        activity_id = client.get(f"/v1/uploads/{first_upload_id}").json()["activity_id"]

        second = client.post(
            "/v1/activities", {"file": SimpleUploadedFile("a.gpx", content)}, format="multipart"
        )
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
        content = build_gpx(datetime(2026, 6, 3, 8, 0, tzinfo=timezone.utc), duration_s=10)
        response = client.post(
            "/v1/activities", {"file": SimpleUploadedFile("x.gpx", content)}, format="multipart"
        )
        self.assertEqual(response.status_code, 403)


class HrBasedTssIngestionTests(TestCase):
    def test_gpx_run_without_power_uses_hr_zone_fallback(self):
        athlete = User.objects.create_user(email="hr@example.cc", password="x", name="HR Athlete", lthr=160)
        start = datetime(2026, 6, 13, 6, 0, tzinfo=timezone.utc)
        content = build_gpx(start, sport="running", duration_s=600, hr=160)
        client = _bearer_client(athlete)

        response = client.post(
            "/v1/activities", {"file": SimpleUploadedFile("run.gpx", content)}, format="multipart"
        )
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
        start = datetime(2026, 6, 14, 7, 0, tzinfo=timezone.utc)
        content = build_tcx(start, sport="Biking", duration_s=300, power=200, hr=140, distance_m=1500)
        client = _bearer_client(athlete)

        response = client.post(
            "/v1/activities", {"file": SimpleUploadedFile("ride.tcx", content)}, format="multipart"
        )
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
        response = client.post(
            "/v1/activities", {"file": SimpleUploadedFile(name, content)}, format="multipart"
        )
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

    def test_running_treadmill_ingests_with_stryd_power(self):
        activity = self._upload_fixture("running_treadmill.fit")
        self.assertEqual(activity.sport, "run")
        self.assertEqual(activity.environment, "indoor")
        self.assertEqual(Record.objects.filter(activity=activity).count(), 5299)
        self.assertIsNotNone(activity.norm_power)
        self.assertGreater(activity.tss, 0)
        self.assertTrue(BestEffort.objects.filter(athlete=self.athlete, kind="running_power").exists())


# ---------------------------------------------------------------------------
# Full HTTP batch flow: POST /v1/activities/batch
# ---------------------------------------------------------------------------


class ActivityBatchUploadViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_batch_upload_processes_all_files(self):
        start = datetime(2026, 6, 4, 7, 0, tzinfo=timezone.utc)
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
        self.assertEqual(
            poll_body["counts"], {"total": 2, "ready": 2, "processing": 0, "failed": 0, "duplicate": 0}
        )
        self.assertEqual(poll_body["progress"], 1.0)
        self.assertEqual(Activity.objects.filter(athlete=self.athlete).count(), 2)

    def test_duplicate_file_within_batch_is_skipped(self):
        start = datetime(2026, 6, 5, 7, 0, tzinfo=timezone.utc)
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
        start = datetime(2026, 6, 6, 7, 0, tzinfo=timezone.utc)
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
        zip_bytes = build_zip({f"f{i}.gpx": b"" for i in range(501)})
        response = _bearer_client(self.athlete).post(
            "/v1/activities/batch", {"file": SimpleUploadedFile("toomany.zip", zip_bytes)}, format="multipart"
        )
        self.assertEqual(response.status_code, 400)

    def test_outsider_cannot_batch_upload(self):
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        zip_bytes = build_zip({"a.tcx": build_tcx(datetime(2026, 6, 7, 7, 0, tzinfo=timezone.utc), duration_s=5)})
        response = client.post(
            "/v1/activities/batch", {"file": SimpleUploadedFile("x.zip", zip_bytes)}, format="multipart"
        )
        self.assertEqual(response.status_code, 403)


class UploadPollingPermissionTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_outsider_cannot_poll_upload(self):
        content = build_gpx(datetime(2026, 6, 8, 7, 0, tzinfo=timezone.utc), duration_s=5)
        client = _bearer_client(self.athlete)
        upload_id = client.post(
            "/v1/activities", {"file": SimpleUploadedFile("a.gpx", content)}, format="multipart"
        ).json()["id"]

        outsider_client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = outsider_client.get(f"/v1/uploads/{upload_id}")
        self.assertEqual(response.status_code, 403)

    def test_outsider_cannot_poll_batch(self):
        zip_bytes = build_zip({"a.tcx": build_tcx(datetime(2026, 6, 9, 7, 0, tzinfo=timezone.utc), duration_s=5)})
        client = _bearer_client(self.athlete)
        batch_id = client.post(
            "/v1/activities/batch", {"file": SimpleUploadedFile("z.zip", zip_bytes)}, format="multipart"
        ).json()["id"]

        outsider_client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = outsider_client.get(f"/v1/uploads/batches/{batch_id}")
        self.assertEqual(response.status_code, 403)
