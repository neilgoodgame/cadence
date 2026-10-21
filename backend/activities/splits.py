"""Activity splits - laps, 1km/5km fixed-distance splits, or (elevation) one split per section
of the activity's own recorded elevation. No goal/target reference or delta here (a plain
activity has no race to compare against) - races/splits.py layers those on top, reusing the pace/
grade math below (pace_s_per_km/gap_pace_s_per_km/avg_grade_pct) so the two can't drift apart.
"""

from typing import Any

from rest_framework.exceptions import ValidationError

from courses.sections import ELEVATION_QUALITY_OK, sections_from_series

from .elevation import ensure_elevation
from .models import Activity
from .record_walk import Segment, StepSpec, walk_steps

SPLITS_MODES = ("laps", "1", "5", "elevation")


def pace_s_per_km(time_s: float, distance_km: float) -> float | None:
    return time_s / distance_km if distance_km else None


# Minetti et al. 2002 ("Energy cost of walking and running at extreme uphill and downhill
# slopes") metabolic cost of running on a grade, J/kg/m - the standard model behind GAP
# (grade-adjusted pace). Valid for gradients within +/-45%; clamped there since a whole
# split's *average* grade shouldn't realistically exceed that, and the polynomial isn't
# meant to extrapolate past it.
_GAP_FLAT_COST = 3.6
_GAP_MAX_GRADE_FRACTION = 0.45


def _metabolic_cost(grade_fraction: float) -> float:
    g = grade_fraction
    return 155.4 * g**5 - 30.4 * g**4 - 43.3 * g**3 + 46.3 * g**2 + 19.5 * g + _GAP_FLAT_COST


def gap_pace_s_per_km(pace: float | None, avg_grade_pct: float | None) -> float | None:
    """Grade-adjusted pace - the flat-equivalent pace for the same effort, from the Minetti
    cost-of-running model: GAP = actual pace / (cost at this grade / cost on flat ground)."""
    if pace is None or avg_grade_pct is None:
        return None
    grade_fraction = max(-_GAP_MAX_GRADE_FRACTION, min(_GAP_MAX_GRADE_FRACTION, avg_grade_pct / 100))
    gap_factor = _metabolic_cost(grade_fraction) / _GAP_FLAT_COST
    return round(pace / gap_factor, 1)


def avg_grade_pct(start_altitude: float | None, end_altitude: float | None, dist_km: float) -> float | None:
    """Net grade over a split, from its first/last recorded altitude - same formula as a course
    section's own avg_grade_pct (courses.sections._grade): end-minus-start elevation over
    distance, not an average of per-sample grades. None if either end has no altitude."""
    if start_altitude is None or end_altitude is None or not dist_km:
        return None
    return round((end_altitude - start_altitude) / (dist_km * 1000) * 100, 1)


def _halves(splits: list[dict[str, Any]]) -> dict[str, int] | None:
    """Time either side of the halfway point of the activity's own total distance (the last
    split's end_km) - not a naive bisection of the splits list by count, which skews badly
    whenever the splits aren't all the same length (e.g. fixed-distance mode's shorter final
    split, or laps/elevation splits of varying length): a 9-split 5km-mode marathon (eight
    5km splits plus a 2.2km tail) would otherwise compare 20km of running against 22.2km."""
    if not splits:
        return None
    total_km = splits[-1]["end_km"]
    if not total_km:
        return None
    half_km = total_km / 2
    first_s = second_s = 0.0
    for s in splits:
        if not s["time_s"]:
            continue
        start_km, end_km = s["start_km"], s["end_km"]
        dist_km = end_km - start_km
        if dist_km <= 0:
            continue
        if end_km <= half_km:
            first_s += s["time_s"]
        elif start_km >= half_km:
            second_s += s["time_s"]
        else:
            frac_first = (half_km - start_km) / dist_km
            first_s += s["time_s"] * frac_first
            second_s += s["time_s"] * (1 - frac_first)
    return {"first_s": round(first_s), "second_s": round(second_s)}


def sections_for(activity: Activity) -> list[dict]:
    """Sections calculated now from the activity's own stored elevation series, with the
    owning athlete's section settings - never stored. Mirrors courses.elevation.sections_for."""
    elevation = ensure_elevation(activity)
    if elevation.elevation_quality != ELEVATION_QUALITY_OK:
        return []
    return sections_from_series(elevation.elevation_series, activity.athlete.section_settings())


