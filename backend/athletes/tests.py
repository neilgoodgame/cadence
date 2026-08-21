from datetime import UTC, date, datetime, timedelta

from django.test import TestCase
from django.utils import timezone
from rest_framework.test import APIClient

from accounts.models import User, UserRelationship
from activities.models import Activity, BestEffort, Record
from authn.jwt_utils import mint_jwt
from authn.oauth_utils import issue_token_pair
from uploads.processing import _trim_kind_window

from .models import BestEffortRecomputeJob, ThresholdHistory, ZoneSet
from .threshold_history import current_window_value, is_stale, record_manual_value, refresh_field, replay_full_history
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

    def test_updating_a_threshold_creates_a_manual_ledger_entry(self):
        response = _bearer_client(self.athlete).patch(f"/v1/athletes/{self.athlete.id}", {"ftp": 280}, format="json")
        self.assertEqual(response.status_code, 200)

        entry = ThresholdHistory.objects.get(athlete=self.athlete, field="ftp")
        self.assertEqual(entry.value_numeric, 280)
        self.assertIsNone(entry.source_activity_id)
        self.assertEqual(entry.effective_from, date.today())

    def test_resubmitting_the_same_value_does_not_duplicate_the_ledger_entry(self):
        # The Preferences form resubmits every field on every save regardless of whether it was
        # actually edited - re-saving with the athlete's existing ftp=250 must not insert a
        # second entry.
        client = _bearer_client(self.athlete)
        client.patch(f"/v1/athletes/{self.athlete.id}", {"ftp": 280}, format="json")
        client.patch(f"/v1/athletes/{self.athlete.id}", {"ftp": 280, "weight_kg": 70.0}, format="json")

        self.assertEqual(ThresholdHistory.objects.filter(athlete=self.athlete, field="ftp").count(), 1)

    def test_updating_threshold_pace_creates_a_manual_ledger_entry(self):
        response = _bearer_client(self.athlete).patch(
            f"/v1/athletes/{self.athlete.id}", {"threshold_pace": "3:45"}, format="json"
        )
        self.assertEqual(response.status_code, 200)

        entry = ThresholdHistory.objects.get(athlete=self.athlete, field="threshold_pace")
        self.assertEqual(entry.value_pace, "3:45")
        self.assertIsNone(entry.source_activity_id)

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

    def test_with_activity_reads_the_ledger_entry_effective_at_that_time(self):
        activity = Activity.objects.create(
            athlete=self.athlete, sport="bike", name="Old ride", start_date=timezone.now()
        )
        ThresholdHistory.objects.create(
            athlete=self.athlete,
            field="ftp",
            value_numeric=200,
            source_activity=activity,
            effective_from=activity.start_date.date(),
        )
        self.assertEqual(reference_for(self.athlete, "bike_power", activity=activity), 200)

    def test_pace_entry_is_parsed_from_mmss_same_as_the_live_field(self):
        activity = Activity.objects.create(athlete=self.athlete, sport="run", name="Old run", start_date=timezone.now())
        ThresholdHistory.objects.create(
            athlete=self.athlete,
            field="threshold_pace",
            value_pace="4:30",
            source_activity=activity,
            effective_from=activity.start_date.date(),
        )
        self.assertEqual(reference_for(self.athlete, "pace", activity=activity), 270)

    def test_heart_rate_ignores_activity_and_always_reads_live(self):
        activity = Activity.objects.create(
            athlete=self.athlete, sport="bike", name="Old ride", start_date=timezone.now()
        )
        ThresholdHistory.objects.create(
            athlete=self.athlete,
            field="ftp",
            value_numeric=200,
            source_activity=activity,
            effective_from=activity.start_date.date(),
        )
        self.assertEqual(reference_for(self.athlete, "heart_rate", activity=activity), 160)

    def test_no_ledger_entry_returns_none_rather_than_falling_back_to_the_live_profile(self):
        # An activity with no history entry effective at its own date (e.g. predating this
        # feature) should read as "unknown," not silently fall back to the athlete's current
        # FTP - that fallback-to-live behavior is exactly what activity-scoping exists to avoid.
        activity = Activity.objects.create(
            athlete=self.athlete, sport="bike", name="No history", start_date=timezone.now()
        )
        self.assertIsNone(reference_for(self.athlete, "bike_power", activity=activity))


