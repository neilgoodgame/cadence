from rest_framework.permissions import BasePermission

from core.auth_context import get_effective_athlete_id


def user_may_read(sub_id, athlete_id):
    if sub_id == athlete_id:
        return True
    from accounts.models import UserRelationship

    return UserRelationship.objects.filter(
        owner_id=athlete_id, grantee_id=sub_id, status=UserRelationship.STATUS_ACTIVE
    ).exists()


def user_may_write(sub_id, athlete_id):
    if sub_id == athlete_id:
        return True
    from accounts.models import UserRelationship

    return UserRelationship.objects.filter(
        owner_id=athlete_id,
        grantee_id=sub_id,
        status=UserRelationship.STATUS_ACTIVE,
        role=UserRelationship.ROLE_COACH,
    ).exists()


def _target_athlete_id(obj):
    return getattr(obj, "athlete_id", None) or getattr(obj, "owner_id", None) or obj.pk


class IsAuthorizedForAthleteRead(BasePermission):
    """Object-level check: the request's principal may read data owned by the object's athlete."""

    def has_permission(self, request, view):
        return bool(request.user and request.user.is_authenticated)

    def has_object_permission(self, request, view, obj):
        sub, _ = get_effective_athlete_id(request)
        return user_may_read(sub, _target_athlete_id(obj))


class IsAuthorizedForAthleteWrite(BasePermission):
    """Object-level check: the request's principal may write data owned by the object's athlete."""

    def has_permission(self, request, view):
        return bool(request.user and request.user.is_authenticated)

    def has_object_permission(self, request, view, obj):
        sub, _ = get_effective_athlete_id(request)
        return user_may_write(sub, _target_athlete_id(obj))
