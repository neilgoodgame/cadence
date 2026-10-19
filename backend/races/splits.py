"""Race splits - laps, 1km/5km fixed-distance splits, or (elevation) one split per section of
the race's linked course - from a race's linked activity. README §3.2/§4.2.6.
"""

from typing import Any

from activities.models import Activity
from activities.splits import (
    avg_grade_pct as _avg_grade_pct,
)
from activities.splits import (
    gap_pace_s_per_km as _gap_pace_s_per_km,
)
from activities.splits import (
    get_custom_activity_splits,
)
from activities.splits import (
    pace_s_per_km as _pace_s_per_km,
)
from courses.elevation import ensure_elevation_series, sections_for

from .models import Race


def _delta_vs_ref(pace_s_per_km: float | None, ref_pace_s_per_km: float | None) -> float | None:
    if pace_s_per_km is None or ref_pace_s_per_km is None:
        return None
    return round(pace_s_per_km - ref_pace_s_per_km, 1)


def _reference_pace(activity: Activity, race: Race) -> tuple[str, float | None]:
    if race.goal_time and race.distance_km:
        return "goal", race.goal_time.total_seconds() / race.distance_km
    if activity.distance_km:
        return "average", activity.moving_time / activity.distance_km
    return "average", None


def _reference_power(activity: Activity, race: Race) -> tuple[str, int | None]:
    """Mirrors _reference_pace, but for Stryd-style power - preferred over GAP as the splits
    delta reference whenever the activity has power data, since power already accounts for
    grade mechanically rather than estimating it from a cost model."""
    if race.target_power:
        return "target", race.target_power
    return "average", activity.avg_power


def _delta_power_w(avg_power: int | None, ref_power_w: int | None) -> int | None:
    if avg_power is None or ref_power_w is None:
        return None
    return round(avg_power - ref_power_w)


def _halves(splits: list[dict[str, Any]]) -> dict[str, int] | None:
    if not splits:
        return None
    mid = len(splits) // 2
    first_s = sum(s["time_s"] for s in splits[:mid] if s["time_s"])
    second_s = sum(s["time_s"] for s in splits[mid:] if s["time_s"])
    return {"first_s": first_s, "second_s": second_s}


def _lap_splits(
    activity: Activity, ref: str, ref_pace: float | None, ref_power: str, ref_power_w: int | None
) -> dict[str, Any]:
    laps = list(activity.laps.order_by("index"))
    # Laps don't store their own altitude, so this is the one split mode that needs a records
    # query just for grade - distance_km/altitude only, not the full row the other two modes
    # pull for pace/HR/power (laps already have their own duration/avg_hr/avg_power).
    altitude_rows = list(activity.records.order_by("t").values_list("distance_km", "altitude"))
    splits = []
    cumulative_km = 0.0
    altitude_cursor = 0
    for lap in laps:
        pace = _pace_s_per_km(lap.duration, lap.distance_km)
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
        avg_grade_pct = _avg_grade_pct(start_altitude, end_altitude, lap.distance_km)
        gap = _gap_pace_s_per_km(pace, avg_grade_pct)
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
                "delta_vs_ref_s_per_km": _delta_vs_ref(gap if gap is not None else pace, ref_pace),
                "delta_power_w": _delta_power_w(lap.avg_power, ref_power_w),
                "section_type": None,
                "gain_m": None,
                "loss_m": None,
                "avg_grade_pct": avg_grade_pct,
            }
        )
        cumulative_km = end_km
    return {
        "has_laps": len(laps) > 1,
        "ref": ref,
        "ref_pace_s_per_km": ref_pace,
        "ref_power": ref_power,
        "ref_power_w": ref_power_w,
        "halves": _halves(splits),
        "splits": splits,
    }


def _fixed_distance_splits(
    activity: Activity, step_km: float, ref: str, ref_pace: float | None, ref_power: str, ref_power_w: int | None
) -> dict[str, Any]:
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
            pace = _pace_s_per_km(time_s, dist_km)
            avg_grade_pct = _avg_grade_pct(start_altitude, end_altitude, dist_km)
            gap = _gap_pace_s_per_km(pace, avg_grade_pct)
            avg_power = round(sum(power_samples) / len(power_samples)) if power_samples else None
            splits.append(
                {
                    "label": "Finish" if i == n_splits - 1 and dist_km < step_km - 0.01 else None,
                    "start_km": round(start_km, 2),
                    "end_km": round(end_km, 2),
                    "time_s": time_s,
                    "pace_s_per_km": round(pace, 1) if pace else None,
                    "gap_pace_s_per_km": gap,
                    "avg_hr": round(sum(hr_samples) / len(hr_samples)) if hr_samples else None,
                    "avg_power": avg_power,
                    "delta_vs_ref_s_per_km": _delta_vs_ref(gap if gap is not None else pace, ref_pace),
                    "delta_power_w": _delta_power_w(avg_power, ref_power_w),
                    "section_type": None,
                    "gain_m": None,
                    "loss_m": None,
                    "avg_grade_pct": avg_grade_pct,
                }
            )

    return {
        "has_laps": False,
        "ref": ref,
        "ref_pace_s_per_km": ref_pace,
        "ref_power": ref_power,
        "ref_power_w": ref_power_w,
        "halves": _halves(splits),
        "splits": splits,
    }


