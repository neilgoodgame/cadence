"""Race splits - laps, 1km and 5km fixed-distance splits from a race's linked
activity. README §3.2/§4.2.6.
"""

from typing import Any

from activities.models import Activity

from .models import Race


def _pace_s_per_km(time_s: float, distance_km: float) -> float | None:
    return time_s / distance_km if distance_km else None


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


def _halves(splits: list[dict[str, Any]]) -> dict[str, int] | None:
    if not splits:
        return None
    mid = len(splits) // 2
    first_s = sum(s["time_s"] for s in splits[:mid] if s["time_s"])
    second_s = sum(s["time_s"] for s in splits[mid:] if s["time_s"])
    return {"first_s": first_s, "second_s": second_s}


def _lap_splits(activity: Activity, ref: str, ref_pace: float | None) -> dict[str, Any]:
    laps = list(activity.laps.order_by("index"))
    splits = []
    cumulative_km = 0.0
    for lap in laps:
        pace = _pace_s_per_km(lap.duration, lap.distance_km)
        splits.append(
            {
                "label": f"Lap {lap.index}",
                "start_km": round(cumulative_km, 2),
                "end_km": round(cumulative_km + lap.distance_km, 2),
                "time_s": lap.duration,
                "pace_s_per_km": round(pace, 1) if pace else None,
                "avg_hr": lap.avg_hr,
                "delta_vs_ref_s_per_km": _delta_vs_ref(pace, ref_pace),
            }
        )
        cumulative_km += lap.distance_km
    return {
        "has_laps": len(laps) > 1,
        "ref": ref,
        "ref_pace_s_per_km": ref_pace,
        "halves": _halves(splits),
        "splits": splits,
    }


def _fixed_distance_splits(activity: Activity, step_km: float, ref: str, ref_pace: float | None) -> dict[str, Any]:
    rows = list(activity.records.order_by("t").values_list("t", "distance_km", "heartrate"))
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
            scan = cursor
            while scan < len(rows) and (rows[scan][1] or 0.0) <= end_km + 1e-6:
                if start_t is None:
                    start_t = rows[scan][0]
                end_t = rows[scan][0]
                if rows[scan][2] is not None:
                    hr_samples.append(rows[scan][2])
                scan += 1
            if start_t is None or end_t is None:
                continue
            time_s = end_t - start_t
            dist_km = end_km - start_km
            pace = _pace_s_per_km(time_s, dist_km)
            splits.append(
                {
                    "label": "Finish" if i == n_splits - 1 and dist_km < step_km - 0.01 else None,
                    "start_km": round(start_km, 2),
                    "end_km": round(end_km, 2),
                    "time_s": time_s,
                    "pace_s_per_km": round(pace, 1) if pace else None,
                    "avg_hr": round(sum(hr_samples) / len(hr_samples)) if hr_samples else None,
                    "delta_vs_ref_s_per_km": _delta_vs_ref(pace, ref_pace),
                }
            )

    return {
        "has_laps": False,
        "ref": ref,
        "ref_pace_s_per_km": ref_pace,
        "halves": _halves(splits),
        "splits": splits,
    }


def get_splits(race: Race, mode: str) -> dict[str, Any]:
    activity = race.activity
    if activity is None:
        return {"has_laps": False, "ref": "average", "ref_pace_s_per_km": None, "halves": None, "splits": []}

    ref, ref_pace = _reference_pace(activity, race)

    if mode == "laps":
        return _lap_splits(activity, ref, ref_pace)
    if mode == "1":
        return _fixed_distance_splits(activity, 1.0, ref, ref_pace)
    if mode == "5":
        return _fixed_distance_splits(activity, 5.0, ref, ref_pace)
    raise ValueError(f"Unknown splits mode '{mode}'")
