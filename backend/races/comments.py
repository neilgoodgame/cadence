"""Shared validation for replying to a race comment - mirrors activities/comments.py's
resolve_parent_comment exactly, scoped to Race/RaceComment instead of Activity/ActivityComment.
"""

from rest_framework.exceptions import NotFound, ValidationError

from .models import Race, RaceComment


def resolve_parent_comment(race: Race, parent_id: str | None) -> RaceComment | None:
    """None for a top-level comment, or the validated parent for a reply. Single-level
    threading only: the parent must belong to the same race and not itself be a reply.
    """
    if parent_id is None:
        return None
    try:
        parent = RaceComment.objects.get(pk=parent_id)
    except RaceComment.DoesNotExist as exc:
        raise NotFound("No such comment.") from exc
    if parent.race_id != race.id:
        raise ValidationError({"parent_id": "That comment is not on this race."})
    if parent.parent_id is not None:
        raise ValidationError({"parent_id": "Cannot reply to a reply - only top-level comments can be replied to."})
    return parent


def snapshot_role(race: Race, author_id: str) -> str:
    """The author's role at post time - 'athlete' for the race's own athlete, else the
    coaching relationship's role. Stored on the comment rather than recomputed later, so
    a comment keeps showing the role its author had when they posted it."""
    if author_id == race.athlete_id:
        return "athlete"
    from accounts.models import UserRelationship

    relationship = UserRelationship.objects.filter(
        owner_id=race.athlete_id, grantee_id=author_id, status=UserRelationship.STATUS_ACTIVE
    ).first()
    return relationship.role if relationship else "coach"
