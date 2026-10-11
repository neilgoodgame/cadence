"""Reverse geocoding for a course's start point. Nothing in the repo did this before -
Nominatim (OpenStreetMap) is free and needs no API key, at the cost of a 1 req/s rate
limit and a required User-Agent, both respected here. Cached via django.core.cache
(the same process-local cache athletes/threshold_suggestions.py relies on) since a
course's start point never moves.
"""

import logging

import requests
from django.core.cache import cache

logger = logging.getLogger(__name__)

NOMINATIM_URL = "https://nominatim.openstreetmap.org/reverse"
USER_AGENT = "Cadence/1.0 (training log; contact via app)"
CACHE_TTL_SECONDS = 60 * 60 * 24 * 30
REQUEST_TIMEOUT_SECONDS = 5


def _cache_key(lat: float, lng: float) -> str:
    return f"course-geocode:{lat:.4f}:{lng:.4f}"


def reverse_geocode(lat: float, lng: float) -> str:
    """Best-effort place name for a start point, e.g. "Valencia, Spain". Returns ""
    on any failure (network, rate limit, no address match) - location_name is a
    user-editable nice-to-have, never something course creation should fail over.
    """
    key = _cache_key(lat, lng)
    cached = cache.get(key)
    if cached is not None:
        return cached

    location_name = ""
    try:
        response = requests.get(
            NOMINATIM_URL,
            params={"lat": lat, "lon": lng, "format": "jsonv2", "zoom": 14},
            headers={"User-Agent": USER_AGENT},
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
        response.raise_for_status()
        data = response.json()
        address = data.get("address", {})
        place = (
            address.get("city")
            or address.get("town")
            or address.get("village")
            or address.get("suburb")
            or address.get("county")
        )
        country = address.get("country")
        if place and country:
            location_name = f"{place}, {country}"
        else:
            location_name = place or country or ""
    except Exception:
        logger.warning("Reverse geocode failed for (%s, %s)", lat, lng, exc_info=True)

    cache.set(key, location_name, CACHE_TTL_SECONDS)
    return location_name
