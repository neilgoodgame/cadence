"""GPX/track processing for Course: distance, elevation smoothing, the stored elevation
series sections are calculated from, route simplification and elevation-profile resampling. Shared by a GPX upload and a
from-activity course - both end up as a list of TrackPoint before process_track_points
takes over, so the two sources produce identical Course fields for the same geometry.

Java (io.jenetics:jpx) must apply the same smoothing/gain rules - see
backend/courses/fixtures/ and the parity test planned for backend_java/.
"""

import math
from dataclasses import dataclass
from typing import Any

import gpxpy

from uploads.parsers.gpx import _haversine_km

from .sections import build_elevation_series

MAX_GPX_BYTES = 10 * 1024 * 1024
MIN_TRACK_POINTS = 50

ELEVATION_SMOOTH_WINDOW_M = 50.0
ELEVATION_NOISE_THRESHOLD_M = 2.0

ROUTE_SIMPLIFY_MAX_POINTS = 500
ELEVATION_PROFILE_POINTS = 200

LOOP_THRESHOLD_M = 200.0


class GpxProcessingError(ValueError):
    """A GPX file or activity track that can't be turned into a Course - too small,
    too few points, or unparseable. Callers surface this as a 422."""


@dataclass
class TrackPoint:
    lat: float
    lng: float
    elevation: float | None
    distance_km: float  # cumulative from the first point


def parse_gpx_track(raw_bytes: bytes) -> list[TrackPoint]:
    if len(raw_bytes) > MAX_GPX_BYTES:
        raise GpxProcessingError("GPX file exceeds the 10 MB limit.")

    try:
        gpx = gpxpy.parse(raw_bytes.decode("utf-8", errors="replace"))
    except Exception as exc:
        raise GpxProcessingError("The GPX file could not be parsed.") from exc

    points = []
    for track in gpx.tracks:
        for segment in track.segments:
            points.extend(segment.points)
    if not points:
        for route in gpx.routes:
            points.extend(route.points)

    usable = [p for p in points if p.latitude is not None and p.longitude is not None]
    if len(usable) < MIN_TRACK_POINTS:
        raise GpxProcessingError(f"GPX file has fewer than {MIN_TRACK_POINTS} usable track points.")

    track_points: list[TrackPoint] = []
    cumulative_km = 0.0
    prev = None
    for p in usable:
        if prev is not None:
            cumulative_km += _haversine_km(prev.latitude, prev.longitude, p.latitude, p.longitude)
        track_points.append(
            TrackPoint(lat=p.latitude, lng=p.longitude, elevation=p.elevation, distance_km=cumulative_km)
        )
        prev = p
    return track_points


def track_points_from_activity(activity: Any) -> list[TrackPoint]:
    """Builds TrackPoints from an activity's recorded lat/lng/altitude/distance_km -
    reuses the already-recorded cumulative distance rather than re-deriving it, and
    drops samples where distance didn't advance (the activity was paused)."""
    rows = (
        activity.records.filter(lat__isnull=False, lng__isnull=False)
        .order_by("t")
        .values_list("lat", "lng", "altitude", "distance_km")
    )

    track_points: list[TrackPoint] = []
    last_distance: float | None = None
    for lat, lng, altitude, distance_km in rows:
        if last_distance is not None and distance_km is not None and distance_km <= last_distance:
            continue
        track_points.append(TrackPoint(lat=lat, lng=lng, elevation=altitude, distance_km=distance_km or 0.0))
        last_distance = distance_km

    if len(track_points) < MIN_TRACK_POINTS:
        raise GpxProcessingError(f"Activity has fewer than {MIN_TRACK_POINTS} usable GPS points.")
    return track_points


def _smoothed_elevations(points: list[TrackPoint]) -> list[float | None]:
    """Centered moving average over a 50m distance window, using a two-pointer
    sliding window since distance_km is monotonically non-decreasing."""
    n = len(points)
    smoothed: list[float | None] = [None] * n
    half_window_km = (ELEVATION_SMOOTH_WINDOW_M / 2) / 1000.0
    left = 0
    right = 0
    for i in range(n):
        center_km = points[i].distance_km
        lo, hi = center_km - half_window_km, center_km + half_window_km
        while left < n and points[left].distance_km < lo:
            left += 1
        if right < left:
            right = left
        while right < n and points[right].distance_km <= hi:
            right += 1
        window = [points[k].elevation for k in range(left, right) if points[k].elevation is not None]
        smoothed[i] = sum(window) / len(window) if window else None
    return smoothed


