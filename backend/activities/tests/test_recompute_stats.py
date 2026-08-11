import json
from datetime import timedelta

from django.test import TestCase

from accounts.models import User

from ..models import Record
from .helpers import _bearer_client, _delegated_client, _make_activity


def _final_stream_event(response) -> dict:
    content = b"".join(response.streaming_content).decode()
    marker = "event: done\ndata: "
    payload = content[content.rindex(marker) + len(marker) :].split("\n\n", 1)[0]
    return json.loads(payload)


class RecomputeActivityStatsViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete", lthr=160)
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def _make_activity_with_records(self):
        activity = _make_activity(self.athlete, moving_time=3600)
        for t in range(3600):
            Record.objects.create(
                activity=activity,
                t=t,
                ts=activity.start_date + timedelta(seconds=t),
                power=200,
                cadence=85,
                speed=8.0,
                altitude=100 + (t % 10),
                heartrate=160,
            )
        return activity

    def test_backfills_stats_from_stored_records(self):
        activity = self._make_activity_with_records()
        self.assertIsNone(activity.max_power)

        response = _bearer_client(self.athlete).post(f"/v1/activities/{activity.id}/recompute-stats")
        self.assertEqual(response.status_code, 200)
        body = response.json()

        self.assertEqual(body["max_power"], 200)
        self.assertEqual(body["avg_cadence"], 85)
        self.assertEqual(body["max_cadence"], 85)
        self.assertAlmostEqual(body["max_speed"], 28.8, places=1)  # 8.0 m/s -> km/h
        self.assertEqual(body["elevation_min"], 100)
        self.assertEqual(body["elevation_max"], 109)
        # A 0-9m sawtooth every 10 samples is sensor-noise-scale, not real elevation change -
        # smoothed, it nets out to near zero rather than summing every micro-fluctuation.
        self.assertLess(body["ascent"], 20)
        self.assertLess(body["total_descent"], 20)
        self.assertIsNotNone(body["calories"])
        # All samples at 100% of LTHR (160) -> Z4 Threshold -> 60 min * zone 4 = 240.
        self.assertEqual(body["trimp"], 240.0)

        activity.refresh_from_db()
        self.assertEqual(activity.max_power, 200)

    def test_missing_source_data_leaves_fields_null(self):
        activity = _make_activity(self.athlete, moving_time=60)
        Record.objects.create(activity=activity, t=0, ts=activity.start_date)

        response = _bearer_client(self.athlete).post(f"/v1/activities/{activity.id}/recompute-stats")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertIsNone(body["max_power"])
        self.assertIsNone(body["avg_cadence"])
        self.assertIsNone(body["calories"])

    def test_outsider_cannot_recompute(self):
        activity = self._make_activity_with_records()
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.post(f"/v1/activities/{activity.id}/recompute-stats")
        self.assertEqual(response.status_code, 403)


class RecomputeActivityTssViewTests(TestCase):
    """recompute-tss reads the activity's own threshold snapshot, not the athlete's current
    profile - the actual bug this feature fixes (see athletes/zones.py::reference_for)."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="tss-recompute@example.cc", password="x", name="Athlete", ftp=300)

    def test_recompute_uses_the_snapshot_not_the_current_ftp(self):
        activity = _make_activity(
            self.athlete,
            sport="bike",
            moving_time=3600,
            tss=0,
            ftp_snapshot=200,
        )
        for t in range(3600):
            Record.objects.create(activity=activity, t=t, ts=activity.start_date + timedelta(seconds=t), power=200)

        response = _bearer_client(self.athlete).post(f"/v1/activities/{activity.id}/recompute-tss")
        self.assertEqual(response.status_code, 200)
        # 200W at a 200W snapshot FTP for a full hour = 100 TSS, not the ~44 a re-rate against
        # the athlete's current 300 FTP would silently have produced.
        self.assertEqual(response.json()["tss"], 100)
        activity.refresh_from_db()
        self.assertEqual(activity.tss, 100)


class RecomputeAthleteStatsViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="bulk-athlete@example.cc", password="x", name="Athlete", lthr=160)
        self.outsider = User.objects.create_user(email="bulk-outsider@example.cc", password="x", name="Outsider")

    def _make_activity_with_records(self, **kwargs):
        activity = _make_activity(self.athlete, moving_time=3600, **kwargs)
        for t in range(3600):
            Record.objects.create(
                activity=activity,
                t=t,
                ts=activity.start_date + timedelta(seconds=t),
                power=200,
                cadence=85,
                speed=8.0,
                altitude=100 + (t % 10),
                heartrate=160,
            )
        return activity

    def test_backfills_stats_across_all_of_the_athletes_activities(self):
        first = self._make_activity_with_records()
        second = self._make_activity_with_records(name="Evening Run")
        self.assertIsNone(first.max_power)
        self.assertIsNone(second.max_power)

        response = _bearer_client(self.athlete).post(f"/v1/athletes/{self.athlete.id}/recompute-stats")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(_final_stream_event(response)["updated"], 2)

        first.refresh_from_db()
        second.refresh_from_db()
        self.assertEqual(first.max_power, 200)
        self.assertEqual(second.max_power, 200)
        self.assertIsNotNone(first.calories)
        self.assertIsNotNone(second.calories)

    def test_does_not_touch_other_athletes_activities(self):
        other_athlete = User.objects.create_user(email="bulk-other@example.cc", password="x", name="Other")
        other_activity = _make_activity(other_athlete)

        response = _bearer_client(self.athlete).post(f"/v1/athletes/{self.athlete.id}/recompute-stats")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(_final_stream_event(response)["updated"], 0)

        other_activity.refresh_from_db()
        self.assertIsNone(other_activity.max_power)

    def test_activities_with_no_records_are_not_counted_as_updated(self):
        # No lthr on this athlete (unlike self.athlete) so compute_edwards_trimp also has
        # nothing to compute - otherwise trimp still resolves to 0.0 (a real "zero time in
        # any zone" value, distinct from "nothing to backfill") even with zero records.
        no_lthr_athlete = User.objects.create_user(email="bulk-no-lthr@example.cc", password="x", name="No LTHR")
        _make_activity(no_lthr_athlete)

        response = _bearer_client(no_lthr_athlete).post(f"/v1/athletes/{no_lthr_athlete.id}/recompute-stats")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(_final_stream_event(response)["updated"], 0)

    def test_outsider_cannot_bulk_recompute(self):
        self._make_activity_with_records()
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.post(f"/v1/athletes/{self.athlete.id}/recompute-stats")
        self.assertEqual(response.status_code, 403)

    def test_streams_progress_after_each_activity(self):
        self._make_activity_with_records()
        self._make_activity_with_records(name="Evening Run")
        self._make_activity_with_records(name="Long Ride")

        response = _bearer_client(self.athlete).post(f"/v1/athletes/{self.athlete.id}/recompute-stats")
        content = b"".join(response.streaming_content).decode()
        progress_events = [
            json.loads(block[len("data: ") :]) for block in content.split("\n\n") if block.startswith("data: ")
        ]

        self.assertEqual(len(progress_events), 3)
        self.assertEqual(progress_events[0], {"current": 1, "total": 3})
        self.assertEqual(progress_events[-1], {"current": 3, "total": 3})
