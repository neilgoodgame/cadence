from datetime import UTC, date, datetime, timedelta

from django.test import TestCase
from django.utils import timezone
from rest_framework.test import APIClient

from accounts.models import User, UserRelationship
from activities.models import Activity, BestEffort
from authn.jwt_utils import mint_jwt
from authn.oauth_utils import issue_token_pair
from uploads.processing import _trim_kind_window

from .models import ZoneSet
from .zones import DEFAULT_ZONES, reference_for


def _bearer_client(user, scope="activities:read activities:write workouts:write calendar:write coach gear:write"):
    access_token, _ = issue_token_pair(user, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access_token.token}")
    return client


def _delegated_client(sub, athlete_id, scopes):
    token, _claims = mint_jwt(sub=sub.id, athlete_id=athlete_id.id, scopes=scopes, expires_in=60)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {token}")
    return client


class AthleteDetailViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(
            email="athlete@example.cc", password="x", name="Athlete", ftp=250, lthr=160, threshold_pace="4:00"
        )
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_self_can_read_own_profile(self):
        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["ftp"], 250)

    def test_outsider_without_relationship_is_forbidden(self):
        response = _bearer_client(self.outsider).get(f"/v1/athletes/{self.athlete.id}")
        self.assertEqual(response.status_code, 403)

    def test_active_viewer_can_read_via_delegated_jwt(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.outsider,
            role=UserRelationship.ROLE_VIEWER,
            status=UserRelationship.STATUS_ACTIVE,
        )
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.get(f"/v1/athletes/{self.athlete.id}")
        self.assertEqual(response.status_code, 200)

    def test_self_can_update_thresholds(self):
        response = _bearer_client(self.athlete).patch(f"/v1/athletes/{self.athlete.id}", {"ftp": 280}, format="json")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["ftp"], 280)
        self.athlete.refresh_from_db()
        self.assertEqual(self.athlete.ftp, 280)

    def test_update_with_no_existing_zone_set_reports_no_recompute(self):
        response = _bearer_client(self.athlete).patch(f"/v1/athletes/{self.athlete.id}", {"ftp": 280}, format="json")
        self.assertEqual(response.json()["zones_recomputed"], [])

    def test_update_with_existing_zone_set_reports_recompute(self):
        ZoneSet.objects.create(athlete=self.athlete, type="bike_power", zones=DEFAULT_ZONES)
        response = _bearer_client(self.athlete).patch(f"/v1/athletes/{self.athlete.id}", {"ftp": 280}, format="json")
        self.assertEqual(response.json()["zones_recomputed"], ["bike_power"])

    def test_self_can_update_weight(self):
        response = _bearer_client(self.athlete).patch(
            f"/v1/athletes/{self.athlete.id}", {"weight_kg": 71.5}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["weight_kg"], 71.5)
        self.athlete.refresh_from_db()
        self.assertEqual(self.athlete.weight_kg, 71.5)

    def test_self_can_update_resting_hr(self):
        response = _bearer_client(self.athlete).patch(
            f"/v1/athletes/{self.athlete.id}", {"resting_hr": 48}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["resting_hr"], 48)
        self.athlete.refresh_from_db()
        self.assertEqual(self.athlete.resting_hr, 48)

    def test_resting_hr_update_does_not_trigger_zone_recompute(self):
        # resting_hr isn't a zone reference threshold (it only feeds the Karvonen HRR%
        # stat) - unlike ftp/lthr/max_hr, changing it shouldn't report any recompute.
        ZoneSet.objects.create(athlete=self.athlete, type="heart_rate", zones=DEFAULT_ZONES)
        response = _bearer_client(self.athlete).patch(
            f"/v1/athletes/{self.athlete.id}", {"resting_hr": 48}, format="json"
        )
        self.assertEqual(response.json()["zones_recomputed"], [])

    def test_viewer_cannot_write(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.outsider,
            role=UserRelationship.ROLE_VIEWER,
            status=UserRelationship.STATUS_ACTIVE,
        )
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.patch(f"/v1/athletes/{self.athlete.id}", {"ftp": 999}, format="json")
        self.assertEqual(response.status_code, 403)

    def test_coach_can_write(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.outsider,
            role=UserRelationship.ROLE_COACH,
            status=UserRelationship.STATUS_ACTIVE,
        )
        client = _delegated_client(self.outsider, self.athlete, scopes=["calendar:write"])
        response = client.patch(f"/v1/athletes/{self.athlete.id}", {"ftp": 300}, format="json")
        self.assertEqual(response.status_code, 200)


class ZoneSetViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(
            email="athlete@example.cc",
            password="x",
            name="Athlete",
            ftp=250,
            lthr=160,
            critical_run_power=270,
            threshold_pace="4:00",
        )
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

    def test_list_lazily_seeds_default_zones_for_all_four_types(self):
        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}/zones")
        self.assertEqual(response.status_code, 200)
        data = response.json()["data"]
        self.assertEqual({z["type"] for z in data}, {"heart_rate", "bike_power", "run_power", "pace"})
        bike = next(z for z in data if z["type"] == "bike_power")
        self.assertEqual(bike["reference"], 250)
        self.assertEqual(bike["zones"], DEFAULT_ZONES)

    def test_pace_reference_is_seconds_from_mmss(self):
        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}/zones")
        pace = next(z for z in response.json()["data"] if z["type"] == "pace")
        self.assertEqual(pace["reference"], 240)

    def test_outsider_without_relationship_cannot_list(self):
        response = _bearer_client(self.outsider).get(f"/v1/athletes/{self.athlete.id}/zones")
        self.assertEqual(response.status_code, 403)

    def test_replace_zone_set(self):
        new_zones = [{"name": "Z1", "low_pct": 0, "high_pct": 60}, {"name": "Z2", "low_pct": 61, "high_pct": 100}]
        response = _bearer_client(self.athlete).put(
            f"/v1/athletes/{self.athlete.id}/zones/bike_power", {"zones": new_zones}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"type": "bike_power", "reference": 250, "updated": True})
        zone_set = ZoneSet.objects.get(athlete=self.athlete, type="bike_power")
        self.assertEqual(zone_set.zones, new_zones)

    def test_replace_unknown_type_is_rejected(self):
        response = _bearer_client(self.athlete).put(
            f"/v1/athletes/{self.athlete.id}/zones/bananas", {"zones": []}, format="json"
        )
        self.assertEqual(response.status_code, 400)

    def test_replace_missing_zones_field_is_rejected(self):
        response = _bearer_client(self.athlete).put(
            f"/v1/athletes/{self.athlete.id}/zones/bike_power", {}, format="json"
        )
        self.assertEqual(response.status_code, 400)

    def test_viewer_cannot_replace(self):
        UserRelationship.objects.create(
            owner=self.athlete,
            grantee=self.outsider,
            role=UserRelationship.ROLE_VIEWER,
            status=UserRelationship.STATUS_ACTIVE,
        )
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.put(f"/v1/athletes/{self.athlete.id}/zones/bike_power", {"zones": []}, format="json")
        self.assertEqual(response.status_code, 403)


class ReferenceForTests(TestCase):
    """Direct unit coverage of zones.py::reference_for's activity-scoping, independent of the
    HTTP layer covered by ZoneSetActivityScopeTests below."""

    def setUp(self):
        self.athlete = User.objects.create_user(
            email="reference-for@example.cc",
            password="x",
            name="Athlete",
            ftp=250,
            lthr=160,
            critical_run_power=270,
            threshold_pace="4:00",
        )

    def test_without_activity_reads_the_athletes_live_profile(self):
        self.assertEqual(reference_for(self.athlete, "bike_power"), 250)
        self.assertEqual(reference_for(self.athlete, "pace"), 240)  # "4:00" -> 240s

    def test_with_activity_reads_that_activitys_snapshot(self):
        activity = Activity.objects.create(
            athlete=self.athlete, sport="bike", name="Old ride", start_date=timezone.now(), ftp_snapshot=200
        )
        self.assertEqual(reference_for(self.athlete, "bike_power", activity=activity), 200)

    def test_pace_snapshot_is_parsed_from_mmss_same_as_the_live_field(self):
        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="run",
            name="Old run",
            start_date=timezone.now(),
            threshold_pace_snapshot="4:30",
        )
        self.assertEqual(reference_for(self.athlete, "pace", activity=activity), 270)

    def test_heart_rate_ignores_activity_and_always_reads_live(self):
        activity = Activity.objects.create(
            athlete=self.athlete, sport="bike", name="Old ride", start_date=timezone.now(), ftp_snapshot=200
        )
        self.assertEqual(reference_for(self.athlete, "heart_rate", activity=activity), 160)

    def test_a_null_snapshot_returns_none_rather_than_falling_back_to_the_live_profile(self):
        # An activity with no snapshot (e.g. pre-dating this feature and never backfilled)
        # should read as "unknown," not silently fall back to the athlete's current FTP -
        # that fallback-to-live behavior is exactly what activity-scoping exists to avoid.
        activity = Activity.objects.create(
            athlete=self.athlete, sport="bike", name="No snapshot", start_date=timezone.now()
        )
        self.assertIsNone(reference_for(self.athlete, "bike_power", activity=activity))


