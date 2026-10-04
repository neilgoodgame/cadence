"""Backfills aerobic decoupling and durability (ActivityDurability rows) across existing
activities - for bootstrapping the feature against history, or after a fix to the computation
itself. Batches per athlete and logs qualified/not-qualified/errored counts, same shape as the
Threshold suggestions feature's own backfill script (infra/scripts/backfill_workout_durations.sh)
but as a management command since this needs no server-side auth token, just DB access.

    python manage.py recompute_durability
    python manage.py recompute_durability --athlete ath_abc123
    python manage.py recompute_durability --since 2026-01-01
"""

import logging

from django.core.management.base import BaseCommand, CommandError
from django.db.models import Q

from accounts.models import User
from activities.models import Activity
from uploads.processing import compute_decoupling_and_durability_for_activity

logger = logging.getLogger(__name__)


class Command(BaseCommand):
    help = "Backfills aerobic decoupling and durability for existing bike/run activities."

    def add_arguments(self, parser):
        parser.add_argument("--athlete", help="Only this athlete's activities (by id).")
        parser.add_argument("--since", help="Only activities on/after this date (YYYY-MM-DD).")

    def handle(self, *args, **options):
        activities = Activity.objects.filter(sport__in=("bike", "run"), parent_activity__isnull=True).select_related(
            "athlete"
        )

        if options["athlete"]:
            if not User.objects.filter(pk=options["athlete"]).exists():
                raise CommandError(f"No athlete with id {options['athlete']!r}")
            activities = activities.filter(athlete_id=options["athlete"])
        if options["since"]:
            activities = activities.filter(Q(start_date__date__gte=options["since"]))

        activities = list(activities.order_by("start_date"))
        total = len(activities)
        qualified = not_qualified = errored = 0

        for i, activity in enumerate(activities):
            try:
                compute_decoupling_and_durability_for_activity(activity, activity.athlete)
            except Exception:
                errored += 1
                logger.warning("recompute_durability failed for activity %s", activity.id, exc_info=True)
                continue
            if activity.decoupling_qualified:
                qualified += 1
            else:
                not_qualified += 1
            if (i + 1) % 100 == 0 or i + 1 == total:
                self.stdout.write(f"{i + 1}/{total}...")

        self.stdout.write(
            self.style.SUCCESS(
                f"Done. {total} activities: {qualified} qualified, {not_qualified} not qualified, {errored} errored."
            )
        )
