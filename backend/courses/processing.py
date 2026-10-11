"""GPX/track processing for Course: distance, elevation smoothing, climb detection,
route simplification and elevation-profile resampling. Shared by a GPX upload and a
from-activity course - both end up as a list of TrackPoint before process_track_points
takes over, so the two sources produce identical Course fields for the same geometry.

Java (io.jenetics:jpx) must apply the same smoothing/gain/climb rules - see
backend/courses/fixtures/ and the parity test planned for backend_java/.
"""

import math
from dataclasses import dataclass
from typing import Any

import gpxpy

from uploads.parsers.gpx import _haversine_km

MAX_GPX_BYTES = 10 * 1024 * 1024
MIN_TRACK_POINTS = 50

ELEVATION_SMOOTH_WINDOW_M = 50.0
ELEVATION_NOISE_THRESHOLD_M = 2.0

CLIMB_MIN_LENGTH_M = 400.0
CLIMB_MIN_GRADE_PCT = 1.5
CLIMB_MIN_GAIN_M = 12.0
CLIMB_MERGE_GAP_M = 200.0

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


def _pivot_indices(smoothed: list[float | None]) -> list[int]:
    """Indices of local min/max in the smoothed series (a zigzag filter using the
    same 2m noise threshold as _gain_loss), alternating starting with the first
    point. Feeds climb detection below."""
    idxs = [i for i, v in enumerate(smoothed) if v is not None]
    if len(idxs) < 2:
        return idxs

    pivots = [idxs[0]]
    direction = 0  # 0 undetermined, 1 trending up, -1 trending down
    extreme_idx = idxs[0]
    for i in idxs[1:]:
        v = smoothed[i]
        extreme_v = smoothed[extreme_idx]
        if direction >= 0 and v >= extreme_v:
            extreme_idx, direction = i, 1
        elif direction <= 0 and v <= extreme_v:
            extreme_idx, direction = i, -1
        elif (direction == 1 and extreme_v - v >= ELEVATION_NOISE_THRESHOLD_M) or (
            direction == -1 and v - extreme_v >= ELEVATION_NOISE_THRESHOLD_M
        ):
            pivots.append(extreme_idx)
            direction, extreme_idx = -direction, i
    if pivots[-1] != extreme_idx:
        pivots.append(extreme_idx)
    return pivots


def _detect_climbs(points: list[TrackPoint], smoothed: list[float | None]) -> list[dict[str, Any]]:
    pivots = _pivot_indices(smoothed)
    if len(pivots) < 2:
        return []

    legs = [
        {"start_idx": a, "end_idx": b, "uphill": smoothed[b] > smoothed[a]}
        for a, b in zip(pivots[:-1], pivots[1:], strict=True)
    ]

    merged: list[dict[str, Any]] = []
    i = 0
    while i < len(legs):
        leg = legs[i]
        if not leg["uphill"]:
            i += 1
            continue
        start_idx, end_idx = leg["start_idx"], leg["end_idx"]
        gain_m = smoothed[end_idx] - smoothed[start_idx]
        j = i + 1
        # Fold in a short descent (< 200m) followed by another climb leg, so a
        # minor dip doesn't split one climb into two.
        while j + 1 < len(legs) and not legs[j]["uphill"]:
            dip = legs[j]
            dip_km = points[dip["end_idx"]].distance_km - points[dip["start_idx"]].distance_km
            if dip_km * 1000.0 >= CLIMB_MERGE_GAP_M:
                break
            next_up = legs[j + 1]
            end_idx = next_up["end_idx"]
            gain_m += smoothed[next_up["end_idx"]] - smoothed[next_up["start_idx"]]
            j += 2
        merged.append({"start_idx": start_idx, "end_idx": end_idx, "gain_m": gain_m})
        i = j

    climbs = []
    for m in merged:
        start_km, end_km = points[m["start_idx"]].distance_km, points[m["end_idx"]].distance_km
        length_m = (end_km - start_km) * 1000.0
        gain_m = m["gain_m"]
        if length_m < CLIMB_MIN_LENGTH_M or gain_m < CLIMB_MIN_GAIN_M:
            continue
        grade_pct = (gain_m / length_m) * 100.0 if length_m else 0.0
        if grade_pct < CLIMB_MIN_GRADE_PCT:
            continue
        climbs.append(
            {
                "start_km": round(start_km, 2),
                "end_km": round(end_km, 2),
                "gain_m": round(gain_m),
                "avg_grade_pct": round(grade_pct, 1),
            }
        )
    return climbs


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
    """Turns a parsed/extracted track into every derived Course field."""
    smoothed = _smoothed_elevations(points)
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
        "climbs": _detect_climbs(points, smoothed),
    }