class ZoneSetActivityScopeTests(TestCase):
    """?activity_id= scopes bike_power/run_power/pace's reference to that activity's own
    threshold snapshot instead of the athlete's current (possibly since-changed) profile."""

    def setUp(self):
        self.athlete = User.objects.create_user(
            email="zone-scope@example.cc", password="x", name="Athlete", ftp=250, lthr=160
        )
        self.old_bike_activity = Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name="Old ride",
            start_date=timezone.now(),
            ftp_snapshot=200,
        )
        # The athlete's FTP has since gone up - the old activity's own snapshot should win.
        self.athlete.ftp = 250
        self.athlete.save(update_fields=["ftp"])

    def test_without_activity_id_uses_the_athletes_current_profile(self):
        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}/zones")
        bike = next(z for z in response.json()["data"] if z["type"] == "bike_power")
        self.assertEqual(bike["reference"], 250)

    def test_with_activity_id_uses_that_activitys_own_snapshot(self):
        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/zones?activity_id={self.old_bike_activity.id}"
        )
        bike = next(z for z in response.json()["data"] if z["type"] == "bike_power")
        self.assertEqual(bike["reference"], 200)

    def test_heart_rate_reference_is_unaffected_by_activity_id(self):
        # lthr isn't snapshotted per-activity - always live, with or without activity_id.
        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/zones?activity_id={self.old_bike_activity.id}"
        )
        hr = next(z for z in response.json()["data"] if z["type"] == "heart_rate")
        self.assertEqual(hr["reference"], 160)

    def test_activity_id_belonging_to_another_athlete_404s(self):
        other = User.objects.create_user(email="zone-scope-other@example.cc", password="x", name="Other")
        other_activity = Activity.objects.create(
            athlete=other, sport="bike", name="Not yours", start_date=timezone.now(), ftp_snapshot=999
        )
        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/zones?activity_id={other_activity.id}"
        )
        self.assertEqual(response.status_code, 404)


