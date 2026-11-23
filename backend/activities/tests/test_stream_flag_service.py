"""Issue #540 (phase 6): stream_flag_service.detect_stream_flags, dispatching to each detector
and normalizing its output into one FlagSegment shape. Needs real Activity/Record/ActivitySource
rows (attach_stream is the simplest way to get a real stored reference source), so this is
conftest.py's `integration` bucket despite each individual detector's own tests being pure-unit.
Mirrors backend_java's StreamFlagServiceTest."""

from datetime import UTC, datetime, timedelta

from django.test import TestCase
from rest_framework.exceptions import ValidationError

from accounts.models import User

from .. import activity_source_service, stream_flag_service
from ..models import Record
from ..stream_flag_service import FlagSegment
from .helpers import _make_activity


class StreamFlagServiceTests(TestCase):
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

    def test_contact_loss_power_delegates_to_dropout_detection(self):
        athlete = self._athlete("flags-power-dropout@example.cc")
        target = self._activity(athlete, "Edge 530", name="Target")
        source = self._activity(athlete, "Favero Assioma", name="Source")
        self._add_records_with(target, 10, power=lambda t: 0 if t in (4, 5) else 200)
        self._add_constant(source, 10, "power", 220)
        attach = activity_source_service.attach_stream(target.id, source.id, "power", offset_seconds=0, preview=False)

        result = stream_flag_service.detect_stream_flags(target.id, "power", "contact_loss", attach.source_id)

        self.assertEqual(result.field, "power")
        self.assertEqual(result.kind, "contact_loss")
        self.assertEqual(result.segments, [FlagSegment(start_t=4, end_t=5, reference_confirmed=True)])

    def test_contact_loss_heartrate_delegates_to_implausible_jump_detection(self):
        athlete = self._athlete("flags-hr-jump@example.cc")
        target = self._activity(athlete, "Epix", name="Target")
        source = self._activity(athlete, "Tickr", name="Source")
        self._add_records_with(target, 10, heartrate=lambda t: 180 if t in (4, 5) else 80)
        self._add_constant(source, 10, "heartrate", 150)
        attach = activity_source_service.attach_stream(
            target.id, source.id, "heartrate", offset_seconds=0, preview=False, source_kind="strap"
        )

        result = stream_flag_service.detect_stream_flags(target.id, "heartrate", "contact_loss", attach.source_id)

        self.assertEqual(result.segments, [FlagSegment(start_t=4, end_t=5, reference_confirmed=True)])

    def test_contact_loss_rejects_an_unsupported_field(self):
        athlete = self._athlete("flags-bad-field@example.cc")
        target = self._activity(athlete, "Edge 530", name="Target")
        self._add_constant(target, 10, "power", 200)

        with self.assertRaises(ValidationError):
            stream_flag_service.detect_stream_flags(target.id, "distance_km", "contact_loss", None)

    def test_cadence_lock_delegates_to_cadence_lock_detection(self):
        athlete = self._athlete("flags-cadence-lock@example.cc")
        target = self._activity(athlete, "Epix", name="Target")
        source = self._activity(athlete, "Tickr", name="Source")
        self._add_records_with(
            target,
            60,
            heartrate=lambda t: 152 if t % 2 == 0 else 148,
            cadence=lambda t: 90 if t % 2 == 0 else 70,
        )
        self._add_constant(source, 60, "heartrate", 150)
        attach = activity_source_service.attach_stream(
            target.id, source.id, "heartrate", offset_seconds=0, preview=False, source_kind="strap"
        )

        result = stream_flag_service.detect_stream_flags(target.id, "heartrate", "cadence_lock", attach.source_id)

        self.assertEqual(result.segments, [FlagSegment(start_t=0, end_t=59, reference_confirmed=True)])

    def test_cadence_lock_rejects_a_field_other_than_heartrate(self):
        athlete = self._athlete("flags-cadence-lock-bad-field@example.cc")
        target = self._activity(athlete, "Edge 530", name="Target")
        self._add_constant(target, 10, "power", 200)

        with self.assertRaises(ValidationError):
            stream_flag_service.detect_stream_flags(target.id, "power", "cadence_lock", None)

    def test_an_unsupported_kind_is_rejected(self):
        athlete = self._athlete("flags-bad-kind@example.cc")
        target = self._activity(athlete, "Edge 530", name="Target")
        self._add_constant(target, 10, "power", 200)

        with self.assertRaises(ValidationError):
            stream_flag_service.detect_stream_flags(target.id, "power", "not_a_real_kind", None)
