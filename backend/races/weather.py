"""Race-day weather forecast, from Open-Meteo (free, no API key, same no-auth-needed shape as
courses/geocoding.py's Nominatim call). Open-Meteo's own forecast horizon is ~16 days, but this
is capped at FORECAST_HORIZON_DAYS by product decision - a forecast more than 10 days out is
unreliable enough to not be worth showing. Needs a course's resolved start point (start_lat/
start_lng) - a race with no course, or a course with no start point, has nothing to look up and
is always "unavailable" rather than an error.

Cached via django.core.cache, same as courses/geocoding.py, but with a short TTL instead of 30
days - unlike a course's start point, a forecast goes stale and needs re-fetching. Mirrors
backend_java/.../races/WeatherService.java exactly.
"""

import datetime
import logging

import requests
from django.core.cache import cache

from .models import Race

logger = logging.getLogger(__name__)

FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
FORECAST_HORIZON_DAYS = 10
CACHE_TTL_SECONDS = 3 * 60 * 60
REQUEST_TIMEOUT_SECONDS = 5


def _unavailable() -> dict:
    return {
        "available": False,
        "date": None,
        "temperature_max_c": None,
        "temperature_min_c": None,
        "precipitation_probability_pct": None,
        "wind_speed_max_kmh": None,
    }


def _cache_key(race_id: str) -> str:
    return f"race-weather:{race_id}"


def _fetch(lat: float, lng: float, date: datetime.date) -> dict:
    date_str = date.isoformat()
    try:
        response = requests.get(
            FORECAST_URL,
            params={
                "latitude": lat,
                "longitude": lng,
                "daily": "temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max",
                "timezone": "auto",
                "start_date": date_str,
                "end_date": date_str,
            },
            timeout=REQUEST_TIMEOUT_SECONDS,
        )
        response.raise_for_status()
        daily = response.json().get("daily") or {}
        if not daily.get("time"):
            return _unavailable()
        return {
            "available": True,
            "date": date,
            "temperature_max_c": daily["temperature_2m_max"][0],
            "temperature_min_c": daily["temperature_2m_min"][0],
            "precipitation_probability_pct": daily["precipitation_probability_max"][0],
            "wind_speed_max_kmh": daily["wind_speed_10m_max"][0],
        }
    except Exception:
        logger.warning("Weather forecast failed for (%s, %s)", lat, lng, exc_info=True)
        return _unavailable()


def forecast_for(race: Race) -> dict:
    """Best-effort forecast for a race's day. Never raises - returns an "unavailable" dict for a
    missing course/start point, a race outside the forecast window, or any failure calling
    Open-Meteo."""
    course = race.course
    if course is None or course.start_lat is None or course.start_lng is None:
        return _unavailable()

    days_until = (race.date - datetime.date.today()).days
    if days_until < 0 or days_until > FORECAST_HORIZON_DAYS:
        return _unavailable()

    key = _cache_key(race.id)
    cached = cache.get(key)
    if cached is not None:
        return cached

    forecast = _fetch(course.start_lat, course.start_lng, race.date)
    cache.set(key, forecast, CACHE_TTL_SECONDS)
    return forecast