class ZoneSetActivityScopeTests(TestCase):
    """?activity_id= scopes bike_power/run_power/pace's reference to the ledger entry effective
    at that activity's own date instead of the athlete's current (possibly since-changed) profile."""

    def setUp(self):
        self.athlete = User.objects.create_user(
            email="zone-scope@example.cc", password="x", name="Athlete", ftp=250, lthr=160
        )
        self.old_bike_activity = Activity.objects.create(
            athlete=self.athlete, sport="bike", name="Old ride", start_date=timezone.now()
        )
        ThresholdHistory.objects.create(
            athlete=self.athlete,
            field="ftp",
            value_numeric=200,
            source_activity=self.old_bike_activity,
            effective_from=self.old_bike_activity.start_date.date(),
        )
        # The athlete's FTP has since gone up - the old activity's own ledger entry should win.
        self.athlete.ftp = 250
        self.athlete.save(update_fields=["ftp"])

    def test_without_activity_id_uses_the_athletes_current_profile(self):
        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}/zones")
        bike = next(z for z in response.json()["data"] if z["type"] == "bike_power")
        self.assertEqual(bike["reference"], 250)

    def test_with_activity_id_uses_that_activitys_own_ledger_entry(self):
        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/zones?activity_id={self.old_bike_activity.id}"
        )
        bike = next(z for z in response.json()["data"] if z["type"] == "bike_power")
        self.assertEqual(bike["reference"], 200)

    def test_heart_rate_reference_is_unaffected_by_activity_id(self):
        # lthr has no history ledger of its own - always live, with or without activity_id.
        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/zones?activity_id={self.old_bike_activity.id}"
        )
        hr = next(z for z in response.json()["data"] if z["type"] == "heart_rate")
        self.assertEqual(hr["reference"], 160)

    def test_activity_id_belonging_to_another_athlete_404s(self):
        other = User.objects.create_user(email="zone-scope-other@example.cc", password="x", name="Other")
        other_activity = Activity.objects.create(
            athlete=other, sport="bike", name="Not yours", start_date=timezone.now()
        )
        response = _bearer_client(self.athlete).get(
            f"/v1/athletes/{self.athlete.id}/zones?activity_id={other_activity.id}"
        )
        self.assertEqual(response.status_code, 404)


