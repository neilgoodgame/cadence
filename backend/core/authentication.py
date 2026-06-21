import hmac
from typing import Any

import jwt as pyjwt
from django.utils import timezone
from rest_framework import exceptions
from rest_framework.authentication import BaseAuthentication, get_authorization_header
from rest_framework.request import Request


class JWTAuthentication(BaseAuthentication):
    """Authenticates Cadence-issued RS256 bearer JWTs (see authn.jwt_utils).

    Tokens from /oauth/token and /v1/auth/tokens are opaque cad_at_/cad_pat_
    strings, not JWTs, so get_unverified_header() raising lets those fall
    through to the other configured authentication classes untouched.
    """

    def authenticate(self, request: Request) -> tuple[Any, Any] | None:
        auth = get_authorization_header(request).split()
        if len(auth) != 2 or auth[0].lower() != b"bearer":
            return None
        token = auth[1].decode("utf-8")

        try:
            header = pyjwt.get_unverified_header(token)
        except pyjwt.InvalidTokenError:
            return None
        if header.get("alg") != "RS256":
            return None

        from authn.jwt_utils import decode_jwt

        try:
            claims = decode_jwt(token)
        except pyjwt.InvalidTokenError as exc:
            raise exceptions.AuthenticationFailed("Invalid or expired token.") from exc

        from accounts.models import User

        try:
            user = User.objects.get(pk=claims["sub"])
        except User.DoesNotExist as exc:
            raise exceptions.AuthenticationFailed("Unknown principal.") from exc

        return (user, claims)


class PersonalAccessTokenAuthentication(BaseAuthentication):
    def authenticate(self, request: Request) -> tuple[Any, Any] | None:
        from accounts.tokens import PREFIX, hash_secret, visible_prefix

        auth = get_authorization_header(request).split()
        if len(auth) != 2 or auth[0].lower() != b"bearer":
            return None
        token = auth[1].decode("utf-8")
        if not token.startswith(PREFIX):
            return None

        from accounts.models import PersonalAccessToken

        try:
            pat = PersonalAccessToken.objects.select_related("user").get(prefix=visible_prefix(token))
        except PersonalAccessToken.DoesNotExist as exc:
            raise exceptions.AuthenticationFailed("Invalid token.") from exc

        if not hmac.compare_digest(pat.hashed_secret, hash_secret(token)):
            raise exceptions.AuthenticationFailed("Invalid token.")

        today = timezone.now().date()
        if pat.expires_at and pat.expires_at < today:
            raise exceptions.AuthenticationFailed("Token expired.")

        if pat.last_used != today:
            PersonalAccessToken.objects.filter(pk=pat.pk).update(last_used=today)

        return (pat.user, pat)