def _elevation_splits(
    race: Race, activity: Activity, ref: str, ref_pace: float | None, ref_power: str, ref_power_w: int | None
) -> dict[str, Any]:
    empty = {
        "has_laps": False,
        "ref": ref,
        "ref_pace_s_per_km": ref_pace,
        "ref_power": ref_power,
        "ref_power_w": ref_power_w,
        "halves": None,
        "splits": [],
    }

    course = race.course
    if course is None:
        return empty
    ensure_elevation_series(course)
    sections = sections_for(course)
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
        pace = _pace_s_per_km(time_s, dist_km)
        gap = _gap_pace_s_per_km(pace, section["avg_grade_pct"])
        avg_power = round(sum(power_samples) / len(power_samples)) if power_samples else None
        splits.append(
            {
                "label": None,
                "start_km": round(start_km, 2),
                "end_km": round(end_km, 2),
                "time_s": time_s,
                "pace_s_per_km": round(pace, 1) if pace else None,
                "gap_pace_s_per_km": gap,
                "avg_hr": round(sum(hr_samples) / len(hr_samples)) if hr_samples else None,
                "avg_power": avg_power,
                "delta_vs_ref_s_per_km": _delta_vs_ref(gap if gap is not None else pace, ref_pace),
                "delta_power_w": _delta_power_w(avg_power, ref_power_w),
                "section_type": section["type"],
                "gain_m": section["gain_m"],
                "loss_m": section["loss_m"],
                "avg_grade_pct": section["avg_grade_pct"],
            }
        )

    return {
        "has_laps": False,
        "ref": ref,
        "ref_pace_s_per_km": ref_pace,
        "ref_power": ref_power,
        "ref_power_w": ref_power_w,
        "halves": _halves(splits),
        "splits": splits,
    }


def get_splits(race: Race, mode: str) -> dict[str, Any]:
    activity = race.activity
    if activity is None:
        return {
            "has_laps": False,
            "ref": "average",
            "ref_pace_s_per_km": None,
            "ref_power": "average",
            "ref_power_w": None,
            "halves": None,
            "splits": [],
        }

    ref, ref_pace = _reference_pace(activity, race)
    ref_power, ref_power_w = _reference_power(activity, race)

    if mode == "laps":
        return _lap_splits(activity, ref, ref_pace, ref_power, ref_power_w)
    if mode == "1":
        return _fixed_distance_splits(activity, 1.0, ref, ref_pace, ref_power, ref_power_w)
    if mode == "5":
        return _fixed_distance_splits(activity, 5.0, ref, ref_pace, ref_power, ref_power_w)
    if mode == "elevation":
        return _elevation_splits(race, activity, ref, ref_pace, ref_power, ref_power_w)
    raise ValueError(f"Unknown splits mode '{mode}'")


def get_custom_splits(race: Race, steps: list[dict[str, Any]]) -> dict[str, Any]:
    """Custom splits (see activities.splits.get_custom_activity_splits) against `race`'s own
    goal/target reference - layers ref/delta on top of the same step-walk every other mode
    shares, same as _lap_splits/_fixed_distance_splits/_elevation_splits do."""
    activity = race.activity
    if activity is None:
        return {
            "has_laps": False,
            "ref": "average",
            "ref_pace_s_per_km": None,
            "ref_power": "average",
            "ref_power_w": None,
            "halves": None,
            "splits": [],
        }

    ref, ref_pace = _reference_pace(activity, race)
    ref_power, ref_power_w = _reference_power(activity, race)

    base = get_custom_activity_splits(activity, steps)  # raises ValueError on malformed steps
    splits = []
    for split in base["splits"]:
        pace_for_delta = (
            split["gap_pace_s_per_km"] if split["gap_pace_s_per_km"] is not None else split["pace_s_per_km"]
        )
        splits.append(
            {
                **split,
                "delta_vs_ref_s_per_km": _delta_vs_ref(pace_for_delta, ref_pace),
                "delta_power_w": _delta_power_w(split["avg_power"], ref_power_w),
            }
        )

    return {
        "has_laps": False,
        "ref": ref,
        "ref_pace_s_per_km": ref_pace,
        "ref_power": ref_power,
        "ref_power_w": ref_power_w,
        "halves": base["halves"],
        "splits": splits,
    }
