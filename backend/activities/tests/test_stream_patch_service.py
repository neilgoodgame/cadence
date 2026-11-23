"""Issue #540 (phase 6): stream_patch_service.patch_stream/unpatch_stream - the write side of
GET .../streams/flags. Integration-style (real Activity/Record/ActivitySource rows), same as
test_activity_source_service.py. Mirrors backend_java's StreamPatchServiceIntegrationTest."""

from datetime import UTC, datetime, timedelta

from django.test import TestCase
from rest_framework.exceptions import ValidationError

from accounts.models import User
from core.exceptions import ConflictError, UnprocessableEntityError

from .. import activity_source_service, stream_patch_service
from ..models import Record, StreamPatch
from .helpers import _make_activity


class StreamPatchServiceTests(TestCase):
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
        athlete = self._athlete(f"patch-{athlete_suffix}@example.cc")
        target = self._activity(athlete, "Edge 530", name="Target")
        source = self._activity(athlete, "Favero Assioma", name="Source")
        self._add_records_with(target, 10, power=lambda t: 0 if t in (4, 5) else 200)
        self._add_constant(source, 10, "power", 220)
        attach = activity_source_service.attach_stream(target.id, source.id, "power", offset_seconds=0, preview=False)
        return target, attach

    def test_patch_applies_explicit_segments_and_creates_an_audit_row(self):
        target, attach = self._target_and_attached_power_source("explicit-commit")

        result = stream_patch_service.patch_stream(
            target.id, "power", attach.source_id, segments=[(4, 5)], preview=False
        )

        self.assertIsNotNone(result.patch_id)
        self.assertEqual(result.segments, [(4, 5)])
        self.assertIsNone(result.detected_kind)

        values_by_t = {r.t: r.power for r in target.records.order_by("t")}
        self.assertEqual(values_by_t[4], 220)
        self.assertEqual(values_by_t[5], 220)
        self.assertEqual(values_by_t[3], 200)  # untouched - outside the patched segment

        patch = StreamPatch.objects.get(pk=result.patch_id)
        self.assertEqual(patch.field, "power")
        self.assertEqual(patch.from_source_id, attach.source_id)
        self.assertEqual(patch.segments, [[4, 5]])
        self.assertEqual(patch.previous_values, {"4": 0, "5": 0})
        self.assertIsNone(patch.undone_at)

    def test_patch_preview_writes_nothing(self):
        target, attach = self._target_and_attached_power_source("preview")

        result = stream_patch_service.patch_stream(
            target.id, "power", attach.source_id, segments=[(4, 5)], preview=True
        )

        self.assertIsNone(result.patch_id)
        self.assertEqual(StreamPatch.objects.count(), 0)
        values_by_t = {r.t: r.power for r in target.records.order_by("t")}
        self.assertEqual(values_by_t[4], 0)
        self.assertEqual(values_by_t[5], 0)

    def test_patch_with_flagged_segments_uses_live_detection_and_records_detected_kind(self):
        target, attach = self._target_and_attached_power_source("flagged")

        result = stream_patch_service.patch_stream(
            target.id,
            "power",
            attach.source_id,
            segments="flagged",
            kind="contact_loss",
            reference=attach.source_id,
            preview=False,
        )

        self.assertEqual(result.segments, [(4, 5)])
        self.assertEqual(result.detected_kind, "contact_loss")
        patch = StreamPatch.objects.get(pk=result.patch_id)
        self.assertEqual(patch.detected_kind, "contact_loss")

    def test_an_overlapping_patch_is_rejected_then_accepted_after_unpatch(self):
        target, attach = self._target_and_attached_power_source("overlap")
        first = stream_patch_service.patch_stream(
            target.id, "power", attach.source_id, segments=[(4, 5)], preview=False
        )

        with self.assertRaises(ConflictError):
            stream_patch_service.patch_stream(target.id, "power", attach.source_id, segments=[(5, 6)], preview=False)

        stream_patch_service.unpatch_stream(target.id, first.patch_id)

        second = stream_patch_service.patch_stream(
            target.id, "power", attach.source_id, segments=[(5, 6)], preview=False
        )
        self.assertIsNotNone(second.patch_id)

    def test_explicit_segments_over_the_sanity_cap_are_rejected(self):
        target, attach = self._target_and_attached_power_source("too-big")

        with self.assertRaises(ValidationError):
            stream_patch_service.patch_stream(target.id, "power", attach.source_id, segments=[(0, 4000)], preview=False)

    def test_unpatch_restores_previous_values_and_marks_undone(self):
        target, attach = self._target_and_attached_power_source("unpatch")
        applied = stream_patch_service.patch_stream(
            target.id, "power", attach.source_id, segments=[(4, 5)], preview=False
        )

        patch = stream_patch_service.unpatch_stream(target.id, applied.patch_id)

        self.assertIsNotNone(patch.undone_at)
        values_by_t = {r.t: r.power for r in target.records.order_by("t")}
        self.assertEqual(values_by_t[4], 0)
        self.assertEqual(values_by_t[5], 0)

    def test_unpatch_is_ambiguous_without_patch_id_when_more_than_one_active(self):
        target, attach = self._target_and_attached_power_source("ambiguous")
        stream_patch_service.patch_stream(target.id, "power", attach.source_id, segments=[(4, 4)], preview=False)
        stream_patch_service.patch_stream(target.id, "power", attach.source_id, segments=[(5, 5)], preview=False)

        with self.assertRaises(UnprocessableEntityError):
            stream_patch_service.unpatch_stream(target.id)

    def test_unpatch_with_an_unknown_patch_id_errors(self):
        target, _attach = self._target_and_attached_power_source("unknown-id")

        with self.assertRaises(UnprocessableEntityError):
            stream_patch_service.unpatch_stream(target.id, "pat_doesnotexist")
