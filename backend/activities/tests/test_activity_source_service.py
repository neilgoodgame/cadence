"""Issue #503 (phase 1): attach_power_stream/set_primary_source/list_sources, and the REST
surfaces built on top of them. Mirrors backend_java's ActivitySourceServiceIntegrationTest."""

from datetime import UTC, datetime, timedelta

from django.test import TestCase

from accounts.models import User

from .. import activity_source_service
from ..models import ActivitySource, Record
from .helpers import _bearer_client, _make_activity


class ActivitySourceServiceTests(TestCase):
    def setUp(self):
        self.start = datetime(2026, 6, 1, 18, 0, 0, tzinfo=UTC)

    def _athlete(self, email, **kwargs):
        return User.objects.create_user(email=email, password="x", name="Athlete", **kwargs)

    def _activity(self, athlete, device, **kwargs):
        return _make_activity(athlete, sport="bike", start_date=self.start, moving_time=600, device=device, **kwargs)

    def _add_records(self, activity, count, power):
        for t in range(count):
            Record.objects.create(activity=activity, t=t, ts=activity.start_date + timedelta(seconds=t), power=power)

    def test_attach_creates_a_non_primary_source_without_touching_the_targets_primary(self):
        athlete = self._athlete("source-attach-no-touch@example.cc")
        target = self._activity(athlete, "Edge 530", name="Target")
        source = self._activity(athlete, "Zwift", name="Source")
        self._add_records(target, 10, power=200)
        self._add_records(source, 10, power=300)

        result = activity_source_service.attach_power_stream(target.id, source.id, offset_seconds=0, preview=False)

        self.assertIsNotNone(result.source_id)
        self.assertEqual(result.copied, 10)
        self.assertEqual(result.nulled, 0)
        # Default bike_power_source is "any" - no preference, so attach never auto-swaps.
        self.assertFalse(result.became_primary)

        for record in target.records.all():
            self.assertEqual(record.power, 200)

        saved_source = ActivitySource.objects.get(pk=result.source_id)
        self.assertEqual(saved_source.origin, "attached")
        self.assertEqual(saved_source.sensor_kind, "power")
        self.assertEqual(saved_source.device, "Zwift")

        stream = saved_source.streams.get(field="power")
        self.assertFalse(stream.primary_of)
        self.assertEqual(len(stream.values), 10)
        self.assertEqual(stream.values[0], 300)

    def test_attach_auto_swaps_when_the_attached_source_matches_the_preference(self):
        athlete = self._athlete("source-attach-auto-swap@example.cc", bike_power_source="trainer")
        target = self._activity(athlete, "Edge 530", name="Target")  # classifies pedals
        source = self._activity(athlete, "Zwift", name="Source")  # classifies trainer
        self._add_records(target, 10, power=200)
        self._add_records(source, 10, power=300)

        result = activity_source_service.attach_power_stream(target.id, source.id, offset_seconds=0, preview=False)

        self.assertTrue(result.became_primary)
        for record in target.records.all():
            self.assertEqual(record.power, 300)
        target.refresh_from_db()
        self.assertEqual(target.bike_power_source, "trainer")

    def test_set_primary_source_swaps_record_values_and_can_be_reversed(self):
        athlete = self._athlete("source-set-primary-reversible@example.cc")
        target = self._activity(athlete, "Edge 530", name="Target")
        source = self._activity(athlete, "Zwift", name="Source")
        self._add_records(target, 10, power=200)
        self._add_records(source, 10, power=300)
        attach = activity_source_service.attach_power_stream(target.id, source.id, offset_seconds=0, preview=False)
        self.assertFalse(attach.became_primary)  # "any" preference by default - no auto-swap yet

        activity_source_service.set_primary_source(target.id, "power", attach.source_id)

        for record in target.records.all():
            self.assertEqual(record.power, 300)
        target.refresh_from_db()
        self.assertEqual(target.bike_power_source, "trainer")

        # The original ("Edge 530", 200) primary was itself snapshotted as a new source, not lost.
        original_snapshot = ActivitySource.objects.get(activity=target, origin="file")
        self.assertEqual(original_snapshot.device, "Edge 530")

        activity_source_service.set_primary_source(target.id, "power", original_snapshot.id)

        for record in target.records.all():
            self.assertEqual(record.power, 200)
        target.refresh_from_db()
        self.assertEqual(target.bike_power_source, "pedals")

    def test_list_sources_reports_is_primary_against_the_activitys_current_primary(self):
        athlete = self._athlete("source-list-is-primary@example.cc")
        target = self._activity(athlete, "Edge 530", name="Target")  # classifies pedals
        source = self._activity(athlete, "Zwift", name="Source")  # classifies trainer
        self._add_records(target, 10, power=200)
        self._add_records(source, 10, power=300)
        attach = activity_source_service.attach_power_stream(target.id, source.id, offset_seconds=0, preview=False)

        summaries = activity_source_service.list_sources(target.id)

        self.assertEqual(len(summaries), 1)
        # Target's current primary is still its own pedals reading - the attached trainer source
        # doesn't match it (yet).
        self.assertEqual(summaries[0].id, attach.source_id)
        self.assertFalse(summaries[0].is_primary)

        activity_source_service.set_primary_source(target.id, "power", attach.source_id)

        summaries_after_swap = activity_source_service.list_sources(target.id)
        matching = next(s for s in summaries_after_swap if s.id == attach.source_id)
        self.assertTrue(matching.is_primary)

    def test_merge_endpoint_with_attach_copy_mode_creates_a_source(self):
        athlete = self._athlete("source-rest-attach@example.cc")
        target = self._activity(athlete, "Edge 530", name="Target")
        source = self._activity(athlete, "Zwift", name="Source")
        self._add_records(target, 10, power=200)
        self._add_records(source, 10, power=300)

        response = _bearer_client(athlete).post(
            f"/v1/activities/{target.id}/merge",
            {
                "source_id": source.id,
                "fields": ["power"],
                "null_policy": "keep_original",
                "offset_seconds": 0,
                "preview": False,
                "copy_mode": "attach",
            },
            format="json",
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertIsNone(body["merge_id"])
        self.assertIsNotNone(body["attached_source_id"])
        for record in target.records.all():
            self.assertEqual(record.power, 200)  # untouched

    def test_sources_and_primary_source_rest_endpoints(self):
        athlete = self._athlete("source-rest-endpoints@example.cc")
        target = self._activity(athlete, "Edge 530", name="Target")
        source = self._activity(athlete, "Zwift", name="Source")
        self._add_records(target, 10, power=200)
        self._add_records(source, 10, power=300)
        attach = activity_source_service.attach_power_stream(target.id, source.id, offset_seconds=0, preview=False)

        client = _bearer_client(athlete)
        list_response = client.get(f"/v1/activities/{target.id}/sources")
        self.assertEqual(list_response.status_code, 200)
        self.assertEqual(len(list_response.json()), 1)
        self.assertEqual(list_response.json()[0]["id"], attach.source_id)

        swap_response = client.post(
            f"/v1/activities/{target.id}/primary-source",
            {"field": "power", "source_id": attach.source_id},
            format="json",
        )
        self.assertEqual(swap_response.status_code, 200)
        target.refresh_from_db()
        self.assertEqual(target.bike_power_source, "trainer")

    def _run_activity(self, athlete, power_source):
        return _make_activity(athlete, sport="run", start_date=self.start, moving_time=600, power_source=power_source)

    def test_parse_time_dual_keep_creates_a_non_primary_source_when_both_candidates_present(self):
        athlete = self._athlete("source-parse-time-both-present@example.cc")
        activity = self._run_activity(athlete, power_source="stryd")
        self._add_records(activity, 5, power=200)  # the primary (stryd) stream, already in Record

        t_series = [0, 1, 2, 3, 4]
        other_values = [250, 251, None, 253, 254]  # native, the other candidate

        activity_source_service.create_parse_time_running_power_source(activity, t_series, other_values, "native")

        sources = list(ActivitySource.objects.filter(activity=activity))
        self.assertEqual(len(sources), 1)
        self.assertEqual(sources[0].origin, "file")
        self.assertEqual(sources[0].sensor_kind, "power")
        self.assertEqual(sources[0].running_power_kind, "native")

        stream = sources[0].streams.get(field="power")
        self.assertFalse(stream.primary_of)
        self.assertEqual(stream.values, [250, 251, None, 253, 254])

        # The primary (stryd) Record values are completely untouched.
        for record in activity.records.all():
            self.assertEqual(record.power, 200)

    def test_parse_time_dual_keep_creates_nothing_when_the_other_candidate_is_entirely_absent(self):
        athlete = self._athlete("source-parse-time-other-absent@example.cc")
        activity = self._run_activity(athlete, power_source="stryd")
        self._add_records(activity, 5, power=200)

        activity_source_service.create_parse_time_running_power_source(
            activity, [0, 1, 2, 3, 4], [None, None, None, None, None], "native"
        )

        self.assertFalse(ActivitySource.objects.filter(activity=activity).exists())

    def test_set_primary_source_on_a_run_stamps_power_source_from_running_power_kind(self):
        athlete = self._athlete("source-run-set-primary@example.cc")
        activity = self._run_activity(athlete, power_source="stryd")
        self._add_records(activity, 5, power=200)  # stryd primary
        activity_source_service.create_parse_time_running_power_source(
            activity, [0, 1, 2, 3, 4], [250, 251, 252, 253, 254], "native"
        )
        native_source = ActivitySource.objects.get(activity=activity)

        activity_source_service.set_primary_source(activity.id, "power", native_source.id)

        self.assertEqual([r.power for r in activity.records.order_by("t")], [250, 251, 252, 253, 254])
        activity.refresh_from_db()
        self.assertEqual(activity.power_source, "native")

        # The original (stryd, 200) primary was itself snapshotted as a new source, tagged with
        # its own running-power kind - not left blank/device-classified the way bike's is.
        stryd_snapshot = ActivitySource.objects.exclude(pk=native_source.pk).get(activity=activity)
        self.assertEqual(stryd_snapshot.running_power_kind, "stryd")

        # And it's reversible, the same as bike's.
        activity_source_service.set_primary_source(activity.id, "power", stryd_snapshot.id)
        activity.refresh_from_db()
        self.assertEqual(activity.power_source, "stryd")
        for record in activity.records.all():
            self.assertEqual(record.power, 200)

    def test_list_sources_is_primary_for_a_run_uses_power_source_not_device(self):
        athlete = self._athlete("source-run-list-is-primary@example.cc")
        activity = self._run_activity(athlete, power_source="stryd")
        self._add_records(activity, 5, power=200)
        activity_source_service.create_parse_time_running_power_source(
            activity, [0, 1, 2, 3, 4], [250, 251, 252, 253, 254], "native"
        )
        native_source = ActivitySource.objects.get(activity=activity)

        summaries = activity_source_service.list_sources(activity.id)
        self.assertEqual(len(summaries), 1)
        # The stored native source doesn't match the activity's current (stryd) primary yet.
        self.assertFalse(summaries[0].is_primary)

        activity_source_service.set_primary_source(activity.id, "power", native_source.id)

        summaries_after_swap = activity_source_service.list_sources(activity.id)
        matching = next(s for s in summaries_after_swap if s.id == native_source.id)
        self.assertTrue(matching.is_primary)
