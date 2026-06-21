from datetime import UTC, datetime

from rest_framework.test import APIClient

from authn.jwt_utils import mint_jwt
from authn.oauth_utils import issue_token_pair

from ..models import Activity


def _bearer_client(user, scope="activities:read activities:write coach"):
    access_token, _ = issue_token_pair(user, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access_token.token}")
    return client


def _delegated_client(sub, athlete_id, scopes):
    token, _claims = mint_jwt(sub=sub.id, athlete_id=athlete_id.id, scopes=scopes, expires_in=60)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {token}")
    return client


def _make_activity(athlete, **kwargs):
    defaults = {
        "sport": "run",
        "environment": "outdoor",
        "name": "Morning Run",
        "start_date": datetime(2026, 1, 1, 7, 0, tzinfo=UTC),
        "moving_time": 1800,
        "distance_km": 5.0,
        "tss": 50,
        "avg_hr": 140,
        "max_hr": 160,
    }
    defaults.update(kwargs)
    return Activity.objects.create(athlete=athlete, **defaults)
