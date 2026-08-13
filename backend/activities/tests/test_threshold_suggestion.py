from datetime import UTC, datetime, timedelta

from django.test import TestCase

from accounts.models import User

from ..models import Activity, Record
from .helpers import _bearer_client, _delegated_client, _make_activity


class ActivityThresholdSuggestionViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(
            email="threshold-suggestion@example.cc", password="x", name="Athlete", ftp=200
        )
        self.outsider = User.objects.create_user(
            email="threshold-suggestion-outsider@example.cc", password="x", name="Outsider"
        )

    def _make_bike_activity_with_records(self, **kwargs):
        defaults = {
            "sport": "bike",
            "moving_time": 3600,
            "ftp_snapshot": 200,
            "suggested_ftp": 260,
            "tss": 50,
        }
        defaults.update(kwargs)
        activity = _make_activity(self.athlete, **defaults)
        for t in range(3600):
            Record.objects.create(
                activity=activity,
                t=t,
                ts=activity.start_date + timedelta(seconds=t),
                power=260,
            )
        return activity

    def test_accepting_updates_profile_and_this_activitys_own_snapshot(self):
        activity = self._make_bike_activity_with_records()

        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{activity.id}/threshold-suggestion",
            {"field": "ftp", "accept": True, "update_profile": True},
            format="json",
        )
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["ftp_snapshot"], 260)
        self.assertIsNone(body["suggested_ftp"])

        self.athlete.refresh_from_db()
        self.assertEqual(self.athlete.ftp, 260)

        activity.refresh_from_db()
        self.assertEqual(activity.ftp_snapshot, 260)
        self.assertIsNone(activity.suggested_ftp)
        # 260W at a 260W (newly-accepted) FTP for a full hour = 100 TSS.
        self.assertEqual(activity.tss, 100)

    def test_dismissing_clears_the_suggestion_without_touching_the_profile_or_snapshot(self):
        activity = self._make_bike_activity_with_records()

        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{activity.id}/threshold-suggestion", {"field": "ftp", "accept": False}, format="json"
        )
        self.assertEqual(response.status_code, 200)
        self.assertIsNone(response.json()["suggested_ftp"])

        self.athlete.refresh_from_db()
        self.assertEqual(self.athlete.ftp, 200)  # unchanged

        activity.refresh_from_db()
        self.assertEqual(activity.ftp_snapshot, 200)  # unchanged
        self.assertIsNone(activity.suggested_ftp)

    def test_no_pending_suggestion_returns_409(self):
        activity = _make_activity(self.athlete, sport="bike", ftp_snapshot=200)  # suggested_ftp left null
        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{activity.id}/threshold-suggestion",
            {"field": "ftp", "accept": True, "update_profile": True},
            format="json",
        )
        self.assertEqual(response.status_code, 409)

    def test_unknown_field_is_rejected(self):
        activity = self._make_bike_activity_with_records()
        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{activity.id}/threshold-suggestion", {"field": "lthr", "accept": True}, format="json"
        )
        self.assertEqual(response.status_code, 400)

    def test_missing_accept_boolean_is_rejected(self):
        activity = self._make_bike_activity_with_records()
        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{activity.id}/threshold-suggestion", {"field": "ftp"}, format="json"
        )
        self.assertEqual(response.status_code, 400)

    def test_outsider_cannot_act_on_a_suggestion(self):
        activity = self._make_bike_activity_with_records()
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.post(
            f"/v1/activities/{activity.id}/threshold-suggestion", {"field": "ftp", "accept": True}, format="json"
        )
        self.assertEqual(response.status_code, 403)

    def test_accepting_threshold_pace_does_not_touch_tss_or_intensity(self):
        # Neither TSS nor intensity is derived from pace anywhere in this codebase - accepting
        # a pace suggestion should only touch the profile + this activity's own pace snapshot.
        activity = _make_activity(
            self.athlete,
            sport="run",
            threshold_pace_snapshot="4:30",
            suggested_threshold_pace="4:00",
            tss=42,
            start_date=datetime(2026, 1, 1, 7, 0, tzinfo=UTC),
        )
        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{activity.id}/threshold-suggestion",
            {"field": "threshold_pace", "accept": True, "update_profile": True},
            format="json",
        )
        self.assertEqual(response.status_code, 200)
        activity.refresh_from_db()
        self.assertEqual(activity.threshold_pace_snapshot, "4:00")
        self.assertEqual(activity.suggested_threshold_pace, "")
        self.assertEqual(activity.tss, 42)  # unchanged

        self.athlete.refresh_from_db()
        self.assertEqual(self.athlete.threshold_pace, "4:00")

    def test_missing_update_profile_boolean_is_rejected_when_accepting(self):
        activity = self._make_bike_activity_with_records()
        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{activity.id}/threshold-suggestion", {"field": "ftp", "accept": True}, format="json"
        )
        self.assertEqual(response.status_code, 400)

    def test_update_profile_false_only_updates_the_activity_snapshot(self):
        # Old enough that update_profile=True would be rejected (see the test below) - but
        # update_profile=False should work regardless of age, since it never touches the profile.
        activity = self._make_bike_activity_with_records(
            start_date=datetime(2020, 1, 1, 7, 0, tzinfo=UTC),
        )

        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{activity.id}/threshold-suggestion",
            {"field": "ftp", "accept": True, "update_profile": False},
            format="json",
        )
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["ftp_snapshot"], 260)
        self.assertIsNone(body["suggested_ftp"])

        self.athlete.refresh_from_db()
        self.assertEqual(self.athlete.ftp, 200)  # unchanged

        activity.refresh_from_db()
        self.assertEqual(activity.ftp_snapshot, 260)  # this activity's own snapshot still updates
        self.assertIsNone(activity.suggested_ftp)
        # 260W at a 260W (newly-accepted) snapshot FTP for a full hour = 100 TSS - still
        # recomputed even though the profile wasn't touched.
        self.assertEqual(activity.tss, 100)

    def test_update_profile_true_rejected_for_old_activity(self):
        activity = self._make_bike_activity_with_records(
            start_date=datetime(2020, 1, 1, 7, 0, tzinfo=UTC),
        )

        response = _bearer_client(self.athlete).post(
            f"/v1/activities/{activity.id}/threshold-suggestion",
            {"field": "ftp", "accept": True, "update_profile": True},
            format="json",
        )
        self.assertEqual(response.status_code, 400)

        # Nothing mutated - the age check runs before any write.
        self.athlete.refresh_from_db()
        self.assertEqual(self.athlete.ftp, 200)

        activity.refresh_from_db()
        self.assertEqual(activity.ftp_snapshot, 200)
        self.assertEqual(activity.suggested_ftp, 260)


class RecomputeActivityThresholdsViewTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(
            email="recompute-thresholds@example.cc", password="x", name="Athlete", ftp=200
        )
        self.outsider = User.objects.create_user(
            email="recompute-thresholds-outsider@example.cc", password="x", name="Outsider"
        )

    def _make_bike_activity_with_records(self, power, **kwargs):
        defaults = {"sport": "bike", "moving_time": 1200, "ftp_snapshot": 200, "threshold_checked": False}
        defaults.update(kwargs)
        activity = _make_activity(self.athlete, **defaults)
        for t in range(1200):
            Record.objects.create(activity=activity, t=t, ts=activity.start_date + timedelta(seconds=t), power=power)
        return activity

    def test_legacy_activity_never_checked_finds_a_suggestion(self):
        # 300W for the full 20-minute window implies FTP = round(0.95 * 300) = 285, well above
        # the 200 on record.
        activity = self._make_bike_activity_with_records(power=300)
        self.assertFalse(activity.threshold_checked)

        response = _bearer_client(self.athlete).post(f"/v1/activities/{activity.id}/recompute-thresholds")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertTrue(body["threshold_checked"])
        self.assertEqual(body["suggested_ftp"], 285)

        activity.refresh_from_db()
        self.assertTrue(activity.threshold_checked)
        self.assertEqual(activity.suggested_ftp, 285)

    def test_checked_but_no_suggestion_still_flips_the_flag(self):
        # 150W never exceeds the 200W already on record - no suggestion, but "checked" is still
        # true afterwards, distinguishing "checked, found nothing" from "never checked."
        activity = self._make_bike_activity_with_records(power=150)

        response = _bearer_client(self.athlete).post(f"/v1/activities/{activity.id}/recompute-thresholds")
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertTrue(body["threshold_checked"])
        self.assertIsNone(body["suggested_ftp"])

    def test_outsider_cannot_trigger_a_recompute(self):
        activity = self._make_bike_activity_with_records(power=300)
        client = _delegated_client(self.outsider, self.athlete, scopes=["activities:read"])
        response = client.post(f"/v1/activities/{activity.id}/recompute-thresholds")
        self.assertEqual(response.status_code, 403)


class ExportIncludesSnapshotButNotSuggestedFieldsTests(TestCase):
    def test_export_carries_ftp_snapshot_but_strips_suggested_ftp(self):
        from django.core.files.storage import default_storage

        from dataexport.export_writer import write_export

        athlete = User.objects.create_user(email="export-snapshot@example.cc", password="x", name="Athlete", ftp=200)
        _make_activity(
            athlete,
            sport="bike",
            name="Ride",
            ftp_snapshot=200,
            suggested_ftp=260,
            threshold_checked=True,
            start_date=datetime(2026, 1, 1, 7, 0, tzinfo=UTC),
        )

        relative_path = "exports/test/threshold-snapshot-export.json.gz"
        write_export(athlete.id, None, relative_path)

        import gzip
        import json

        with default_storage.open(relative_path, "rb") as raw, gzip.GzipFile(fileobj=raw) as gz:
            doc = json.loads(gz.read())

        activity_data = doc["activities"][0]["activity"]
        self.assertEqual(activity_data["ftp_snapshot"], 200)
        self.assertNotIn("suggested_ftp", activity_data)
        self.assertNotIn("threshold_checked", activity_data)

        default_storage.delete(relative_path)


class ImportCarriesOverTheSourceActivitysRealSnapshotTests(TestCase):
    def test_import_uses_the_sources_own_snapshot_not_the_importing_athletes_profile(self):
        from dataexport.export_writer import write_export
        from dataexport.import_reader import read_import

        source = User.objects.create_user(
            email="snapshot-roundtrip-source@example.cc", password="x", name="Source", ftp=222
        )
        target = User.objects.create_user(
            email="snapshot-roundtrip-target@example.cc", password="x", name="Target", ftp=999
        )
        _make_activity(
            source,
            sport="bike",
            name="Ride",
            ftp_snapshot=222,
            threshold_checked=True,
            start_date=datetime(2026, 1, 1, 7, 0, tzinfo=UTC),
        )

        relative_path = "exports/test/threshold-snapshot-roundtrip.json.gz"
        write_export(source.id, None, relative_path)
        read_import(target.id, relative_path)

        imported = Activity.objects.get(athlete=target, name="Ride")
        # 222, not the importing athlete's own 999 - the export now carries the real value.
        self.assertEqual(imported.ftp_snapshot, 222)
        # threshold_checked isn't exported - a freshly-imported row starts "not yet checked"
        # regardless of whether the source row had already been checked.
        self.assertFalse(imported.threshold_checked)

        from django.core.files.storage import default_storage

        default_storage.delete(relative_path)