class RecomputeAthleteTssViewTests(TestCase):
    """The bulk 'Recompute TSS' action - the actual bug this snapshot feature fixes: it used
    to silently re-rate every historical activity against the athlete's CURRENT FTP."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="bulk-tss@example.cc", password="x", name="Athlete", ftp=300)

    def test_recompute_uses_each_activitys_own_snapshot_not_the_current_profile(self):
        from activities.models import Record

        activity = Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name="Old ride",
            start_date=timezone.now(),
            moving_time=3600,
            tss=0,
            ftp_snapshot=200,  # what FTP actually was when this ride happened
        )
        for t in range(3600):
            Record.objects.create(activity=activity, t=t, ts=activity.start_date + timedelta(seconds=t), power=200)

        response = _bearer_client(self.athlete).post(f"/v1/athletes/{self.athlete.id}/recompute-tss")
        self.assertEqual(response.status_code, 200)

        activity.refresh_from_db()
        # 200W normalized power at a 200W snapshot FTP = 100 TSS for a 1-hour ride - NOT the
        # ~44 TSS a re-rate against the athlete's current 300 FTP would have silently produced.
        self.assertEqual(activity.tss, 100)


class BestEffortListViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")

        recent_activity = Activity.objects.create(
            athlete=self.athlete, sport="bike", name="Ride", start_date=timezone.now(), moving_time=300, distance_km=10
        )
        BestEffort.objects.create(
            athlete=self.athlete,
            kind="cycling_power",
            window="5min",
            value=300,
            unit="watts",
            date=date.today(),
            activity=recent_activity,
        )

        old_activity = Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name="Old Ride",
            start_date=timezone.now() - timedelta(days=400),
            moving_time=300,
            distance_km=10,
        )
        BestEffort.objects.create(
            athlete=self.athlete,
            kind="cycling_power",
            window="20min",
            value=250,
            unit="watts",
            date=date.today() - timedelta(days=400),
            activity=old_activity,
        )

    def test_list_requires_kind(self):
        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}/best-efforts")
        self.assertEqual(response.status_code, 400)

    def test_list_all_period_returns_everything(self):
        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}/best-efforts?kind=cycling_power")
        body = response.json()
        self.assertEqual(body["kind"], "cycling_power")
        self.assertEqual(body["period"], "all")
        windows = {e["window"] for e in body["data"]}
        self.assertEqual(windows, {"5min", "20min"})

    def test_period_filters_by_date(self):
        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/best-efforts?kind=cycling_power&period=1y"
        )
        windows = {e["window"] for e in response.json()["data"]}
        self.assertEqual(windows, {"5min"})

    def test_unknown_kind_400(self):
        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}/best-efforts?kind=bogus")
        self.assertEqual(response.status_code, 400)

    def test_period_filter_returns_a_recent_non_all_time_record(self):
        # Two old, fast efforts occupy the all-time top-2 for this window; a slower but recent
        # one is 3rd all-time. Trimming with top_n=2 must still retain it (it's a top-2 record
        # within the last 90 days), and the period=3m filter should then return it.
        old1 = Activity.objects.create(
            athlete=self.athlete, sport="run", name="Old fast run 1", start_date=timezone.now() - timedelta(days=1000)
        )
        BestEffort.objects.create(
            athlete=self.athlete,
            kind="running_pace",
            window="10km",
            value=200.0,
            unit="sec_per_km",
            date=date.today() - timedelta(days=1000),
            activity=old1,
        )
        old2 = Activity.objects.create(
            athlete=self.athlete, sport="run", name="Old fast run 2", start_date=timezone.now() - timedelta(days=900)
        )
        BestEffort.objects.create(
            athlete=self.athlete,
            kind="running_pace",
            window="10km",
            value=210.0,
            unit="sec_per_km",
            date=date.today() - timedelta(days=900),
            activity=old2,
        )
        recent_activity = Activity.objects.create(
            athlete=self.athlete, sport="run", name="Recent slower run", start_date=timezone.now() - timedelta(days=10)
        )
        BestEffort.objects.create(
            athlete=self.athlete,
            kind="running_pace",
            window="10km",
            value=280.0,
            unit="sec_per_km",
            date=date.today() - timedelta(days=10),
            activity=recent_activity,
        )

        _trim_kind_window(self.athlete.id, "running_pace", "10km", True, top_n=2)

        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/best-efforts?kind=running_pace&period=3m"
        )
        activity_ids = {e["activity_id"] for e in response.json()["data"]}
        self.assertEqual(activity_ids, {recent_activity.id})

    def test_period_filter_caps_to_top_n_per_window_across_bands(self):
        # Trim retains up to top_n rows PER PERIOD (see the Java/Python trim tests), so a read
        # spanning multiple periods can see more survivors for one window than top_n unless the
        # read endpoint re-caps. Here a 200-days-ago band (fast, wins the wider 365-day/all-time
        # periods) and a 100-days-ago band (slower, but forms its own top-N within the narrower
        # 112-day period) both survive trim - a period=1y read spans both bands' cutoffs, so
        # without a read-side cap it would return 2*top_n rows for this one window.
        self.athlete.best_effort_top_n = 3
        self.athlete.save()

        def make(value, days_ago):
            activity = Activity.objects.create(
                athlete=self.athlete,
                sport="run",
                name=f"Run -{days_ago}d",
                start_date=timezone.now() - timedelta(days=days_ago),
            )
            return BestEffort.objects.create(
                athlete=self.athlete,
                kind="running_pace",
                window="10km",
                value=value,
                unit="sec_per_km",
                date=date.today() - timedelta(days=days_ago),
                activity=activity,
            )

        for v in (100.0, 101.0, 102.0, 103.0):
            make(v, days_ago=200)
        for v in (200.0, 201.0, 202.0, 203.0):
            make(v, days_ago=100)

        _trim_kind_window(self.athlete.id, "running_pace", "10km", True, self.athlete.best_effort_top_n)

        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/best-efforts?kind=running_pace&period=1y"
        )
        data = response.json()["data"]
        # API always orders value desc regardless of kind (see LOWER_IS_BETTER_KINDS /
        # frontend's isPR comment) - for pace that's worst-to-best, so the *set* of values is
        # the assertion that matters here, not that they're ascending.
        self.assertEqual([e["value"] for e in data], [102.0, 101.0, 100.0])

    def test_16w_period_queries_native_112_day_window(self):
        # Before native 4w/16w periods existed, the frontend faked "16 weeks" by fetching the
        # wider "1y" bucket (already capped to top_n there) and narrowing client-side to 112
        # days - which could drop entries that are genuinely top-N within 112 days but not
        # within the top-N of the full 365-day bucket. period=16w must query that exact window
        # natively instead.
        self.athlete.best_effort_top_n = 2
        self.athlete.save()

        def make(value, days_ago):
            activity = Activity.objects.create(
                athlete=self.athlete,
                sport="run",
                name=f"Run -{days_ago}d",
                start_date=timezone.now() - timedelta(days=days_ago),
            )
            return BestEffort.objects.create(
                athlete=self.athlete,
                kind="running_pace",
                window="1km",
                value=value,
                unit="sec_per_km",
                date=date.today() - timedelta(days=days_ago),
                activity=activity,
            )

        # Two very fast efforts outside the 112-day window (but inside 365) - these would
        # dominate a naive "top-2 of the last year, then narrow to 112 days" query down to
        # nothing, since neither survives the narrowing.
        make(150.0, days_ago=200)
        make(151.0, days_ago=210)
        # Two slower-but-still-notable efforts inside the last 112 days - what "16 weeks"
        # should actually show.
        recent1 = make(280.0, days_ago=20)
        recent2 = make(285.0, days_ago=50)

        _trim_kind_window(self.athlete.id, "running_pace", "1km", True, self.athlete.best_effort_top_n)

        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/best-efforts?kind=running_pace&period=16w"
        )
        activity_ids = {e["activity_id"] for e in response.json()["data"]}
        self.assertEqual(activity_ids, {recent1.activity_id, recent2.activity_id})

    def test_outsider_forbidden(self):
        response = _bearer_client(self.outsider).get(f"/v1/athletes/{self.athlete.id}/best-efforts?kind=cycling_power")
        self.assertEqual(response.status_code, 403)


class FitnessListViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="athlete@example.cc", password="x", name="Athlete")
        self.outsider = User.objects.create_user(email="outsider@example.cc", password="x", name="Outsider")
        self.day1 = date(2026, 1, 1)
        self.day2 = date(2026, 1, 2)
        Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name="Day 1",
            start_date=datetime(2026, 1, 1, 10, 0, tzinfo=UTC),
            moving_time=3600,
            distance_km=30,
            tss=100,
        )
        Activity.objects.create(
            athlete=self.athlete,
            sport="bike",
            name="Day 2",
            start_date=datetime(2026, 1, 2, 10, 0, tzinfo=UTC),
            moving_time=1800,
            distance_km=15,
            tss=50,
        )

    def test_no_activities_returns_zero_series(self):
        empty_athlete = User.objects.create_user(email="empty@example.cc", password="x", name="Empty")
        response = _bearer_client(empty_athlete).get(
            f"/v1/athletes/{empty_athlete.id}/fitness?from=2026-01-01&to=2026-01-01"
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()["data"]
        self.assertEqual(data, [{"date": "2026-01-01", "ctl": 0.0, "atl": 0.0, "tsb": 0.0}])

    def test_computes_ctl_atl_tsb_from_daily_tss(self):
        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/fitness?from=2026-01-01&to=2026-01-02"
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()["data"]
        self.assertEqual(len(data), 2)
        self.assertEqual(data[0], {"date": "2026-01-01", "ctl": 2.4, "atl": 14.3, "tsb": -11.9})
        self.assertEqual(data[1], {"date": "2026-01-02", "ctl": 3.5, "atl": 19.4, "tsb": -15.9})

    def test_narrows_to_from_to_window(self):
        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/fitness?from=2026-01-02&to=2026-01-02"
        )
        data = response.json()["data"]
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["date"], "2026-01-02")

    def test_invalid_date_returns_400(self):
        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}/fitness?from=not-a-date")
        self.assertEqual(response.status_code, 400)

    def test_from_after_to_returns_400(self):
        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/fitness?from=2026-01-02&to=2026-01-01"
        )
        self.assertEqual(response.status_code, 400)

    def test_outsider_forbidden(self):
        response = _bearer_client(self.outsider).get(f"/v1/athletes/{self.athlete.id}/fitness")
        self.assertEqual(response.status_code, 403)
