"""Runs `workouts.inference.infer_workout` against real activity history and
prints a readable report, so the heuristic can be tuned by eye against real
data before it's wired into any endpoint's default behavior or ported to Java.

    python manage.py infer_workout_report --athlete you@example.com
    python manage.py infer_workout_report --athlete you@example.com --activity act_123 --auto-repeats
"""

from typing import Any

from django.core.management.base import BaseCommand, CommandError
from django.db.models import Count

from accounts.models import User
from activities.models import Activity
from workouts.calculations import compute_duration_and_tss
from workouts.inference import infer_workout
from workouts.models import Workout


def _describe_steps(steps: list[dict[str, Any]], indent: str = "") -> list[str]:
    lines = []
    for step in steps:
        if step["kind"] == "repeat":
            lines.append(f"{indent}{step['repeat']}x [")
            lines.extend(_describe_steps(step["children"], indent + "    "))
            lines.append(f"{indent}]")
            continue
        target = step["target_type"]
        if step["target_low"] is not None:
            pct = (
                step["target_low"]
                if step["target_low"] == step["target_high"]
                else f"{step['target_low']}-{step['target_high']}"
            )
            target = f"{target} {pct}%"
        lines.append(f"{indent}{step['kind']:7} {step['duration'] or 0:>4}s  {target}")
    return lines


class Command(BaseCommand):
    help = "Runs the workout-inference heuristic against real activity history and prints a readable accuracy report."

    def add_arguments(self, parser):
        parser.add_argument("--athlete", required=True, help="Athlete email.")
        parser.add_argument("--activity", help="Only run against this one activity id.")
        parser.add_argument("--auto-repeats", action="store_true", help="Enable repeat-group auto-detection.")

    def handle(self, *args, **options):
        try:
            athlete = User.objects.get(email=options["athlete"])
        except User.DoesNotExist as exc:
            raise CommandError(f"No user with email {options['athlete']!r}") from exc

        activities = (
            Activity.objects.filter(athlete=athlete, sport__in=dict(Workout.SPORT_CHOICES).keys())
            .annotate(lap_count=Count("laps"))
            .filter(lap_count__gt=0)
            .prefetch_related("laps")
            .order_by("-start_date")
        )
        if options["activity"]:
            activities = activities.filter(pk=options["activity"])

        activities = list(activities)
        if not activities:
            self.stdout.write("No eligible bike/run activities with laps found.")
            return

        for activity in activities:
            result = infer_workout(activity, auto_detect_repeats=options["auto_repeats"])
            duration, tss = compute_duration_and_tss(result["steps"])

            self.stdout.write(
                self.style.MIGRATE_HEADING(
                    f"\n{activity.id}  {activity.name}  ({activity.sport}, {activity.lap_count} laps)"
                )
            )
            self.stdout.write(f"  laps: {activity.lap_count} -> steps: {len(result['steps'])}")
            for line in _describe_steps(result["steps"]):
                self.stdout.write(f"  {line}")
            self.stdout.write(
                f"  duration: inferred {duration}s vs recorded {activity.moving_time}s"
                f"  |  tss: inferred {tss} vs recorded {activity.tss}"
            )
