"""What happens to an activity when it gets linked to a workout template - shared between
ingest-time auto-match (uploads/processing.py::attempt_workout_match, matched by date against a
ScheduledWorkout) and manually accepting a Scan-for-matches candidate
(views.py::ActivityDetailView.patch). Both are "this activity is now matched to that workout" in
the same sense, so they apply the same athlete preferences rather than each having their own.

Deliberately excludes the "Auto-matched" system tag attempt_workout_match also applies - that
tag specifically marks a match the system made without a human confirming it, which is the
opposite of what accepting a candidate means.
"""

from accounts.models import User

from .lap_derivation import replace_laps_with_derived
from .models import Activity, ActivityTag


def matched_activity_name(workout_name: str, activity: Activity, athlete: User) -> str:
    """athlete.rename_matched_activities's naming - the %Y-%m-%d format matches the
    device-derived default name this replaces (see uploads/processing.py's "{sport} on {date}"
    f-string), so a renamed activity still sorts/reads consistently with any sibling that
    wasn't matched (or whose athlete has the preference off).
    """
    if athlete.append_match_date_to_name:
        return f"{workout_name} - {activity.start_date:%Y-%m-%d}"
    return workout_name


def apply_match_rename(activity: Activity, workout_name: str, athlete: User, update_fields: list[str]) -> None:
    """Mutates activity.name in place per athlete.rename_matched_activities and appends "name"
    to update_fields - skipped if the caller already put "name" in update_fields (an explicit
    rename in the same request wins over the preference-driven one). Caller still owns the
    actual activity.save()."""
    if athlete.rename_matched_activities and "name" not in update_fields:
        activity.name = matched_activity_name(workout_name, activity, athlete)
        update_fields.append("name")


def apply_match_side_effects(activity: Activity, workout, athlete: User) -> None:
    """Copies the workout's tags (if enabled) and (re)derives laps from it (if enabled) - call
    once activity.workout has actually been saved, since lap derivation reads it back.

    Workouts and activities now share the same Tag catalog (WorkoutTag/ActivityTag both point at
    activities.Tag), so "copying" a workout's tags onto the activity is just linking the
    activity to the same Tag rows the workout already carries - no more get-or-create-by-name.
    """
    if athlete.copy_matched_workout_tags:
        for tag in workout.tags.all():
            ActivityTag.objects.get_or_create(activity=activity, tag=tag)
    if athlete.lap_source == "matched_workout":
        replace_laps_with_derived(activity, workout)
