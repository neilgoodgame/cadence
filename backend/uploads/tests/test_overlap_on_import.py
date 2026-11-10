from datetime import UTC, datetime
from unittest.mock import patch

from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import TestCase

from accounts.models import User
from activities.models import Activity
from athletes.models import ThresholdHistory

from ..fixtures_helpers import build_tcx, build_zip
from ..models import Upload, UploadBatch
from ..processing import threshold_fields_affected_by_demoted_duplicate
from ..tasks import _maybe_finalize_batch
from .helpers import _bearer_client


class OverlapOnImportTests(TestCase):
    """End-to-end coverage for issue #498's on-ingest side: the `on_overlap` request param on
    POST /v1/activities/batch -> services.create_batch_from_files resolving it against the
    athlete's `overlap_handling` default -> processing.ingest_upload running
    activities.overlap_detection for each newly-created activity -> the result landing on the
    Upload row. The detection and primary-selection logic itself is covered directly (and far
    more cheaply) by activities/tests/test_overlap_detection.py; this only exists to prove the
    wiring between the HTTP-adjacent layer and that module actually happens."""

    def setUp(self):
        self.start = datetime(2026, 6, 1, 18, 0, 0, tzinfo=UTC)

    def _overlapping_zip(self):
        # Identical start time and duration on both files - the simplest possible full overlap.
        content_a = build_tcx(self.start, duration_s=60, power=200)
        content_b = build_tcx(self.start, duration_s=60, power=205, hr=150)
        return build_zip({"a.tcx": content_a, "b.tcx": content_b})

    def test_on_overlap_mark_on_the_request_marks_one_copy_as_a_duplicate_of_the_other(self):
        # The athlete's own default is off - the request's on_overlap must be what actually
        # drives this, not a coincidentally-matching default.
        athlete = User.objects.create_user(email="e2e-overlap-mark@example.cc", password="x", overlap_handling="off")
        client = _bearer_client(athlete)

        response = client.post(
            "/v1/activities/batch",
            {"file": SimpleUploadedFile("dupes.zip", self._overlapping_zip()), "on_overlap": "mark"},
            format="multipart",
        )
        self.assertEqual(response.status_code, 202)
        batch = client.get(f"/v1/uploads/batches/{response.json()['id']}").json()
        self.assertEqual(batch["status"], "completed")

        activities = list(Activity.objects.filter(athlete=athlete).order_by("id"))
        self.assertEqual(len(activities), 2)
        primaries = [a for a in activities if a.primary_activity_id is None]
        duplicates = [a for a in activities if a.primary_activity_id is not None]
        self.assertEqual(len(primaries), 1)
        self.assertEqual(len(duplicates), 1)
        self.assertEqual(duplicates[0].primary_activity_id, primaries[0].id)

        with_action = [u for u in batch["uploads"] if u["overlap_action"] is not None]
        self.assertEqual(len(with_action), 1)
        self.assertEqual(with_action[0]["overlap_action"], "marked")
        self.assertIsNotNone(with_action[0]["overlap_other_activity_id"])

    def test_on_overlap_off_on_the_request_skips_detection_even_when_the_athlete_default_is_mark(self):
        athlete = User.objects.create_user(
            email="e2e-overlap-off-override@example.cc", password="x", overlap_handling="mark"
        )
        client = _bearer_client(athlete)

        response = client.post(
            "/v1/activities/batch",
            {"file": SimpleUploadedFile("dupes.zip", self._overlapping_zip()), "on_overlap": "off"},
            format="multipart",
        )
        batch = client.get(f"/v1/uploads/batches/{response.json()['id']}").json()

        for upload in batch["uploads"]:
            self.assertIsNone(upload["overlap_action"])
        for activity in Activity.objects.filter(athlete=athlete):
            self.assertIsNone(activity.primary_activity_id)

    def test_no_request_override_falls_back_to_the_athletes_own_overlap_handling_default(self):
        athlete = User.objects.create_user(
            email="e2e-overlap-athlete-default@example.cc", password="x", overlap_handling="flag"
        )
        client = _bearer_client(athlete)

        # No on_overlap in the request at all - mirrors garmin/sync.py's call site, which has no
        # per-sync override either.
        response = client.post(
            "/v1/activities/batch",
            {"file": SimpleUploadedFile("dupes.zip", self._overlapping_zip())},
            format="multipart",
        )
        batch = client.get(f"/v1/uploads/batches/{response.json()['id']}").json()

        with_action = [u for u in batch["uploads"] if u["overlap_action"] is not None]
        self.assertEqual(len(with_action), 1)
        # FLAG never writes to the activity, even though a pair was found.
        self.assertEqual(with_action[0]["overlap_action"], "flagged")
        for activity in Activity.objects.filter(athlete=athlete):
            self.assertIsNone(activity.primary_activity_id)


