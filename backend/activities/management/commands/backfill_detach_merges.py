"""Issue #542's backfill for attached sources that predate PR #575 - that fix made
merge_activities(copy_mode="attach") create a Merge row, but a handful of real activities got
an attached source before it existed, so they have no Merge row at all and can't be found via
get_merge/list_merges or undone via unmerge_activity (only the new standalone detach_source call
can remove them). Mirrors backend_java's DetachMergeBackfillRunner exactly (same --mode
report/apply shape) - keep the two in sync when changing either.

Report mode (the default) only lists the orphans found; nothing is changed until --mode apply is
passed. Re-running apply is idempotent - once a Merge row exists for a source, it's no longer an
orphan and won't be touched again.

Every field this creates that the real attach-time code would have populated from data we no
longer have (source_activity, demoted_source, offset_seconds, alignment_score) is left null
rather than guessing - see activity_source_service.detach_source/detach_and_restore's own
docstrings for why a null demoted_source_id is safe (there's a live-data fallback), and
backend/activities/models.py's Merge.offset_seconds for why that column is nullable at all.

    python manage.py backfill_detach_merges                 (report-only, every orphan)
    python manage.py backfill_detach_merges --activity act_abc123
    python manage.py backfill_detach_merges --mode apply
"""

from django.core.management.base import BaseCommand, CommandError
from django.db import transaction

from activities import models


class Command(BaseCommand):
    help = "Backfills issue #542 Merge rows for attached sources that predate PR #575."

    def add_arguments(self, parser):
        parser.add_argument("--activity", help="Only this activity's orphaned sources (by id).")
        parser.add_argument(
            "--mode",
            choices=["report", "apply"],
            default="report",
            help="'report' (default) only lists orphans; 'apply' actually creates their Merge rows.",
        )

    def handle(self, *args, **options):
        activity_id = options["activity"]
        apply_changes = options["mode"] == "apply"

        orphans = models.ActivitySource.objects.filter(origin="attached").exclude(
            pk__in=models.Merge.objects.filter(attached_source__isnull=False).values_list(
                "attached_source_id", flat=True
            )
        )
        if activity_id:
            orphans = orphans.filter(activity_id=activity_id)
            if not orphans.exists() and not models.Activity.objects.filter(pk=activity_id).exists():
                raise CommandError(f"No activity with id {activity_id!r}")

        created = 0
        for source in orphans.select_related("activity"):
            if not apply_changes:
                self.stdout.write(
                    f"[{source.activity_id}] orphaned {source.sensor_kind} source {source.id} "
                    f"(origin=attached, created {source.created_at})"
                )
                continue
            with transaction.atomic():
                merge = models.Merge.objects.create(
                    target_activity=source.activity,
                    source_activity=None,
                    fields=[source.sensor_kind],
                    null_policy=None,
                    copy_mode="attach",
                    attached_source=source,
                    demoted_source=None,
                    offset_seconds=None,
                    alignment_score=None,
                    field_counts={},
                    previous_activity_values={},
                    source_hidden=False,
                )
                # created_at is auto_now_add - overridden after creation so the backfilled row
                # sorts among real merges by when the attach actually happened, not "now".
                models.Merge.objects.filter(pk=merge.pk).update(created_at=source.created_at)
            created += 1
            self.stdout.write(f"[{source.activity_id}] created {merge.id} for {source.sensor_kind} source {source.id}")

        if not apply_changes:
            self.stdout.write(
                self.style.SUCCESS(f"Done (report-only - nothing changed). {orphans.count()} orphan(s) found.")
            )
            return
        self.stdout.write(self.style.SUCCESS(f"Done applying. {created} Merge row(s) created."))
