def get_effective_athlete_id(request):
    """Returns (sub, athlete_id) for the authenticated request.

    JWTs can name a different athlete_id than the signed-in principal (delegation).
    OAuth2 access tokens and personal access tokens always act as their own owner.
    """
    claims = request.auth if isinstance(request.auth, dict) else None
    if claims is not None:
        sub = claims["sub"]
        athlete_id = claims.get("athlete_id") or sub
        return sub, athlete_id
    return request.user.id, request.user.id


def get_request_scopes(request):
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