class OverlapOnSingleUploadTests(TestCase):
    """Same wiring as OverlapOnImportTests, for the single (non-batch) POST /v1/activities
    upload - a pre-existing gap this issue's own "on_overlap on the single-file upload
    endpoint" item closes: a lone file had no way to carry a per-request override at all,
    always falling back to the athlete's overlap_handling default."""

    def setUp(self):
        self.start = datetime(2026, 6, 1, 18, 0, 0, tzinfo=UTC)

    def test_on_overlap_mark_on_the_request_marks_the_new_upload_against_an_existing_activity(self):
        athlete = User.objects.create_user(
            email="e2e-single-overlap-mark@example.cc", password="x", overlap_handling="off"
        )
        client = _bearer_client(athlete)
        first = client.post(
            "/v1/activities",
            {"file": SimpleUploadedFile("a.tcx", build_tcx(self.start, duration_s=60, power=200))},
            format="multipart",
        )
        first_activity_id = client.get(f"/v1/uploads/{first.json()['id']}").json()["activity_id"]

        second = client.post(
            "/v1/activities",
            {
                "file": SimpleUploadedFile("b.tcx", build_tcx(self.start, duration_s=60, power=205, hr=150)),
                "on_overlap": "mark",
            },
            format="multipart",
        )
        self.assertEqual(second.status_code, 202)
        poll = client.get(f"/v1/uploads/{second.json()['id']}").json()
        self.assertEqual(poll["overlap_action"], "marked")
        self.assertEqual(poll["overlap_other_activity_id"], first_activity_id)

        activities = list(Activity.objects.filter(athlete=athlete).order_by("id"))
        self.assertEqual(len(activities), 2)
        primaries = [a for a in activities if a.primary_activity_id is None]
        duplicates = [a for a in activities if a.primary_activity_id is not None]
        self.assertEqual(len(primaries), 1)
        self.assertEqual(len(duplicates), 1)
        self.assertEqual(duplicates[0].primary_activity_id, primaries[0].id)

    def test_no_request_override_falls_back_to_the_athletes_own_overlap_handling_default(self):
        athlete = User.objects.create_user(
            email="e2e-single-overlap-default@example.cc", password="x", overlap_handling="mark"
        )
        client = _bearer_client(athlete)
        client.post(
            "/v1/activities",
            {"file": SimpleUploadedFile("a.tcx", build_tcx(self.start, duration_s=60, power=200))},
            format="multipart",
        )

        second = client.post(
            "/v1/activities",
            # No on_overlap in the request at all.
            {"file": SimpleUploadedFile("b.tcx", build_tcx(self.start, duration_s=60, power=205, hr=150))},
            format="multipart",
        )
        poll = client.get(f"/v1/uploads/{second.json()['id']}").json()

        self.assertEqual(poll["overlap_action"], "marked")
        duplicates = [a for a in Activity.objects.filter(athlete=athlete) if a.primary_activity_id is not None]
        self.assertEqual(len(duplicates), 1)

    def test_on_overlap_off_on_the_request_overrides_an_athlete_default_of_mark(self):
        athlete = User.objects.create_user(
            email="e2e-single-overlap-off-override@example.cc", password="x", overlap_handling="mark"
        )
        client = _bearer_client(athlete)
        client.post(
            "/v1/activities",
            {"file": SimpleUploadedFile("a.tcx", build_tcx(self.start, duration_s=60, power=200))},
            format="multipart",
        )

        second = client.post(
            "/v1/activities",
            {
                "file": SimpleUploadedFile("b.tcx", build_tcx(self.start, duration_s=60, power=205, hr=150)),
                "on_overlap": "off",
            },
            format="multipart",
        )
        poll = client.get(f"/v1/uploads/{second.json()['id']}").json()

        self.assertIsNone(poll["overlap_action"])
        for activity in Activity.objects.filter(athlete=athlete):
            self.assertIsNone(activity.primary_activity_id)


