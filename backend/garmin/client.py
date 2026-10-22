"""Garmin Connect Personal API: exchange a Personal API key for a 24-hour bearer token, list
activities, download an activity's ZIP (which holds the original .fit). Paths come from Garmin's
own connect-personal-api-python client. Neither the key nor the token is ever logged or put in
an error message. Mirrors ``GarminApiClient`` in backend_java.
"""

from dataclasses import dataclass
from datetime import UTC, date, datetime

import requests
from django.conf import settings
from rest_framework.exceptions import APIException

from core.exceptions import UnprocessableEntityError

# Garmin's list endpoint 500s above 50 per page.
PAGE_SIZE = 50
# Safety ceiling on how many summaries one sync will page through.
MAX_SUMMARIES = 1000
_TIMEOUT = (10, 60)  # connect, read


class GarminUpstreamError(APIException):
    status_code = 502
    default_detail = "Could not reach Garmin."
    default_code = "bad_gateway"


@dataclass(frozen=True)
class ActivitySummary:
    activity_id: int
    start_time: datetime


def _request(method: str, path: str, *, headers: dict[str, str], params: dict | None = None) -> requests.Response:
    base = settings.GARMIN_API_BASE_URL.rstrip("/")
    try:
        response = requests.request(method, base + path, headers=headers, params=params, timeout=_TIMEOUT)
    except requests.RequestException as exc:
        raise GarminUpstreamError() from exc
    if response.status_code in (401, 403):
        raise UnprocessableEntityError("Garmin rejected your API key - it may have expired or been revoked.")
    if not response.ok:
        raise GarminUpstreamError(f"Garmin returned an error (HTTP {response.status_code}).")
    return response


def exchange_key(api_key: str) -> str:
    response = _request(
        "POST",
        "/personalapitoken-service/personal-api/personal-api-key/token",
        headers={"Authorization": f"ApiKey {api_key}"},
    )
    token = response.text.strip()
    if not token:
        raise GarminUpstreamError("Garmin returned no access token.")
    return token


def latest(token: str) -> list[ActivitySummary]:
    """Garmin's single newest activity, or an empty list when the account has none."""
    response = _request(
        "GET",
        "/activitylist-service/activities/search/activities",
        headers={"Authorization": f"Bearer {token}"},
        params={"start": 0, "limit": 1},
    )
    try:
        page = response.json()[:1]
        return [
            ActivitySummary(
                int(item["activityId"]),
                datetime.strptime(item["startTimeGMT"], "%Y-%m-%d %H:%M:%S").replace(tzinfo=UTC),
            )
            for item in page
        ]
    except (ValueError, KeyError, TypeError) as exc:
        raise GarminUpstreamError("Garmin returned an unreadable activity list.") from exc


def list_since(token: str, start_date: date) -> list[ActivitySummary]:
    """Newest first, matching Garmin's own order."""
    found: list[ActivitySummary] = []
    for offset in range(0, MAX_SUMMARIES, PAGE_SIZE):
        response = _request(
            "GET",
            "/activitylist-service/activities/search/activities",
            headers={"Authorization": f"Bearer {token}"},
            params={"start": offset, "limit": PAGE_SIZE, "startDate": start_date.isoformat()},
        )
        try:
            page = response.json()
            found.extend(
                ActivitySummary(
                    int(item["activityId"]),
                    datetime.strptime(item["startTimeGMT"], "%Y-%m-%d %H:%M:%S").replace(tzinfo=UTC),
                )
                for item in page
            )
        except (ValueError, KeyError, TypeError) as exc:
            raise GarminUpstreamError("Garmin returned an unreadable activity list.") from exc
        if len(page) < PAGE_SIZE:
            break
    return found


def download_zip(token: str, activity_id: int) -> bytes:
    response = _request(
        "GET", f"/download-service/files/activity/{activity_id}", headers={"Authorization": f"Bearer {token}"}
    )
    if not response.content:
        raise GarminUpstreamError("Garmin returned an empty file.")
    return response.content
