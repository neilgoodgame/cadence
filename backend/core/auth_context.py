from typing import TYPE_CHECKING, cast

from rest_framework.request import Request

if TYPE_CHECKING:
    from accounts.models import User


def get_effective_athlete_id(request: Request) -> tuple[str, str]:
    """Returns (sub, athlete_id) for the authenticated request.

    JWTs can name a different athlete_id than the signed-in principal (delegation), same as a
    personal access token created with an athlete_id (see accounts.delegation) - both are the
    coach-acting-on-an-athlete's-data case. An ordinary OAuth2 access token or self-scoped
    personal access token falls through to _virtual_coach_delegated_athlete_id below - a real
    user always still acts as its own owner there, only a virtual coach account delegates.
    """
    claims = request.auth if isinstance(request.auth, dict) else None
    if claims is not None:
        sub = claims["sub"]
        athlete_id = claims.get("athlete_id") or sub
        return sub, athlete_id
    from accounts.models import PersonalAccessToken

    if isinstance(request.auth, PersonalAccessToken) and request.auth.delegated_athlete_id:
        return cast(str, request.user.id), cast(str, request.auth.delegated_athlete_id)
    # Permission classes (IsAuthenticated) guarantee request.user isn't AnonymousUser here.
    sub = cast(str, request.user.id)
    delegated = _virtual_coach_delegated_athlete_id(request.user)
    return sub, delegated or sub


def _virtual_coach_delegated_athlete_id(user: "User") -> str | None:
    """Deliberately scoped to is_virtual accounts only, not "any coach with exactly one active
    relationship" - a real user's own OAuth2 session (the web app's normal login) must never
    silently start showing someone else's data just because they happen to coach one athlete. A
    virtual account has no legitimate "self" view at all (see User.is_virtual's comment), and is
    only ever linked to exactly one athlete by construction, so this is unambiguous.
    """
    if not user.is_virtual:
        return None
    from accounts.models import UserRelationship

    relationship = UserRelationship.objects.filter(grantee=user, status=UserRelationship.STATUS_ACTIVE).first()
    return relationship.owner_id if relationship else None


def get_request_scopes(request: Request) -> set[str]:
    auth = request.auth
    if isinstance(auth, dict):
        return set(auth.get("scope", "").split())
    scopes = getattr(auth, "scopes", None)
    if scopes is not None:
        return set(scopes)
    scope = getattr(auth, "scope", None)
    if scope is not None:
        return set(scope.split())
    return set()


def authenticated_user(request: Request) -> "User":
    """request.user narrowed to the concrete User model.

    Permission classes (IsAuthenticated, the project-wide default) guarantee
    request.user isn't AnonymousUser by the time a view body runs; the stubs
    can't see that, so call sites that pass request.user into a queryset
    filter or model field use this instead of a bare cast at every call site.
    """
    return cast("User", request.user)
