"""Issue #540 (phase 6), code review follow-up: stream_patch_service._acquire_patch_lock closes
the TOCTOU race between _reject_overlap's read and patch_stream's own write - two concurrent
patch_stream calls on the same activity/field could otherwise both pass the overlap check before
either committed. TransactionTestCase (not TestCase) because this needs two real, separately
committing connections - TestCase's own enclosing transaction would make cross-thread visibility
impossible."""

import threading
import time
from datetime import UTC, datetime, timedelta
from unittest.mock import patch

from django.db import connection
from django.test import TransactionTestCase

from accounts.models import User
from core.exceptions import ConflictError

from .. import activity_source_service, stream_patch_service
from ..models import Record, StreamPatch
from .helpers import _make_activity


class StreamPatchLockingTests(TransactionTestCase):
    def setUp(self):
        self.start = datetime(2026, 6, 1, 18, 0, 0, tzinfo=UTC)

    def _athlete(self, email):
        return User.objects.create_user(email=email, password="x", name="Athlete")

    def _activity(self, athlete, device, **kwargs):
        return _make_activity(athlete, sport="bike", start_date=self.start, moving_time=600, device=device, **kwargs)

    def _add_constant(self, activity, count, field, value):
        for t in range(count):
            Record.objects.create(
                activity=activity, t=t, ts=activity.start_date + timedelta(seconds=t), **{field: value}
            )

    def test_concurrent_overlapping_patches_are_serialized_not_a_race(self):
        athlete = self._athlete("patch-locking-concurrent@example.cc")
        target = self._activity(athlete, "Edge 530", name="Target")
        source = self._activity(athlete, "Favero Assioma", name="Source")
        self._add_constant(target, 10, "power", 200)
        self._add_constant(source, 10, "power", 220)
        attach = activity_source_service.attach_stream(target.id, source.id, "power", offset_seconds=0, preview=False)
        connection.close()  # don't hand the setup connection to either worker thread below

        original_reject_overlap = stream_patch_service._reject_overlap

        def slow_reject_overlap(*args, **kwargs):
            # Widens the race window far past normal thread-scheduling jitter, so - without the
            # lock - both threads reliably pass this check before either commits, deterministically
            # reproducing the race rather than leaving it to chance.
            time.sleep(0.2)
            return original_reject_overlap(*args, **kwargs)

        results: list[object] = []
        errors: list[Exception] = []
        barrier = threading.Barrier(2)

        def attempt(segment):
            barrier.wait()
            try:
                result = stream_patch_service.patch_stream(
                    target.id, "power", attach.source_id, segments=[segment], preview=False
                )
                results.append(result)
            except ConflictError as exc:
                errors.append(exc)
            finally:
                connection.close()

        with patch.object(stream_patch_service, "_reject_overlap", side_effect=slow_reject_overlap):
            t1 = threading.Thread(target=attempt, args=((4, 5),))
            t2 = threading.Thread(target=attempt, args=((5, 6),))
            t1.start()
            t2.start()
            t1.join()
            t2.join()

        self.assertEqual(len(results), 1)
        self.assertEqual(len(errors), 1)
        self.assertIsInstance(errors[0], ConflictError)
        self.assertEqual(
            StreamPatch.objects.filter(activity_id=target.id, field="power", undone_at__isnull=True).count(), 1
        )