class RecomputeAthleteTssViewTests(TestCase):
    """The bulk 'Recompute TSS' action - the actual bug this ledger feature fixes: it used
    to silently re-rate every historical activity against the athlete's CURRENT FTP."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="bulk-tss@example.cc", password="x", name="Athlete", ftp=300)

    def test_recompute_uses_each_activitys_own_historical_value_not_the_current_profile(self):
        from activities.models import Record

        activity = Activity.objects.create(
            athlete=self.athlete, sport="bike", name="Old ride", start_date=timezone.now(), moving_time=3600, tss=0
        )
        ThresholdHistory.objects.create(
            athlete=self.athlete,
            field="ftp",
            value_numeric=200,  # what FTP actually was when this ride happened
            source_activity=activity,
            effective_from=activity.start_date.date(),
        )
        for t in range(3600):
            Record.objects.create(activity=activity, t=t, ts=activity.start_date + timedelta(seconds=t), power=200)

        response = _bearer_client(self.athlete).post(f"/v1/athletes/{self.athlete.id}/recompute-tss")
        self.assertEqual(response.status_code, 200)

        activity.refresh_from_db()
        # 200W normalized power at a 200W historical FTP = 100 TSS for a 1-hour ride - NOT the
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


class ThresholdHistoryAlgorithmTests(TestCase):
    """current_window_value/replay_full_history (threshold_history.py) - the pure rolling-window
    algorithm, tested independently of any endpoint. Default threshold_window_days=112,
    threshold_sanity_pct=30 unless a test overrides them."""

    def setUp(self):
        self.athlete = User.objects.create_user(
            email="threshold-history@example.cc", password="x", name="Athlete", ftp=200
        )

    def _make_power_activity(self, sport, start_date, power, duration_seconds=1200, name="Ride"):
        activity = Activity.objects.create(
            athlete=self.athlete, sport=sport, name=name, start_date=start_date, moving_time=duration_seconds
        )
        for t in range(duration_seconds):
            Record.objects.create(activity=activity, t=t, ts=start_date + timedelta(seconds=t), power=power)
        return activity

    def _make_pace_activity(self, start_date, pace_seconds_per_km, duration_seconds=3600, name="Run"):
        activity = Activity.objects.create(
            athlete=self.athlete, sport="run", name=name, start_date=start_date, moving_time=duration_seconds
        )
        for t in range(duration_seconds + 1):
            Record.objects.create(
                activity=activity, t=t, ts=start_date + timedelta(seconds=t), distance_km=t / pace_seconds_per_km
            )
        return activity

    # --- current_window_value ---

    def test_picks_best_qualifying_activity_within_window(self):
        # Both within the default 30% sanity band around the athlete's ftp=200 (140-260) - this
        # test is about picking the best *qualifying* candidate, not sanity filtering.
        self._make_power_activity("bike", datetime(2026, 5, 1, 7, 0, tzinfo=UTC), power=230)
        strong = self._make_power_activity("bike", datetime(2026, 5, 15, 7, 0, tzinfo=UTC), power=250)

        candidate = current_window_value(self.athlete, "ftp", as_of=date(2026, 6, 1))

        self.assertIsNotNone(candidate)
        self.assertEqual(candidate.activity_id, strong.id)
        self.assertEqual(candidate.implied_value, round(0.95 * 250))

    def test_ignores_activities_outside_window(self):
        self._make_power_activity("bike", datetime(2025, 1, 1, 7, 0, tzinfo=UTC), power=400)
        recent = self._make_power_activity("bike", datetime(2026, 5, 15, 7, 0, tzinfo=UTC), power=250)

        candidate = current_window_value(self.athlete, "ftp", as_of=date(2026, 6, 1))

        self.assertEqual(candidate.activity_id, recent.id)

    def test_ignores_wrong_sport(self):
        self._make_power_activity("run", datetime(2026, 5, 15, 7, 0, tzinfo=UTC), power=500)  # not a bike ride

        candidate = current_window_value(self.athlete, "ftp", as_of=date(2026, 6, 1))

        self.assertIsNone(candidate)

    def test_short_activity_does_not_qualify(self):
        # 10 minutes - shorter than the 20-minute FTP test window.
        self._make_power_activity("bike", datetime(2026, 5, 15, 7, 0, tzinfo=UTC), power=400, duration_seconds=600)

        candidate = current_window_value(self.athlete, "ftp", as_of=date(2026, 6, 1))

        self.assertIsNone(candidate)

    def test_excludes_outlier_via_sanity_check(self):
        # Athlete's current FTP is 200 - a 20-minute effort implying ~401 (100%+ higher, well
        # past the default 30% sanity band) is treated as implausible (e.g. corrupt power data).
        self._make_power_activity("bike", datetime(2026, 5, 15, 7, 0, tzinfo=UTC), power=422)

        candidate = current_window_value(self.athlete, "ftp", as_of=date(2026, 6, 1))

        self.assertIsNone(candidate)

    def test_first_ever_value_has_no_sanity_check(self):
        athlete = User.objects.create_user(email="threshold-history-fresh@example.cc", password="x", name="Fresh")
        start_date = datetime(2026, 5, 15, 7, 0, tzinfo=UTC)
        activity = Activity.objects.create(
            athlete=athlete, sport="bike", name="Ride", start_date=start_date, moving_time=1200
        )
        for t in range(1200):
            Record.objects.create(activity=activity, t=t, ts=start_date + timedelta(seconds=t), power=500)

        candidate = current_window_value(athlete, "ftp", as_of=date(2026, 6, 1))

        self.assertIsNotNone(candidate)  # no reference yet - nothing to sanity-check against
        self.assertEqual(candidate.implied_value, round(0.95 * 500))

    def test_sixty_min_direct_uses_raw_sixty_minute_power_not_the_twenty_minute_multiplier(self):
        self.athlete.ftp_calculation_method = "sixty_min_direct"
        self.athlete.save(update_fields=["ftp_calculation_method"])
        self._make_power_activity("bike", datetime(2026, 5, 15, 7, 0, tzinfo=UTC), power=240, duration_seconds=3600)

        candidate = current_window_value(self.athlete, "ftp", as_of=date(2026, 6, 1))

        # The raw 60-min average (240), not 0.95 * 240 (228) - the twenty_min_test default's math.
        self.assertIsNotNone(candidate)
        self.assertEqual(candidate.implied_value, 240)

    def test_sixty_min_direct_does_not_qualify_from_a_20_minute_effort(self):
        self.athlete.ftp_calculation_method = "sixty_min_direct"
        self.athlete.save(update_fields=["ftp_calculation_method"])
        # Long enough for twenty_min_test, too short for a real 60-minute window.
        self._make_power_activity("bike", datetime(2026, 5, 15, 7, 0, tzinfo=UTC), power=240, duration_seconds=1200)

        candidate = current_window_value(self.athlete, "ftp", as_of=date(2026, 6, 1))

        self.assertIsNone(candidate)

    def test_pace_lower_is_better(self):
        self.athlete.threshold_pace = "5:00"
        self.athlete.save(update_fields=["threshold_pace"])
        self._make_pace_activity(datetime(2026, 5, 1, 7, 0, tzinfo=UTC), pace_seconds_per_km=280)  # 4:40/km
        faster = self._make_pace_activity(datetime(2026, 5, 15, 7, 0, tzinfo=UTC), pace_seconds_per_km=270)  # 4:30/km

        candidate = current_window_value(self.athlete, "threshold_pace", as_of=date(2026, 6, 1))

        self.assertEqual(candidate.activity_id, faster.id)
        self.assertAlmostEqual(candidate.implied_value, 270, delta=1)

    # --- replay_full_history ---

    def test_replay_builds_ledger_of_changes_over_time(self):
        first = self._make_power_activity("bike", datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=210)
        second = self._make_power_activity("bike", datetime(2026, 2, 1, 7, 0, tzinfo=UTC), power=230)

        entries = replay_full_history(self.athlete, "ftp")

        self.assertEqual(len(entries), 2)
        self.assertEqual(entries[0].activity_id, first.id)
        self.assertEqual(entries[0].value, round(0.95 * 210))
        self.assertEqual(entries[1].activity_id, second.id)
        self.assertEqual(entries[1].value, round(0.95 * 230))

    def test_replay_drops_value_when_source_ages_out(self):
        strong = self._make_power_activity("bike", datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=250)
        # >112 days later - the earlier ride has aged out of the window by the time this weaker
        # (but still plausible - within the sanity band) one is processed, so it becomes the new,
        # lower current value.
        weak = self._make_power_activity("bike", datetime(2026, 8, 1, 7, 0, tzinfo=UTC), power=220)

        entries = replay_full_history(self.athlete, "ftp")

        self.assertEqual(len(entries), 2)
        self.assertEqual(entries[0].activity_id, strong.id)
        self.assertEqual(entries[1].activity_id, weak.id)
        self.assertLess(entries[1].value, entries[0].value)

    def test_replay_excludes_outlier_from_ledger(self):
        self._make_power_activity("bike", datetime(2026, 1, 1, 7, 0, tzinfo=UTC), power=210)
        self._make_power_activity("bike", datetime(2026, 1, 15, 7, 0, tzinfo=UTC), power=500)  # implausible spike

        entries = replay_full_history(self.athlete, "ftp")

        self.assertEqual(len(entries), 1)  # the spike never enters the ledger

    def test_replay_gradual_improvement_not_blocked_by_cumulative_sanity_check(self):
        # Each step is a plausible jump from the *previous* step, but the total change across all
        # steps (200 -> 285, +42.5%) would fail a naive "vs. the original value" sanity check -
        # checking each candidate against the running reference (not the very first value) must
        # not block genuine, gradual improvement like this.
        start_date = datetime(2026, 1, 1, 7, 0, tzinfo=UTC)
        powers = [210, 240, 270, 300]
        for i, power in enumerate(powers):
            self._make_power_activity("bike", start_date + timedelta(days=30 * i), power=power)

        entries = replay_full_history(self.athlete, "ftp")

        self.assertEqual(len(entries), len(powers))
        self.assertEqual(entries[-1].value, round(0.95 * powers[-1]))


class RecordManualValueTests(TestCase):
    """record_manual_value - a manually-entered threshold (see AthleteDetailView.patch) is
    trusted unconditionally and functions as an initial value (or a correction) exactly like any
    other ledger entry from that point on."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="manual-threshold@example.cc", password="x", name="Athlete")

    def test_seeds_the_initial_value_when_no_entry_exists(self):
        changed = record_manual_value(self.athlete, "ftp", 250, as_of=date(2026, 6, 1))

        self.assertTrue(changed)
        entry = ThresholdHistory.objects.get(athlete=self.athlete, field="ftp")
        self.assertEqual(entry.value_numeric, 250)
        self.assertIsNone(entry.source_activity)
        self.assertEqual(entry.effective_from, date(2026, 6, 1))

    def test_no_op_when_the_value_matches_the_latest_entry(self):
        record_manual_value(self.athlete, "ftp", 250, as_of=date(2026, 6, 1))

        changed = record_manual_value(self.athlete, "ftp", 250, as_of=date(2026, 6, 15))

        self.assertFalse(changed)
        self.assertEqual(ThresholdHistory.objects.filter(athlete=self.athlete, field="ftp").count(), 1)

    def test_records_a_correction_even_though_it_is_a_decrease(self):
        # No sanity-band check for a manual entry - a human directly declaring a number is
        # trusted, unlike an automatically-detected candidate that needs outlier protection.
        record_manual_value(self.athlete, "ftp", 300, as_of=date(2026, 6, 1))

        changed = record_manual_value(self.athlete, "ftp", 180, as_of=date(2026, 6, 15))

        self.assertTrue(changed)
        entry = ThresholdHistory.objects.filter(athlete=self.athlete, field="ftp").order_by("-effective_from").first()
        self.assertEqual(entry.value_numeric, 180)

    def test_threshold_pace_stores_the_mmss_string(self):
        changed = record_manual_value(self.athlete, "threshold_pace", "4:15", as_of=date(2026, 6, 1))

        self.assertTrue(changed)
        entry = ThresholdHistory.objects.get(athlete=self.athlete, field="threshold_pace")
        self.assertEqual(entry.value_pace, "4:15")
        self.assertIsNone(entry.value_numeric)

    def test_a_manual_entry_becomes_the_activity_scoped_reference_going_forward(self):
        record_manual_value(self.athlete, "ftp", 250, as_of=date(2026, 6, 1))
        activity = Activity.objects.create(
            athlete=self.athlete, sport="bike", name="Ride", start_date=datetime(2026, 6, 10, 7, 0, tzinfo=UTC)
        )

        self.assertEqual(reference_for(self.athlete, "bike_power", activity=activity), 250)

    def test_a_manual_entry_becomes_stale_after_the_window(self):
        self.athlete.threshold_window_days = 112
        self.athlete.save(update_fields=["threshold_window_days"])
        record_manual_value(self.athlete, "ftp", 250, as_of=date(2026, 1, 1))

        self.assertFalse(is_stale(self.athlete, "ftp", as_of=date(2026, 4, 1)))  # 90 days later
        self.assertTrue(is_stale(self.athlete, "ftp", as_of=date(2026, 5, 1)))  # 120 days later


