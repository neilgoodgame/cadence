"""Issue #498's backfill for same-ride-twice duplicates that existed before on-ingest detection
(activities/overlap_detection.py::detect_and_resolve_overlap, wired into
uploads/processing.py::ingest_upload) shipped - that only runs against newly-ingested
activities, so every pair already sitting in the database needs this separate, operator-run
pass. Mirrors backend_java's OverlapBackfillRunner exactly (same --athlete/--mode/--action
arguments, same report-then-apply shape and recompute cascade) - keep the two in sync when
changing either.

Report mode (the default) only lists what it finds, exactly as the issue asks for
("report-only mode first, then apply"); nothing is changed until --mode apply is passed. Apply
mode resolves every unresolved pair via overlap_detection.resolve_pair - the same code path
on-ingest detection uses - then, once per athlete that actually had a pair resolved, rebuilds
that athlete's threshold history (duplicate-sourced best efforts are no longer eligible
threshold candidates) and recomputes TSS/intensity for their activities against the rebuilt
ledger (the fitness trend reads Activity.tss live, so re-rating activities is also how the
fitness trend "recomputes" - there's no separate stored series to rebuild).

    python manage.py backfill_overlaps                       (report-only, every athlete)
    python manage.py backfill_overlaps --athlete ath_abc123
    python manage.py backfill_overlaps --mode apply            (defaults to --action mark)
    python manage.py backfill_overlaps --mode apply --action flag
    python manage.py backfill_overlaps --mode apply --action merge

--action merge (issue #501 phase 4) resolves each pair via the athlete's saved-or-built-in
merge_rule_service rule, same as on-ingest detection - see overlap_detection's own module
docstring for the full merge-vs-flag decision.
"""

from django.core.management.base import BaseCommand, CommandError

from accounts.models import User
from activities.overlap_detection import resolve_pair
from activities.overlaps import find_overlaps
from athletes.threshold_history import rebuild_history_stream
from uploads.processing import recompute_tss_for_athlete

_THRESHOLD_FIELDS = ("ftp", "critical_run_power", "threshold_pace")
_PAGE_SIZE = 100


class Command(BaseCommand):
    help = "Backfills issue #498 same-ride-twice duplicate detection across existing activities."

    def add_arguments(self, parser):
        parser.add_argument("--athlete", help="Only this athlete's activities (by id).")
        parser.add_argument(
            "--mode",
            choices=["report", "apply"],
            default="report",
            help="'report' (default) only lists unresolved pairs; 'apply' actually resolves them.",
        )
        parser.add_argument(
            "--action",
            choices=["mark", "flag", "merge"],
            default="mark",
            help="Only used with --mode apply: 'mark' (default) links the lower-ranked copy as a "
            "duplicate; 'flag' just records the pair without changing anything; 'merge' applies "
            "the athlete's saved-or-built-in merge rule, falling back to flagging when none "
            "applies or the alignment score is too low.",
        )

    def handle(self, *args, **options):
        athlete_id = options["athlete"]
        apply_changes = options["mode"] == "apply"
        action = options["action"]

        if athlete_id:
            try:
                athletes = [User.objects.get(pk=athlete_id)]
            except User.DoesNotExist as exc:
                raise CommandError(f"No athlete with id {athlete_id!r}") from exc
        else:
            athletes = list(User.objects.all())

        total_pairs = marked = merged = merge_skipped = flagged = 0
        affected_athlete_ids: list[str] = []

        for athlete in athletes:
            cursor = None
            while True:
                page = find_overlaps(athlete.id, cursor=cursor, limit=_PAGE_SIZE)
                for pair in page["data"]:
                    total_pairs += 1
                    anchor_id = pair["anchor"]["id"]
                    other_id = pair["other"]["id"]
                    if not apply_changes:
                        self.stdout.write(
                            f"[{athlete.id}] {anchor_id} <-> {other_id} "
                            f"(overlap {pair['overlap_pct']}%, start delta {pair['start_delta_seconds']}s)"
                        )
                        continue
                    resolution = resolve_pair(anchor_id, other_id, action)
                    if resolution["action"] == "marked":
                        marked += 1
                        if athlete.id not in affected_athlete_ids:
                            affected_athlete_ids.append(athlete.id)
                        self.stdout.write(
                            f"[{athlete.id}] MARKED {resolution['other_activity_id']} as a duplicate of {anchor_id}"
                        )
                    elif resolution["action"] == "merged":
                        # Not added to affected_athlete_ids - the merge itself already ran the
                        # full recompute cascade (see overlap_detection._merge).
                        merged += 1
                        self.stdout.write(
                            f"[{athlete.id}] MERGED {resolution['other_activity_id']} into "
                            f"{resolution['primary_activity_id']}"
                        )
                    elif resolution["action"] == "merge_skipped_low_score":
                        merge_skipped += 1
                        self.stdout.write(f"[{athlete.id}] MERGE_SKIPPED_LOW_SCORE {anchor_id} <-> {other_id}")
                    else:
                        flagged += 1
                        self.stdout.write(f"[{athlete.id}] FLAGGED {anchor_id} <-> {other_id}")
                if not page["has_more"]:
                    break
                cursor = page["next_cursor"]

        if not apply_changes:
            self.stdout.write(
                self.style.SUCCESS(
                    f"Done (report-only - nothing changed). {total_pairs} unresolved pair(s) found "
                    f"across {len(athletes)} athlete(s)."
                )
            )
            return

        self.stdout.write(
            self.style.SUCCESS(
                f"Done applying. {total_pairs} pair(s) resolved: {marked} marked, {merged} merged, "
                f"{merge_skipped} merge-skipped (low score), {flagged} flagged."
            )
        )

        for affected_id in affected_athlete_ids:
            athlete = User.objects.get(pk=affected_id)
            for field in _THRESHOLD_FIELDS:
                for _ in rebuild_history_stream(athlete, field):
                    pass
            rerated = recompute_tss_for_athlete(athlete)
            self.stdout.write(f"[{affected_id}] Rebuilt threshold history and re-rated {rerated} activity/activities.")
