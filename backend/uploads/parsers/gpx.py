import math
from typing import Any

import gpxpy

from .types import Lap, ParsedActivity, Sample
from .utils import ensure_utc

SPORT_TYPE_MAP = {
    "running": "run",
    "run": "run",
    "cycling": "bike",
    "biking": "bike",
    "bike": "bike",
    "swimming": "swim",
    "swim": "swim",
    "walking": "walk",
    "walk": "walk",
    "hiking": "walk",
}


def _haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def _extension_value(point: Any, local_name: str) -> str | None:
    for ext in point.extensions:
        for el in ext.iter():
            tag = el.tag.split("}")[-1] if "}" in el.tag else el.tag
            if tag.lower() == local_name.lower() and el.text:
                return str(el.text)
    return None


def parse_gpx(path: str) -> ParsedActivity:
    with open(path, "rb") as f:
        # gpxpy's stub declares IO[str], but parser.py handles bytes at
        # runtime (decodes via isinstance(text, bytes) before parsing) -
        # binary mode here sidesteps locale-dependent default-encoding bugs.
        gpx = gpxpy.parse(f)  # type: ignore[type-var]

    sport = "bike"
    for track in gpx.tracks:
        if track.type:
            sport = SPORT_TYPE_MAP.get(track.type.lower(), sport)
            break

    points = []
    for track in gpx.tracks:
        for segment in track.segments:
            points.extend(segment.points)

    if not points:
        raise ValueError("GPX file contains no track points.")

    start_time = ensure_utc(points[0].time)
    if start_time is None:
        raise ValueError("GPX file's first track point has no timestamp.")
    samples: list[Sample] = []
    cumulative_km = 0.0
    prev = None
    for point in points:
        if prev is not None and point.latitude is not None and prev.latitude is not None:
            cumulative_km += _haversine_km(prev.latitude, prev.longitude, point.latitude, point.longitude)
        point_time = ensure_utc(point.time)
        t = int((point_time - start_time).total_seconds()) if point_time else len(samples)
        hr_raw = _extension_value(point, "hr")
        cad_raw = _extension_value(point, "cad")
        samples.append(
            {
                "t": t,
                "lat": point.latitude,
                "lng": point.longitude,
                "altitude": point.elevation,
                "distance_km": round(cumulative_km, 4),
                "heartrate": int(hr_raw) if hr_raw else None,
                "cadence": int(cad_raw) if cad_raw else None,
            }
        )
        prev = point

    laps: list[Lap] = []
    lap_index = 1
    for track in gpx.tracks:
        for segment in track.segments:
            if not segment.points:
                continue
            seg_start = ensure_utc(segment.points[0].time)
            seg_end = ensure_utc(segment.points[-1].time)
            duration = int((seg_end - seg_start).total_seconds()) if seg_start and seg_end else 0
            seg_distance = 0.0
            seg_prev = None
            hr_vals = []
            for point in segment.points:
                if seg_prev is not None and point.latitude is not None and seg_prev.latitude is not None:
                    seg_distance += _haversine_km(
                        seg_prev.latitude, seg_prev.longitude, point.latitude, point.longitude
                    )
                hr_raw = _extension_value(point, "hr")
                if hr_raw:
                    hr_vals.append(int(hr_raw))
                seg_prev = point
            laps.append(
                {
                    "index": lap_index,
                    "duration": duration,
                    "distance_km": round(seg_distance, 4),
                    "avg_hr": round(sum(hr_vals) / len(hr_vals)) if hr_vals else None,
                    "avg_power": None,
                }
            )
            lap_index += 1

    return {
        "sport": sport,
        "environment": "outdoor",
        "has_gps": True,
        "start_date": start_time,
        "source": "gpx",
        "samples": samples,
        "laps": laps,
        "distance_source": "gps",
    }