def _lap_splits(activity: Activity) -> dict[str, Any]:
    laps = list(activity.laps.order_by("index"))
    # Laps don't store their own altitude, so this is the one split mode that needs a records
    # query just for grade - distance_km/altitude only, not the full row the other two modes
    # pull for pace/HR/power (laps already have their own duration/avg_hr/avg_power).
    altitude_rows = list(activity.records.order_by("t").values_list("distance_km", "altitude"))
    splits = []
    cumulative_km = 0.0
    altitude_cursor = 0
    for lap in laps:
        pace = pace_s_per_km(lap.duration, lap.distance_km)
        start_km = cumulative_km
        end_km = cumulative_km + lap.distance_km
        while altitude_cursor < len(altitude_rows) and (altitude_rows[altitude_cursor][0] or 0.0) < start_km:
            altitude_cursor += 1
        start_altitude = end_altitude = None
        scan = altitude_cursor
        while scan < len(altitude_rows) and (altitude_rows[scan][0] or 0.0) <= end_km + 1e-6:
            altitude = altitude_rows[scan][1]
            if altitude is not None:
                if start_altitude is None:
                    start_altitude = altitude
                end_altitude = altitude
            scan += 1
        grade = avg_grade_pct(start_altitude, end_altitude, lap.distance_km)
        gap = gap_pace_s_per_km(pace, grade)
        splits.append(
            {
                "label": f"Lap {lap.index}",
                "start_km": round(start_km, 2),
                "end_km": round(end_km, 2),
                "time_s": lap.duration,
                "pace_s_per_km": round(pace, 1) if pace else None,
                "gap_pace_s_per_km": gap,
                "avg_hr": lap.avg_hr,
                "avg_power": lap.avg_power,
                "section_type": None,
                "gain_m": None,
                "loss_m": None,
                "avg_grade_pct": grade,
            }
        )
        cumulative_km = end_km
    return {"has_laps": len(laps) > 1, "halves": _halves(splits), "splits": splits}


