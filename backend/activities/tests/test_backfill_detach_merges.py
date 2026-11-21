from datetime import UTC, datetime
from io import StringIO

from django.core.management import call_command
from django.test import TestCase

from accounts.models import User

from .. import activity_source_service
from ..models import Merge
from .helpers import _make_activity


class BackfillDetachMergesTests(TestCase):
    """Issue #542's backfill command for attached sources that predate PR #575 - mirrors
    backend_java's DetachMergeBackfillRunner."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="backfill-detach@example.cc", password="x", name="Athlete")
        self.start = datetime(2026, 6, 1, 18, 0, 0, tzinfo=UTC)

    def _activity(self, device, **kwargs):
        defaults = {"sport": "bike", "moving_time": 600, "start_date": self.start}
        defaults.update(kwargs)
        return _make_activity(self.athlete, device=device, **defaults)

    def _run(self, *args):
        out = StringIO()
        call_command("backfill_detach_merges", *args, stdout=out)
        return out.getvalue()

    def _orphaned_attach(self):
        """attach_stream alone (no merge_service wrapper) never creates a Merge row - exactly
        the pre-PR-575 shape this backfill targets."""
        target = self._activity("Edge 530", name="Target")
        source = self._activity("Zwift", name="Source")
        attach = activity_source_service.attach_stream(target.id, source.id, "power", offset_seconds=0, preview=False)
        return target, attach

    def test_report_mode_lists_the_orphan_but_creates_nothing(self):
        target, attach = self._orphaned_attach()

        output = self._run()

        self.assertIn(target.id, output)
        self.assertIn(attach.source_id, output)
        self.assertIn("report-only", output)
        self.assertEqual(Merge.objects.count(), 0)

    def test_apply_mode_creates_a_correctly_shaped_merge_row(self):
        target, attach = self._orphaned_attach()

        output = self._run("--mode", "apply")

        self.assertIn("1 Merge row(s) created", output)
        merge = Merge.objects.get(attached_source_id=attach.source_id)
        self.assertEqual(merge.target_activity_id, target.id)
        self.assertIsNone(merge.source_activity_id)
        self.assertEqual(merge.fields, ["power"])
        self.assertIsNone(merge.null_policy)
        self.assertEqual(merge.copy_mode, "attach")
        self.assertIsNone(merge.demoted_source_id)
        self.assertIsNone(merge.offset_seconds)
        self.assertIsNone(merge.alignment_score)
        self.assertEqual(merge.field_counts, {})
        self.assertEqual(merge.previous_activity_values, {})
        self.assertFalse(merge.source_hidden)
        self.assertIsNone(merge.undone_at)

    def test_apply_mode_backdates_created_at_to_the_sources_own(self):
        _target, attach = self._orphaned_attach()
        from ..models import ActivitySource

        source = ActivitySource.objects.get(pk=attach.source_id)

        self._run("--mode", "apply")

        merge = Merge.objects.get(attached_source_id=attach.source_id)
        self.assertEqual(merge.created_at, source.created_at)

    def test_apply_mode_is_idempotent(self):
        self._orphaned_attach()
        self._run("--mode", "apply")

        second_output = self._run("--mode", "apply")

        self.assertIn("0 Merge row(s) created", second_output)
        self.assertEqual(Merge.objects.count(), 1)

    def test_activity_filter_scopes_to_one_activitys_orphans(self):
        target_a, attach_a = self._orphaned_attach()
        _target_b, attach_b = self._orphaned_attach()

        output = self._run("--activity", target_a.id)

        self.assertIn(attach_a.source_id, output)
        self.assertNotIn(attach_b.source_id, output)

    def test_a_tracked_attach_merge_is_not_reported_as_an_orphan(self):
        """An attach made through merge_service (the normal, post-#575 path) already gets its
        own Merge row - it must not show up as an orphan too."""
        from ..merge_service import run_merge

        target = self._activity("Edge 530", name="Target")
        source = self._activity("Zwift", name="Source")
        run_merge(target.id, source.id, ["power"], null_policy="keep_original", copy_mode="attach")

        output = self._run()

        self.assertIn("0 orphan(s) found", output)
