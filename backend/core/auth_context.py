from typing import TYPE_CHECKING, cast

from rest_framework.request import Request

if TYPE_CHECKING:
    from accounts.models import User


def get_effective_athlete_id(request: Request) -> tuple[str, str]:
    """Returns (sub, athlete_id) for the authenticated request.

    JWTs can name a different athlete_id than the signed-in principal (delegation).
    OAuth2 access tokens and personal access tokens always act as their own owner.
    """
    claims = request.auth if isinstance(request.auth, dict) else None
    if claims is not None:
        sub = claims["sub"]
        athlete_id = claims.get("athlete_id") or sub
        return sub, athlete_id
    # Permission classes (IsAuthenticated) guarantee request.user isn't AnonymousUser here.
    return cast(str, request.user.id), cast(str, request.user.id)


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
