from datetime import datetime

from lxml import etree
from lxml.etree import _Element

from .types import Lap, ParsedActivity, Sample
from .utils import ensure_utc

SPORT_MAP = {
    "running": "run",
    "biking": "bike",
    "other": "bike",
}


def _find(node: _Element, name: str) -> _Element | None:
    return node.find(f".//{{*}}{name}")


def _text(node: _Element | None, name: str) -> str | None:
    if node is None:
        return None
    found = _find(node, name)
    return found.text if found is not None else None


def _parse_time(value: str | None) -> datetime | None:
    if value is None:
        return None
    return ensure_utc(datetime.fromisoformat(value.replace("Z", "+00:00")))


def parse_tcx(path: str) -> ParsedActivity:
    tree = etree.parse(path)
    root = tree.getroot()

    activity_el = root.find(".//{*}Activity")
    if activity_el is None:
        raise ValueError("TCX file contains no Activity element.")

    sport = SPORT_MAP.get((activity_el.get("Sport") or "").lower(), "bike")

    lap_els = activity_el.findall("{*}Lap")
    laps: list[Lap] = []
    samples: list[Sample] = []
    start_time = None

    for index, lap_el in enumerate(lap_els, start=1):
        lap_start = _parse_time(lap_el.get("StartTime"))
        if start_time is None:
            start_time = lap_start

        duration_raw = _text(lap_el, "TotalTimeSeconds")
        distance_raw = _text(lap_el, "DistanceMeters")
        hr_wrapper = _find(lap_el, "AverageHeartRateBpm")
        avg_hr_raw = _text(hr_wrapper, "Value") if hr_wrapper is not None else None

        for tp in lap_el.findall(".//{*}Trackpoint"):
            t_time = _parse_time(_text(tp, "Time"))
            if t_time is None or start_time is None:
                continue
            position = _find(tp, "Position")
            lat_raw = _text(position, "LatitudeDegrees")
            lng_raw = _text(position, "LongitudeDegrees")
            lat = float(lat_raw) if lat_raw else None
            lng = float(lng_raw) if lng_raw else None

            altitude_raw = _text(tp, "AltitudeMeters")
            distance_m_raw = _text(tp, "DistanceMeters")
            hr_node = _find(tp, "HeartRateBpm")
            hr_raw = _text(hr_node, "Value") if hr_node is not None else None
            cadence_raw = _text(tp, "Cadence")
            watts_raw = _text(tp, "Watts")
            speed_raw = _text(tp, "Speed")

            samples.append(
                {
                    "t": int((t_time - start_time).total_seconds()),
                    "lat": lat,
                    "lng": lng,
                    "altitude": float(altitude_raw) if altitude_raw else None,
                    "distance_km": float(distance_m_raw) / 1000 if distance_m_raw else None,
                    "heartrate": int(float(hr_raw)) if hr_raw else None,
                    "cadence": int(float(cadence_raw)) if cadence_raw else None,
                    "power": int(float(watts_raw)) if watts_raw else None,
                    "speed": float(speed_raw) if speed_raw else None,
                }
            )

        laps.append(
            {
                "index": index,
                "duration": int(float(duration_raw)) if duration_raw else 0,
                "distance_km": float(distance_raw) / 1000 if distance_raw else 0.0,
                "avg_hr": int(float(avg_hr_raw)) if avg_hr_raw else None,
                "avg_power": None,
            }
        )

    if not samples:
        raise ValueError("TCX file contains no trackpoints.")

    has_gps = any(s["lat"] is not None for s in samples)

    return {
        "sport": sport,
        "environment": "outdoor" if has_gps else "indoor",
        "has_gps": has_gps,
        "start_date": start_time,
        "source": "tcx",
        "samples": samples,
        "laps": laps,
        "distance_source": "gps" if has_gps else "trainer",
    }
