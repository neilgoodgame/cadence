from pathlib import Path
from unittest.mock import MagicMock

from rest_framework.test import APIClient

from authn.jwt_utils import mint_jwt
from authn.oauth_utils import issue_token_pair

FIXTURES_DIR = Path(__file__).resolve().parent.parent / "tests_fixtures"


def _bearer_client(user, scope="activities:read activities:write"):
    access_token, _ = issue_token_pair(user, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access_token.token}")
    return client


def _delegated_client(sub, athlete_id, scopes):
    token, _claims = mint_jwt(sub=sub.id, athlete_id=athlete_id.id, scopes=scopes, expires_in=60)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {token}")
    return client


def _fit_msg(**fields):
    msg = MagicMock()
    msg.get_value.side_effect = lambda key: fields.get(key)
    return msg