class RefreshFieldTests(TestCase):
    """refresh_field / _recompute_and_record - the athlete-triggered "this value is stale,
    refresh now" recompute, as distinct from record_manual_value's trusted-unconditionally
    write above."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="refresh-field@example.cc", password="x", name="Athlete")

    def _make_power_activity(self, start_date, power, duration_seconds=1200):
        activity = Activity.objects.create(
            athlete=self.athlete, sport="bike", name="Ride", start_date=start_date, moving_time=duration_seconds
        )
        for t in range(duration_seconds):
            Record.objects.create(activity=activity, t=t, ts=start_date + timedelta(seconds=t), power=power)
        return activity

    # Regression test for a real bug found live (Java backend, same algorithm): a manually-
    # entered value effective from today permanently outranks any activity-based candidate dated
    # earlier than today, however different its value - _recompute_and_record used to record it
    # anyway, appending a dead row that could never actually become current (see is_stale/the
    # dashboard summary: "current" is whichever row has the latest effective_from) and would keep
    # re-inserting itself, with a new id, every time the ingest hook or a manual refresh
    # re-evaluated the same activity. Seen for real: a stale manual FTP entry (255W) outranked a
    # genuine 225W ride-based candidate dated three weeks earlier, and every re-trigger silently
    # added another identical dead 225W row.
    def test_does_not_record_a_candidate_dated_before_the_current_entry(self):
        today = date.today()
        record_manual_value(self.athlete, "ftp", 255, as_of=today)
        # 237W best-20min * 0.95 FTP_TEST_MULTIPLIER = 225.15, rounds to 225 - deliberately
        # different from the manual 255 so a naive value-only diff check would record it. Dated
        # a month before today so it's well within the default 112-day window but still earlier
        # than the manual entry above.
        self._make_power_activity(
            datetime(today.year, today.month, today.day, 7, 0, tzinfo=UTC) - timedelta(days=30), power=237
        )

        changed = refresh_field(self.athlete, "ftp")

        self.assertFalse(changed)
        entries = ThresholdHistory.objects.filter(athlete=self.athlete, field="ftp")
        self.assertEqual(entries.count(), 1)
        self.assertEqual(entries.get().value_numeric, 255)


class BestEffortRecomputeJobViewTests(TestCase):
    """The recompute endpoint runs via a Celery job + polling, not a synchronous
    StreamingHttpResponse (see athletes/tasks.py's run_best_effort_recompute) - a full
    account can take longer than gunicorn's sync-worker timeout otherwise. Under
    CELERY_TASK_ALWAYS_EAGER (settings_test.py), .delay() runs synchronously, so the job
    is already terminal by the time the POST response returns - these tests exercise the
    real end-to-end path (view -> task -> DB), not a mocked one."""

    def setUp(self):
        # ftp is required: _update_power_best_efforts only runs for cycling_power when
        # athlete.ftp is set (see uploads/processing.py's compute_kind_best_efforts).
        self.athlete = User.objects.create_user(email="ber-athlete@example.cc", password="x", name="Athlete", ftp=250)
        self.outsider = User.objects.create_user(email="ber-outsider@example.cc", password="x", name="Outsider")

        # A full hour, matching RecomputeAthleteTssViewTests' convention - the shortest
        # cycling_power window is 5s, but fewer than ~300+ continuous seconds risks missing
        # the 5min window by an off-by-one on t's span rather than testing anything real.
        self.activity = Activity.objects.create(
            athlete=self.athlete, sport="bike", name="Ride", start_date=timezone.now(), moving_time=3600
        )
        for t in range(3600):
            Record.objects.create(
                activity=self.activity, t=t, ts=self.activity.start_date + timedelta(seconds=t), power=250
            )

    def test_start_returns_202_and_the_job_completes_synchronously_under_eager_mode(self):
        response = _bearer_client(self.athlete).post(f"/v1/athletes/{self.athlete.id}/best-efforts/recompute")

        self.assertEqual(response.status_code, 202)
        body = response.json()
        self.assertEqual(body["object"], "best_effort_recompute_job")
        self.assertIn("Location", response.headers)

        job = BestEffortRecomputeJob.objects.get(pk=body["id"])
        self.assertEqual(job.status, "ready")
        self.assertEqual(job.processed_items, 1)
        self.assertEqual(job.total_items, 1)
        self.assertIsNotNone(job.completed_at)
        self.assertTrue(BestEffort.objects.filter(athlete=self.athlete, activity=self.activity).exists())

    def test_polling_the_job_returns_its_current_state(self):
        start = _bearer_client(self.athlete).post(f"/v1/athletes/{self.athlete.id}/best-efforts/recompute")
        job_id = start.json()["id"]

        response = _bearer_client(self.athlete).get(f"/v1/athletes/{self.athlete.id}/best-efforts/recompute/{job_id}")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "ready")
        # Terminal jobs don't ask the poller to keep going.
        self.assertNotIn("Retry-After", response.headers)

    def test_kind_scopes_which_activities_and_best_efforts_get_recomputed(self):
        run = Activity.objects.create(
            athlete=self.athlete, sport="run", name="Run", start_date=timezone.now(), moving_time=3600
        )
        for t in range(3600):
            Record.objects.create(activity=run, t=t, ts=run.start_date + timedelta(seconds=t), heartrate=150)

        response = _bearer_client(self.athlete).post(
            f"/v1/athletes/{self.athlete.id}/best-efforts/recompute?kind=cycling_power"
        )
        job = BestEffortRecomputeJob.objects.get(pk=response.json()["id"])

        self.assertEqual(job.kind, "cycling_power")
        self.assertEqual(job.total_items, 1)  # only the bike activity, not the run
        self.assertTrue(BestEffort.objects.filter(athlete=self.athlete, kind="cycling_power").exists())
        self.assertFalse(BestEffort.objects.filter(athlete=self.athlete, kind="running_hr").exists())

    def test_invalid_kind_400(self):
        response = _bearer_client(self.athlete).post(
            f"/v1/athletes/{self.athlete.id}/best-efforts/recompute?kind=not-a-real-kind"
        )
        self.assertEqual(response.status_code, 400)

    def test_outsider_cannot_start_or_poll(self):
        start = _bearer_client(self.outsider).post(f"/v1/athletes/{self.athlete.id}/best-efforts/recompute")
        self.assertEqual(start.status_code, 403)

        job = BestEffortRecomputeJob.objects.create(athlete=self.athlete)
        poll = _bearer_client(self.outsider).get(f"/v1/athletes/{self.athlete.id}/best-efforts/recompute/{job.id}")
        self.assertEqual(poll.status_code, 403)
