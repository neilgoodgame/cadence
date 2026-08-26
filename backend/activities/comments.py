"""Shared validation for replying to an activity comment - used by both
ActivityCommentListView.post (REST) and ActivityMCPTools.post_activity_comment (MCP), so the
single-level-threading rule can't drift between the two paths.
"""

from rest_framework.exceptions import NotFound, ValidationError

from .models import Activity, ActivityComment


def resolve_parent_comment(activity: Activity, parent_id: str | None) -> ActivityComment | None:
    """Returns None for a top-level comment (parent_id is None), or the validated parent for a
    reply. Single-level threading only: the parent must belong to the same activity and not
    itself be a reply - raises ValidationError/NotFound rather than silently re-parenting to the
    root, so a caller passing a stale/wrong id finds out immediately instead of the thread
    quietly reshaping itself.
    """
    if parent_id is None:
        return None
    try:
        parent = ActivityComment.objects.get(pk=parent_id)
    except ActivityComment.DoesNotExist as exc:
        raise NotFound("No such comment.") from exc
    if parent.activity_id != activity.id:
        raise ValidationError({"parent_id": "That comment is not on this activity."})
    if parent.parent_id is not None:
        raise ValidationError({"parent_id": "Cannot reply to a reply - only top-level comments can be replied to."})
    return parent
