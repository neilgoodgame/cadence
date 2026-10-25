"""Renaming and deleting tags - shared between the REST endpoint (TagDetailView) and the
rename_tag/delete_tag MCP tools, so both go through the same merge logic. Tag is shared between
activities (ActivityTag) and workouts (workouts.WorkoutTag) - a rename-merge repoints both.
"""

from .models import ActivityTag, Tag


def rename_tag(tag: Tag, new_name: str) -> Tag:
    """Renames `tag` to `new_name`. If the athlete already has a different tag with that name
    (case-insensitive - Tag's own uniqueness constraint is case-sensitive, but two tags that only
    differ by case are the same tag to a person typing one in), merges into it instead: every
    activity or workout linked to `tag` ends up linked to the existing tag (one that already
    carries both keeps just the one link, via get_or_create), and `tag` is deleted - its now-
    orphaned links go with it via ActivityTag.tag/WorkoutTag.tag's CASCADE. Returns whichever tag
    now holds `new_name`: `tag` itself (renamed in place) or the pre-existing one it was merged
    into.
    """
    # Imported here, not at module level - workouts imports activities.models (Tag's own home),
    # so a top-level import the other way round would be circular.
    from workouts.models import WorkoutTag

    existing = Tag.objects.filter(athlete_id=tag.athlete_id, name__iexact=new_name).exclude(pk=tag.pk).first()
    if existing is None:
        tag.name = new_name
        tag.save(update_fields=["name"])
        return tag

    for activity_id in tag.activity_tags.values_list("activity_id", flat=True):
        ActivityTag.objects.get_or_create(activity_id=activity_id, tag=existing)
    for workout_id in tag.workout_tags.values_list("workout_id", flat=True):
        WorkoutTag.objects.get_or_create(workout_id=workout_id, tag=existing)
    tag.delete()
    return existing
