"""Issue #540 (phase 6): the REST surface over stream_flag_service/stream_patch_service - GET
.../streams/flags, POST .../streams/patch, POST .../streams/unpatch. The service layer's own
tests (test_stream_flag_service.py, test_stream_patch_service.py) cover the underlying logic in
depth; these just prove the views/urls/serializers wiring actually works end to end."""

from datetime import UTC, datetime, timedelta

from django.test import TestCase

from accounts.models import User

from .. import activity_source_service
from ..models import Record, StreamPatch
from .helpers import _bearer_client, _make_activity


class StreamFlagsRestTests(TestCase):
    def setUp(self):
        self.start = datetime(2026, 6, 1, 18, 0, 0, tzinfo=UTC)

    def _athlete(self, email):
        return User.objects.create_user(email=email, password="x", name="Athlete")

    def _activity(self, athlete, device, **kwargs):
        return _make_activity(athlete, sport="bike", start_date=self.start, moving_time=600, device=device, **kwargs)

    def _add_records_with(self, activity, count, **field_fns):
        for t in range(count):
            Record.objects.create(
                activity=activity,
                t=t,
                ts=activity.start_date + timedelta(seconds=t),
                **{field: fn(t) for field, fn in field_fns.items()},
            )

    def _add_constant(self, activity, count, field, value):
        self._add_records_with(activity, count, **{field: lambda t, v=value: v})

    def _target_and_attached_power_source(self, athlete_suffix):
        athlete = self._athlete(f"flags-rest-{athlete_suffix}@example.cc")
        target = self._activity(athlete, "Edge 530", name="Target")
        source = self._activity(athlete, "Favero Assioma", name="Source")
        self._add_records_with(target, 10, power=lambda t: 0 if t in (4, 5) else 200)
        self._add_constant(source, 10, "power", 220)
        attach = activity_source_service.attach_stream(target.id, source.id, "power", offset_seconds=0, preview=False)
        return athlete, target, attach

    def test_flags_endpoint_returns_detected_segments(self):
        athlete, target, attach = self._target_and_attached_power_source("detect")

        response = _bearer_client(athlete).get(
            f"/v1/activities/{target.id}/streams/flags",
            {"field": "power", "kind": "contact_loss", "reference": attach.source_id},
        )

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["field"], "power")
        self.assertEqual(body["kind"], "contact_loss")
        self.assertEqual(body["segments"], [{"start_t": 4, "end_t": 5, "reference_confirmed": True}])

    def test_patch_then_unpatch_round_trip_over_rest(self):
        athlete, target, attach = self._target_and_attached_power_source("round-trip")
        client = _bearer_client(athlete)

        patch_response = client.post(
            f"/v1/activities/{target.id}/streams/patch",
            {"field": "power", "from_source": attach.source_id, "segments": [[4, 5]], "preview": False},
            format="json",
        )

        self.assertEqual(patch_response.status_code, 200)
        patch_body = patch_response.json()
        self.assertEqual(patch_body["segments"], [[4, 5]])
        patch_id = patch_body["patch_id"]
        self.assertIsNotNone(patch_id)
        self.assertEqual({r.t: r.power for r in target.records.filter(t__in=[4, 5])}, {4: 220, 5: 220})

        unpatch_response = client.post(f"/v1/activities/{target.id}/streams/unpatch", {}, format="json")

        self.assertEqual(unpatch_response.status_code, 200)
        self.assertEqual(unpatch_response.json()["patch_id"], patch_id)
        self.assertEqual({r.t: r.power for r in target.records.filter(t__in=[4, 5])}, {4: 0, 5: 0})
        self.assertIsNotNone(StreamPatch.objects.get(pk=patch_id).undone_at)

    def test_patch_endpoint_rejects_malformed_segments(self):
        athlete, target, attach = self._target_and_attached_power_source("malformed")

        response = _bearer_client(athlete).post(
            f"/v1/activities/{target.id}/streams/patch",
            {"field": "power", "from_source": attach.source_id, "segments": "not-a-real-option", "preview": False},
            format="json",
        )

        self.assertEqual(response.status_code, 400)
