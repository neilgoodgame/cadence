from datetime import UTC, datetime, timedelta

from django.test import TestCase

from accounts.models import User

from .helpers import _bearer_client, _make_activity


class ActivityOverlapListViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.start = datetime(2026, 6, 1, 18, 0, 0, tzinfo=UTC)

    def test_full_overlap_between_two_devices_is_detected(self):
        zwift = _make_activity(
            self.athlete, sport="bike", device="Zwift", start_date=self.start, elapsed_seconds=3600, moving_time=3600
        )
        garmin = _make_activity(
            self.athlete,
            sport="bike",
            device="Garmin Edge 830",
            start_date=self.start + timedelta(seconds=5),
            elapsed_seconds=3600,
            moving_time=3600,
        )

        response = _bearer_client(self.athlete).get("/v1/activities/overlaps")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(len(body["data"]), 1)
        pair = body["data"][0]
        self.assertEqual({pair["anchor"]["id"], pair["other"]["id"]}, {zwift.id, garmin.id})
        self.assertEqual(pair["overlap_seconds"], 3595)
        self.assertAlmostEqual(pair["overlap_pct"], 99.86, delta=0.1)
        self.assertEqual(pair["start_delta_seconds"], 5)
        self.assertEqual(pair["status"], "unresolved")

    def test_partial_overlap_reports_the_overlapping_slice_only(self):
        _make_activity(
            self.athlete, sport="bike", device="Zwift", start_date=self.start, elapsed_seconds=3600, moving_time=3600
        )
        _make_activity(
            self.athlete,
            sport="bike",
            device="Garmin",
            start_date=self.start + timedelta(seconds=1800),
            elapsed_seconds=3600,
            moving_time=3600,
        )

        response = _bearer_client(self.athlete).get("/v1/activities/overlaps")
        pair = response.json()["data"][0]
        self.assertEqual(pair["overlap_seconds"], 1800)
        self.assertAlmostEqual(pair["overlap_pct"], 50.0, delta=0.1)

    def test_non_overlapping_activities_are_not_returned(self):
        _make_activity(
            self.athlete, sport="bike", device="Zwift", start_date=self.start, elapsed_seconds=3600, moving_time=3600
        )
        _make_activity(
            self.athlete,
            sport="bike",
            device="Garmin",
            start_date=self.start + timedelta(seconds=7200),
            elapsed_seconds=3600,
            moving_time=3600,
        )

        response = _bearer_client(self.athlete).get("/v1/activities/overlaps")
        self.assertEqual(response.json()["data"], [])

    def test_min_overlap_pct_boundary(self):
        # 400s overlap out of a 1000s shorter side -> exactly 40%.
        _make_activity(
            self.athlete, sport="bike", device="Zwift", start_date=self.start, elapsed_seconds=1000, moving_time=1000
        )
        _make_activity(
            self.athlete,
            sport="bike",
            device="Garmin",
            start_date=self.start + timedelta(seconds=600),
            elapsed_seconds=1000,
            moving_time=1000,
        )

        client = _bearer_client(self.athlete)
        self.assertEqual(client.get("/v1/activities/overlaps?minOverlapPct=50").json()["data"], [])
        self.assertEqual(len(client.get("/v1/activities/overlaps?minOverlapPct=40").json()["data"]), 1)

    def test_device_and_other_device_filters_match_case_insensitively_by_prefix(self):
        _make_activity(
            self.athlete,
            sport="bike",
            device="Zwift X",
            start_date=self.start,
            elapsed_seconds=3600,
            moving_time=3600,
        )
        _make_activity(
            self.athlete,
            sport="bike",
            device="Garmin Edge 830",
            start_date=self.start + timedelta(seconds=5),
            elapsed_seconds=3600,
            moving_time=3600,
        )

        client = _bearer_client(self.athlete)
        matched = client.get("/v1/activities/overlaps?device=zwift&otherDevice=garmin")
        self.assertEqual(len(matched.json()["data"]), 1)
        unmatched = client.get("/v1/activities/overlaps?device=peloton")
        self.assertEqual(unmatched.json()["data"], [])

    def test_from_and_to_filter_on_the_anchors_start_date(self):
        _make_activity(
            self.athlete,
            sport="bike",
            device="Zwift",
            start_date=datetime(2026, 1, 15, 18, 0, 0, tzinfo=UTC),
            elapsed_seconds=3600,
            moving_time=3600,
        )
        _make_activity(
            self.athlete,
            sport="bike",
            device="Garmin",
            start_date=datetime(2026, 1, 15, 18, 0, 5, tzinfo=UTC),
            elapsed_seconds=3600,
            moving_time=3600,
        )
        _make_activity(
            self.athlete,
            sport="bike",
            device="Zwift",
            start_date=datetime(2026, 3, 15, 18, 0, 0, tzinfo=UTC),
            elapsed_seconds=3600,
            moving_time=3600,
        )
        _make_activity(
            self.athlete,
            sport="bike",
            device="Garmin",
            start_date=datetime(2026, 3, 15, 18, 0, 5, tzinfo=UTC),
            elapsed_seconds=3600,
            moving_time=3600,
        )

        response = _bearer_client(self.athlete).get("/v1/activities/overlaps?from=2026-03-01&to=2026-03-31")
        body = response.json()
        self.assertEqual(len(body["data"]), 1)
        pair = body["data"][0]
        self.assertEqual(
            {pair["anchor"]["start_date"], pair["other"]["start_date"]},
            {"2026-03-15T18:00:00Z", "2026-03-15T18:00:05Z"},
        )

    def test_include_resolved_excludes_an_already_linked_duplicate_by_default(self):
        zwift = _make_activity(
            self.athlete, sport="bike", device="Zwift", start_date=self.start, elapsed_seconds=3600, moving_time=3600
        )
        _make_activity(
            self.athlete,
            sport="bike",
            device="Garmin",
            start_date=self.start + timedelta(seconds=5),
            elapsed_seconds=3600,
            moving_time=3600,
            primary_activity=zwift,
        )

        client = _bearer_client(self.athlete)
        self.assertEqual(client.get("/v1/activities/overlaps").json()["data"], [])
        included = client.get("/v1/activities/overlaps?includeResolved=true").json()["data"]
        self.assertEqual(len(included), 1)
        self.assertEqual(included[0]["status"], "duplicate")

    def test_activity_id_pins_the_anchor_to_that_one_activity(self):
        zwift = _make_activity(
            self.athlete, sport="bike", device="Zwift", start_date=self.start, elapsed_seconds=3600, moving_time=3600
        )
        garmin = _make_activity(
            self.athlete,
            sport="bike",
            device="Garmin",
            start_date=self.start + timedelta(seconds=5),
            elapsed_seconds=3600,
            moving_time=3600,
        )

        response = _bearer_client(self.athlete).get(f"/v1/activities/overlaps?activityId={garmin.id}")
        body = response.json()
        self.assertEqual(len(body["data"]), 1)
        self.assertEqual(body["data"][0]["anchor"]["id"], garmin.id)
        self.assertEqual(body["data"][0]["other"]["id"], zwift.id)

    def test_other_has_filters_single_and_repeated(self):
        _make_activity(
            self.athlete, sport="bike", device="Zwift", start_date=self.start, elapsed_seconds=3600, moving_time=3600
        )
        _make_activity(
            self.athlete,
            sport="bike",
            device="Garmin",
            start_date=self.start + timedelta(seconds=5),
            elapsed_seconds=3600,
            moving_time=3600,
            avg_power=220,
            avg_hr=None,
        )

        client = _bearer_client(self.athlete)
        with_power = client.get("/v1/activities/overlaps?otherHas=power")
        self.assertEqual(len(with_power.json()["data"]), 1)

        # Garmin has power but no heart rate - requiring both must exclude the pair.
        with_power_and_hr = client.get("/v1/activities/overlaps?otherHas=power&otherHas=heartrate")
        self.assertEqual(with_power_and_hr.json()["data"], [])

    def test_min_duration_seconds_ignores_a_short_activity_on_either_side(self):
        _make_activity(
            self.athlete, sport="bike", device="Zwift", start_date=self.start, elapsed_seconds=3600, moving_time=3600
        )
        _make_activity(
            self.athlete,
            sport="bike",
            device="Garmin",
            start_date=self.start + timedelta(seconds=5),
            elapsed_seconds=60,
            moving_time=60,
        )

        client = _bearer_client(self.athlete)
        self.assertEqual(len(client.get("/v1/activities/overlaps").json()["data"]), 1)
        self.assertEqual(client.get("/v1/activities/overlaps?minDurationSeconds=300").json()["data"], [])

    def test_environment_filter_applies_to_both_sides(self):
        _make_activity(
            self.athlete,
            sport="bike",
            device="Zwift",
            environment="indoor",
            start_date=self.start,
            elapsed_seconds=3600,
            moving_time=3600,
        )
        _make_activity(
            self.athlete,
            sport="bike",
            device="Garmin",
            environment="outdoor",
            start_date=self.start + timedelta(seconds=5),
            elapsed_seconds=3600,
            moving_time=3600,
        )

        client = _bearer_client(self.athlete)
        self.assertEqual(client.get("/v1/activities/overlaps?environment=indoor").json()["data"], [])
        self.assertEqual(len(client.get("/v1/activities/overlaps").json()["data"]), 1)

    def test_never_returns_a_pair_across_different_athletes(self):
        other = User.objects.create_user(email="other-overlap@example.cc", password="x", name="Other")
        _make_activity(
            self.athlete, sport="bike", device="Zwift", start_date=self.start, elapsed_seconds=3600, moving_time=3600
        )
        _make_activity(
            other,
            sport="bike",
            device="Garmin",
            start_date=self.start + timedelta(seconds=5),
            elapsed_seconds=3600,
            moving_time=3600,
        )

        response = _bearer_client(self.athlete).get("/v1/activities/overlaps")
        self.assertEqual(response.json()["data"], [])

    def test_sport_filter_requires_both_sides_unless_cross_sport_is_set(self):
        _make_activity(
            self.athlete, sport="bike", device="Zwift", start_date=self.start, elapsed_seconds=3600, moving_time=3600
        )
        _make_activity(
            self.athlete,
            sport="run",
            device="Garmin",
            start_date=self.start + timedelta(seconds=5),
            elapsed_seconds=3600,
            moving_time=3600,
        )

        client = _bearer_client(self.athlete)
        same_sport_only = client.get("/v1/activities/overlaps?sport=bike")
        self.assertEqual(same_sport_only.json()["data"], [])
        cross_sport = client.get("/v1/activities/overlaps?sport=bike&crossSport=true")
        self.assertEqual(len(cross_sport.json()["data"]), 1)

    def test_other_has_invalid_value_400(self):
        response = _bearer_client(self.athlete).get("/v1/activities/overlaps?otherHas=nonsense")
        self.assertEqual(response.status_code, 400)

    def test_unknown_cursor_400(self):
        response = _bearer_client(self.athlete).get("/v1/activities/overlaps?cursor=not-valid-base64!!!")
        self.assertEqual(response.status_code, 400)