def _gain_loss(smoothed: list[float | None]) -> tuple[int, int]:
    """Counts gain/loss only once the smoothed elevation has moved >= 2m from the
    last confirmed baseline, so GPS/barometric noise under that doesn't accumulate."""
    values = [v for v in smoothed if v is not None]
    if not values:
        return 0, 0
    gain = 0.0
    loss = 0.0
    baseline = values[0]
    for v in values[1:]:
        if v - baseline >= ELEVATION_NOISE_THRESHOLD_M:
            gain += v - baseline
            baseline = v
        elif baseline - v >= ELEVATION_NOISE_THRESHOLD_M:
            loss += baseline - v
            baseline = v
    return round(gain), round(loss)


def _perpendicular_distance(point: tuple[float, float], start: tuple[float, float], end: tuple[float, float]) -> float:
    (x, y), (x1, y1), (x2, y2) = point, start, end
    if (x1, y1) == (x2, y2):
        return math.hypot(x - x1, y - y1)
    t = ((x - x1) * (x2 - x1) + (y - y1) * (y2 - y1)) / ((x2 - x1) ** 2 + (y2 - y1) ** 2)
    t = max(0.0, min(1.0, t))
    proj_x, proj_y = x1 + t * (x2 - x1), y1 + t * (y2 - y1)
    return math.hypot(x - proj_x, y - proj_y)


def _douglas_peucker(coords: list[tuple[float, float]], epsilon: float) -> list[tuple[float, float]]:
    if len(coords) < 3:
        return coords
    start, end = coords[0], coords[-1]
    max_dist, index = 0.0, 0
    for i in range(1, len(coords) - 1):
        d = _perpendicular_distance(coords[i], start, end)
        if d > max_dist:
            index, max_dist = i, d
    if max_dist > epsilon:
        left = _douglas_peucker(coords[: index + 1], epsilon)
        right = _douglas_peucker(coords[index:], epsilon)
        return left[:-1] + right
    return [start, end]


def _simplify_route(points: list[TrackPoint], max_points: int = ROUTE_SIMPLIFY_MAX_POINTS) -> list[list[float]]:
    coords = [(p.lat, p.lng) for p in points]
    if len(coords) <= max_points:
        simplified = coords
    else:
        epsilon = 0.00001
        simplified = coords
        for _ in range(30):
            simplified = _douglas_peucker(coords, epsilon)
            if len(simplified) <= max_points:
                break
            epsilon *= 1.5
    return [[round(lat, 6), round(lng, 6)] for lat, lng in simplified]


def _elevation_profile(
    points: list[TrackPoint], smoothed: list[float | None], num_points: int = ELEVATION_PROFILE_POINTS
) -> list[list[float]]:
    total_km = points[-1].distance_km
    if total_km <= 0:
        return []

    profile = []
    idx = 0
    for i in range(num_points + 1):
        target_km = total_km * i / num_points
        while idx < len(points) - 2 and points[idx + 1].distance_km < target_km:
            idx += 1
        p0, p1 = points[idx], points[idx + 1] if idx + 1 < len(points) else points[idx]
        e0 = smoothed[idx] if smoothed[idx] is not None else 0.0
        e1 = smoothed[idx + 1] if idx + 1 < len(smoothed) and smoothed[idx + 1] is not None else e0
        t = 0.0
        if p1.distance_km > p0.distance_km:
            t = max(0.0, min(1.0, (target_km - p0.distance_km) / (p1.distance_km - p0.distance_km)))
        profile.append([round(target_km, 3), round(e0 + (e1 - e0) * t, 1)])
    return profile


def _is_loop(points: list[TrackPoint]) -> bool:
    start, end = points[0], points[-1]
    return _haversine_km(start.lat, start.lng, end.lat, end.lng) * 1000.0 <= LOOP_THRESHOLD_M


def process_track_points(points: list[TrackPoint]) -> dict[str, Any]:
    """Turns a parsed/extracted track into every derived Course field. Sections and climbs
    aren't among them - they're calculated on read from elevation_series with the athlete's
    current settings (see courses/sections.py)."""
    smoothed = _smoothed_elevations(points)
    quality, series = build_elevation_series([p.distance_km for p in points], [p.elevation for p in points])
    gain_m, loss_m = _gain_loss(smoothed)
    elevations = [v for v in smoothed if v is not None]
    return {
        "start_lat": points[0].lat,
        "start_lng": points[0].lng,
        "distance_km": round(points[-1].distance_km, 3),
        "elevation_gain_m": gain_m,
        "elevation_loss_m": loss_m,
        "elevation_max_m": round(max(elevations)) if elevations else None,
        "elevation_min_m": round(min(elevations)) if elevations else None,
        "is_loop": _is_loop(points),
        "route_polyline": _simplify_route(points),
        "elevation_profile": _elevation_profile(points, smoothed),
        "elevation_quality": quality,
        "elevation_series": series,
    }