def _fixed_distance_splits(activity: Activity, step_km: float) -> dict[str, Any]:
    rows = list(activity.records.order_by("t").values_list("t", "distance_km", "heartrate", "power", "altitude"))
    splits: list[dict[str, Any]] = []
    total_km = rows[-1][1] or 0.0 if rows else 0.0

    if rows and total_km > 0:
        n_splits = int(total_km // step_km) + (1 if total_km % step_km > 0.01 else 0)
        cursor = 0
        for i in range(n_splits):
            start_km = i * step_km
            end_km = min(start_km + step_km, total_km)
            while cursor < len(rows) and (rows[cursor][1] or 0.0) < start_km:
                cursor += 1
            start_t = end_t = None
            hr_samples = []
            power_samples = []
            start_altitude = end_altitude = None
            scan = cursor
            while scan < len(rows) and (rows[scan][1] or 0.0) <= end_km + 1e-6:
                if start_t is None:
                    start_t = rows[scan][0]
                end_t = rows[scan][0]
                if rows[scan][2] is not None:
                    hr_samples.append(rows[scan][2])
                if rows[scan][3] is not None:
                    power_samples.append(rows[scan][3])
                if rows[scan][4] is not None:
                    if start_altitude is None:
                        start_altitude = rows[scan][4]
                    end_altitude = rows[scan][4]
                scan += 1
            if start_t is None or end_t is None:
                continue
            time_s = end_t - start_t
            dist_km = end_km - start_km
            pace = pace_s_per_km(time_s, dist_km)
            grade = avg_grade_pct(start_altitude, end_altitude, dist_km)
            gap = gap_pace_s_per_km(pace, grade)
            splits.append(
                {
                    "label": "Finish" if i == n_splits - 1 and dist_km < step_km - 0.01 else None,
                    "start_km": round(start_km, 2),
                    "end_km": round(end_km, 2),
                    "time_s": time_s,
                    "pace_s_per_km": round(pace, 1) if pace else None,
                    "gap_pace_s_per_km": gap,
                    "avg_hr": round(sum(hr_samples) / len(hr_samples)) if hr_samples else None,
                    "avg_power": round(sum(power_samples) / len(power_samples)) if power_samples else None,
                    "section_type": None,
                    "gain_m": None,
                    "loss_m": None,
                    "avg_grade_pct": grade,
                }
            )

    return {"has_laps": False, "halves": _halves(splits), "splits": splits}


def _elevation_splits(activity: Activity) -> dict[str, Any]:
    empty = {"has_laps": False, "halves": None, "splits": []}

    sections = sections_for(activity)
    if not sections:
        return empty

    rows = list(activity.records.order_by("t").values_list("t", "distance_km", "heartrate", "power"))
    total_km = rows[-1][1] or 0.0 if rows else 0.0
    if not rows or total_km <= 0:
        return empty

    splits: list[dict[str, Any]] = []
    cursor = 0
    for section in sections:
        start_km = section["start_km"]
        if start_km >= total_km:
            break
        end_km = min(section["end_km"], total_km)
        while cursor < len(rows) and (rows[cursor][1] or 0.0) < start_km:
            cursor += 1
        start_t = end_t = None
        hr_samples = []
        power_samples = []
        scan = cursor
        while scan < len(rows) and (rows[scan][1] or 0.0) <= end_km + 1e-6:
            if start_t is None:
                start_t = rows[scan][0]
            end_t = rows[scan][0]
            if rows[scan][2] is not None:
                hr_samples.append(rows[scan][2])
            if rows[scan][3] is not None:
                power_samples.append(rows[scan][3])
            scan += 1
        if start_t is None or end_t is None:
            continue
        time_s = end_t - start_t
        dist_km = end_km - start_km
        pace = pace_s_per_km(time_s, dist_km)
        gap = gap_pace_s_per_km(pace, section["avg_grade_pct"])
        splits.append(
            {
                "label": None,
                "start_km": round(start_km, 2),
                "end_km": round(end_km, 2),
                "time_s": time_s,
                "pace_s_per_km": round(pace, 1) if pace else None,
                "gap_pace_s_per_km": gap,
                "avg_hr": round(sum(hr_samples) / len(hr_samples)) if hr_samples else None,
                "avg_power": round(sum(power_samples) / len(power_samples)) if power_samples else None,
                "section_type": section["type"],
                "gain_m": section["gain_m"],
                "loss_m": section["loss_m"],
                "avg_grade_pct": section["avg_grade_pct"],
            }
        )

    return {"has_laps": False, "halves": _halves(splits), "splits": splits}


def get_activity_splits(activity: Activity, mode: str) -> dict[str, Any]:
    if mode == "laps":
        return _lap_splits(activity)
    if mode == "1":
        return _fixed_distance_splits(activity, 1.0)
    if mode == "5":
        return _fixed_distance_splits(activity, 5.0)
    if mode == "elevation":
        return _elevation_splits(activity)
    raise ValueError(f"Unknown splits mode '{mode}'")


def validate_custom_steps(steps: list[dict[str, Any]]) -> None:
    """Raises a DRF ValidationError (not a plain ValueError - so callers can let it propagate
    straight to the client without an except-and-restringify step, which CodeQL flags as
    information exposure even though every message here is a static literal we wrote ourselves)
    if `steps` isn't a non-empty list of well-formed step dicts. Each step is consumed
    sequentially from wherever the previous one left off - see record_walk.walk_steps - so
    unlike an absolute start_km/end_km boundary list, there's no separate "ordered and
    non-overlapping" check: that's true by construction."""
    if not isinstance(steps, list) or not steps:
        raise ValidationError({"steps": "steps must be a non-empty list."})
    for i, step in enumerate(steps):
        if not isinstance(step, dict):
            raise ValidationError({"steps": f"steps[{i}] must be an object."})
        end_type = step.get("end_type")
        if end_type not in ("time", "distance"):
            raise ValidationError({"steps": f"steps[{i}].end_type must be 'time' or 'distance'."})
        if end_type == "time":
            duration_s = step.get("duration_s")
            if not isinstance(duration_s, int | float) or duration_s <= 0:
                raise ValidationError({"steps": f"steps[{i}].duration_s must be a positive number of seconds."})
        else:
            distance_km = step.get("distance_km")
            if not isinstance(distance_km, int | float) or distance_km <= 0:
                raise ValidationError({"steps": f"steps[{i}].distance_km must be a positive number of kilometres."})


def _split_from_segment(label: str | None, segment: Segment) -> dict[str, Any]:
    rows = segment["rows"]
    time_s = segment["time_s"]
    distance_km = segment["distance_km"]
    hr_samples = [r[2] for r in rows if r[2] is not None]
    power_samples = [r[3] for r in rows if r[3] is not None]
    altitudes = [r[4] for r in rows if r[4] is not None]
    pace = pace_s_per_km(time_s, distance_km)
    grade = avg_grade_pct(altitudes[0] if altitudes else None, altitudes[-1] if altitudes else None, distance_km)
    gap = gap_pace_s_per_km(pace, grade)
    start_km = rows[0][1] or 0.0
    return {
        "label": label,
        "start_km": round(start_km, 2),
        "end_km": round(start_km + distance_km, 2),
        "time_s": time_s,
        "pace_s_per_km": round(pace, 1) if pace else None,
        "gap_pace_s_per_km": gap,
        "avg_hr": round(sum(hr_samples) / len(hr_samples)) if hr_samples else None,
        "avg_power": round(sum(power_samples) / len(power_samples)) if power_samples else None,
        "section_type": None,
        "gain_m": None,
        "loss_m": None,
        "avg_grade_pct": grade,
    }


def get_custom_activity_splits(activity: Activity, steps: list[dict[str, Any]]) -> dict[str, Any]:
    """Splits built from an ad-hoc, client-supplied list of steps (each either `{"end_type":
    "time", "duration_s": N}` or `{"end_type": "distance", "distance_km": N}`, plus an optional
    "label"), consumed sequentially from the start of the recording - the same step shape and
    walk `derive_laps_from_workout` uses for a matched workout's own steps, just not persisted
    and not tied to a Workout. Raises ValidationError (via validate_custom_steps) on malformed
    input.
    """
    validate_custom_steps(steps)
    rows = list(activity.records.order_by("t").values_list("t", "distance_km", "heartrate", "power", "altitude"))
    step_specs: list[StepSpec] = [
        {
            "end_type": step["end_type"],
            "duration": step.get("duration_s"),
            "distance": (step.get("distance_km") or 0) * 1000,
        }
        for step in steps
    ]
    segments, trailing = walk_steps(rows, step_specs)

    splits = [_split_from_segment(step.get("label"), segment) for step, segment in zip(steps, segments, strict=False)]
    if trailing is not None:
        splits.append(_split_from_segment(None, trailing))

    return {"has_laps": False, "halves": _halves(splits), "splits": splits}
