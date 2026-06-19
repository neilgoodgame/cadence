import datetime

from django.conf import settings
from django.utils import timezone
from oauth2_provider.models import get_access_token_model, get_application_model, get_refresh_token_model

from authn.token_generators import generate_access_token, generate_refresh_token

ALL_SCOPES = "activities:read activities:write workouts:write calendar:write coach gear:write"

FIRST_PARTY_APPLICATION_NAME = "cadence-first-party"


def _first_party_application():
    Application = get_application_model()
    application, _ = Application.objects.get_or_create(
        name=FIRST_PARTY_APPLICATION_NAME,
        defaults={
            "client_type": Application.CLIENT_CONFIDENTIAL,
            "authorization_grant_type": Application.GRANT_PASSWORD,
        },
    )
    return application


def issue_token_pair(user, scope=ALL_SCOPES):
    """Issues an OAuth2 access/refresh token pair directly against a shared
    first-party Application, bypassing the redirect-based authorization-code
    dance — there's no frontend yet to perform it. Used at registration time.
    """
    AccessToken = get_access_token_model()
    RefreshToken = get_refresh_token_model()
    application = _first_party_application()

    expires = timezone.now() + datetime.timedelta(seconds=settings.OAUTH2_PROVIDER["ACCESS_TOKEN_EXPIRE_SECONDS"])
    access_token = AccessToken.objects.create(
        user=user,
        application=application,
        token=generate_access_token(),
        expires=expires,
        scope=scope,
    )
    refresh_token = RefreshToken.objects.create(
        user=user,
        application=application,
        token=generate_refresh_token(),
        access_token=access_token,
    )
    return access_token, refresh_token