class RecomputeAfterIngestTimeMarkTests(TestCase):
    """Issue #498: an ingest-time auto-mark must trigger the same post-mark recompute cascade
    (processing.recompute_after_duplicate_change) that mark_duplicate/unmark_duplicate/
    set_primary and resolve_duplicates_batch already run - otherwise a duplicate-sourced best
    effort stays an eligible threshold candidate, and TSS/intensity elsewhere stays stale, until
    something else happens to trigger a rebuild. Patches the cascade itself rather than building
    a full threshold-ledger scenario - its own correctness (what it rebuilds, and why) is covered
    directly by activities/tests/test_duplicate_batches.py and athletes/tests.py's own
    rebuild_history_stream tests; this only proves every auto-mark path reaches it, exactly once
    per upload or batch."""

    def setUp(self):
        self.start = datetime(2026, 6, 1, 18, 0, 0, tzinfo=UTC)

    @patch("uploads.tasks.recompute_after_duplicate_change")
    def test_single_upload_auto_mark_triggers_the_scoped_cascade_after_ready(self, mock_recompute):
        athlete = User.objects.create_user(
            email="e2e-single-overlap-recompute@example.cc", password="x", overlap_handling="mark"
        )
        client = _bearer_client(athlete)
        client.post(
            "/v1/activities",
            {"file": SimpleUploadedFile("a.tcx", build_tcx(self.start, duration_s=60, power=200))},
            format="multipart",
        )
        mock_recompute.assert_not_called()  # nothing to overlap with yet

        second = client.post(
            "/v1/activities",
            {"file": SimpleUploadedFile("b.tcx", build_tcx(self.start, duration_s=60, power=205, hr=150))},
            format="multipart",
        )
        poll = client.get(f"/v1/uploads/{second.json()['id']}").json()
        self.assertEqual(poll["overlap_action"], "marked")
        self.assertEqual(poll["status"], "ready")
        # Runs from tasks.process_upload after the upload is ready, scoped to the threshold
        # fields the demoted duplicate sourced - none here, so nothing would be rebuilt.
        mock_recompute.assert_called_once_with(athlete, [])

    @patch("uploads.tasks.recompute_after_duplicate_change")
    def test_flag_only_mode_never_triggers_the_cascade(self, mock_recompute):
        athlete = User.objects.create_user(
            email="e2e-single-overlap-recompute-flag@example.cc", password="x", overlap_handling="flag"
        )
        client = _bearer_client(athlete)
        client.post(
            "/v1/activities",
            {"file": SimpleUploadedFile("a.tcx", build_tcx(self.start, duration_s=60, power=200))},
            format="multipart",
        )
        second = client.post(
            "/v1/activities",
            {"file": SimpleUploadedFile("b.tcx", build_tcx(self.start, duration_s=60, power=205, hr=150))},
            format="multipart",
        )
        poll = client.get(f"/v1/uploads/{second.json()['id']}").json()

        self.assertEqual(poll["overlap_action"], "flagged")
        mock_recompute.assert_not_called()

    # Exercises _maybe_finalize_batch directly against a batch already settled in the DB,
    # rather than through the real HTTP/Celery ingest pipeline: with CELERY_ALWAYS_EAGER (as in
    # tests), create_batch_from_files' own loop creates and eagerly processes each upload one at
    # a time, so _maybe_finalize_batch can see a transient "no queued/processing siblings" state
    # for a batch whose later files simply haven't been created yet - a timing artifact specific
    # to synchronous-task tests (a real deployment enqueues every file before any worker starts
    # on the first one), not something this fix needs to paper over. Testing the function's own
    # logic directly - once settled, recompute exactly once no matter how many children were
    # marked - sidesteps that artifact entirely.
    @patch("uploads.tasks.recompute_after_duplicate_change")
    def test_batch_finalize_runs_the_cascade_once_regardless_of_how_many_uploads_were_marked(self, mock_recompute):
        athlete = User.objects.create_user(email="e2e-batch-overlap-recompute@example.cc", password="x")
        batch = UploadBatch.objects.create(
            athlete=athlete, filename="dupes.zip", on_duplicate="skip", on_overlap="mark", status="processing"
        )
        for i in range(4):
            Upload.objects.create(
                athlete=athlete,
                batch=batch,
                filename=f"f{i}.tcx",
                file_hash=f"hash{i}",
                status="ready",
                overlap_action="marked" if i in (1, 3) else "",
            )

        _maybe_finalize_batch(batch.id)

        mock_recompute.assert_called_once_with(athlete)


class ThresholdFieldsAffectedByDemotedDuplicateTests(TestCase):
    """Scoping for the post-ingest rebuild: only fields the demoted duplicate's sport can define,
    and only where it is currently a *measured* ledger source."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="scoped-recompute@example.cc", password="x")
        self.run = Activity.objects.create(
            athlete=self.athlete, sport="run", name="Run", start_date=datetime(2026, 6, 1, tzinfo=UTC)
        )

    def _row(self, field, kind="measured", activity=None):
        ThresholdHistory.objects.create(
            athlete=self.athlete,
            field=field,
            value_numeric=250,
            source_activity=activity if activity is not None else self.run,
            effective_from=datetime(2026, 6, 1, tzinfo=UTC).date(),
            current_from=datetime(2026, 6, 1, tzinfo=UTC).date(),
            kind=kind,
        )

    def test_never_a_source_affects_nothing(self):
        self.assertEqual(threshold_fields_affected_by_demoted_duplicate(self.run.id, "run"), [])

    def test_only_measured_source_fields_for_the_sport(self):
        self._row("critical_run_power")
        self._row("threshold_pace", kind="manual")
        self._row("ftp")  # not a run field, even if a stray row exists
        self.assertEqual(threshold_fields_affected_by_demoted_duplicate(self.run.id, "run"), ["critical_run_power"])

    def test_sport_without_thresholds_affects_nothing(self):
        self._row("critical_run_power")
        self.assertEqual(threshold_fields_affected_by_demoted_duplicate(self.run.id, "swim"), [])
