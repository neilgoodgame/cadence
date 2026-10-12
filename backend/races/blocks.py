"""Race training-block rules - README §3.2. Block range/truncation, phases, weekly
aggregates, race-day form, the comparison block, Riegel prediction, key-session
ranking and sweat rate. Backs GET /v1/races/{id}/block (views.py) and the
get_race_block MCP tool (mcp.py).

Two deliberate simplifications versus the design doc, called out here since a Java
port (session 2) needs to make the same calls:
- Key-session "quality" candidates check IF >= 0.85 for any sport, but the "20 min at
  goal pace" alternative is only evaluated for run races - there's no per-race goal
  power field to compare a ride against, and IF >= 0.85 already covers quality bike
  sessions. See _is_quality_activity.
- "Planned" key-session candidates use a duration/type heuristic
  (_is_long_or_quality_workout) since Workout has no explicit long/quality flag.
"""

from datetime import date, timedelta
from typing import Any

from django.utils import timezone

from accounts.models import User
from activities.models import Activity, BestEffort, Tag
from athletes.views import _DURABILITY_WIRE_SPORT, LOWER_IS_BETTER_KINDS, _durability_section, cap_per_window
from core.derived import ATL_DAYS, CTL_DAYS, _daily_tss
from scheduling.models import ScheduledWorkout

from .models import Race

RIEGEL_EXPONENT = 1.06
COMPARISON_DISTANCE_TOLERANCE = 0.02
LONG_WORKOUT_DURATION_S = 90 * 60
QUALITY_TYPE_KEYWORDS = ("threshold", "tempo", "vo2", "race pace", "marathon pace", "mp")
QUALITY_INTENSITY_FACTOR = 0.85
GOAL_PACE_WINDOW_S = 20 * 60


# --- week arithmetic -------------------------------------------------------------


def _week_start(d: date) -> date:
    return d - timedelta(days=d.weekday())


def _week_bounds(start_week: date, n_weeks: int) -> list[tuple[date, date]]:
    return [(start_week + timedelta(weeks=i), start_week + timedelta(weeks=i, days=6)) for i in range(n_weeks)]


def _tsb_band(tsb: float) -> str:
    if tsb > 25:
        return "very_fresh"
    if tsb >= 5:
        return "fresh"
    if tsb >= -10:
        return "neutral"
    return "fatigued"


# --- block range / phases ---------------------------------------------------------


def a_race_block_range(race: Race, block_weeks: int) -> dict[str, Any]:
    race_week = _week_start(race.date)
    ideal_start = race_week - timedelta(weeks=block_weeks - 1)
    prev_a = Race.objects.filter(athlete_id=race.athlete_id, priority="A", date__lt=race.date).order_by("-date").first()
    if prev_a:
        start_week = max(ideal_start, _week_start(prev_a.date) + timedelta(weeks=1))
    else:
        start_week = ideal_start
    weeks = (race_week - start_week).days // 7 + 1
    truncated = weeks < block_weeks
    return {
        "start_week": start_week,
        "end_week": race_week,
        "weeks": weeks,
        "truncated_by_race_id": prev_a.id if truncated and prev_a else None,
    }


def find_parent_a_race(race: Race, block_weeks: int) -> Race | None:
    b_week = _week_start(race.date)
    for a in Race.objects.filter(athlete_id=race.athlete_id, priority="A", date__gte=race.date).order_by("date"):
        block = a_race_block_range(a, block_weeks)
        if block["start_week"] <= b_week <= block["end_week"]:
            return a
    return None


def phases(n_weeks: int) -> list[dict[str, Any]]:
    """1-based week-of-block indices into the block's own `weeks` list, not calendar dates."""
    base = round(0.375 * n_weeks)
    build = round(0.375 * n_weeks)
    peak = max(1, round(0.125 * n_weeks))
    taper = n_weeks - base - build - peak
    result = []
    week_num = 1
    for name, length in (("Base", base), ("Build", build), ("Peak", peak), ("Taper", taper)):
        if length <= 0:
            continue
        result.append({"name": name, "start_week": week_num, "end_week": week_num + length - 1})
        week_num += length
    return result


# --- fitness series, including a forward planned-TSS projection -------------------


def _planned_daily_tss(athlete_id: str, start: date, end: date) -> dict[date, int]:
    if start > end:
        return {}
    rows = ScheduledWorkout.objects.filter(athlete_id=athlete_id, date__range=(start, end)).select_related("workout")
    totals: dict[date, int] = {}
    for sw in rows:
        totals[sw.date] = totals.get(sw.date, 0) + (sw.workout.tss or 0)
    return totals


def fitness_series_with_plan(athlete_id: str, from_date: date, to_date: date, today: date) -> list[dict[str, Any]]:
    """Like core.derived.compute_fitness_series, but days after `today` use planned TSS
    from ScheduledWorkout instead of 0 - the training-block chart's projected/dashed CTL."""
    first_activity_date = (
        Activity.objects.filter(athlete_id=athlete_id)
        .order_by("start_date")
        .values_list("start_date", flat=True)
        .first()
    )
    start = min(first_activity_date.date(), from_date) if first_activity_date else from_date
    if start > to_date:
        return []

    actual = _daily_tss(athlete_id, start, min(today, to_date))
    planned = _planned_daily_tss(athlete_id, max(start, today + timedelta(days=1)), to_date)

    ctl = atl = 0.0
    series = []
    day = start
    while day <= to_date:
        tss = actual.get(day, 0) if day <= today else planned.get(day, 0)
        ctl += (tss - ctl) / CTL_DAYS
        atl += (tss - atl) / ATL_DAYS
        if day >= from_date:
            series.append(
                {
                    "date": day,
                    "ctl": round(ctl, 1),
                    "atl": round(atl, 1),
                    "tsb": round(ctl - atl, 1),
                    "planned": day > today,
                }
            )
        day += timedelta(days=1)
    return series


# --- weekly aggregates -------------------------------------------------------------


def _weekly_aggregate(athlete_id: str, week_start_d: date, week_end_d: date, today: date) -> dict[str, Any]:
    is_planned = week_start_d > today

    races_qs = list(Race.objects.filter(athlete_id=athlete_id, date__range=(week_start_d, week_end_d)))
    races_list = [
        {
            "id": r.id,
            "name": r.name,
            "priority": r.priority,
            "result_s": int(r.result_time.total_seconds()) if r.result_time else None,
        }
        for r in races_qs
    ]

    if not is_planned:
        activities = list(
            Activity.objects.filter(
                athlete_id=athlete_id,
                start_date__date__range=(week_start_d, week_end_d),
                parent_activity__isnull=True,
                primary_activity__isnull=True,
            )
        )
        race_activity_ids = {r.activity_id for r in races_qs if r.activity_id}

        tss = sum(a.tss for a in activities)
        tss_races = sum(a.tss for a in activities if a.id in race_activity_ids)

        runs = [a for a in activities if a.sport == "run"]
        run_km = round(sum(a.distance_km for a in runs), 2) if runs else 0.0
        run_moving_s = sum(a.moving_time for a in runs)
        run_count = len(runs)
        run_avg_pace_s_per_km = round(run_moving_s / run_km) if run_km > 0 else None
        non_race_runs = [a for a in runs if a.id not in race_activity_ids]
        run_long_km = round(max(a.distance_km for a in non_race_runs), 2) if non_race_runs else None

        rides = [a for a in activities if a.sport == "bike"]
        ride_km = round(sum(a.distance_km for a in rides), 2) if rides else 0.0
        ride_moving_s = sum(a.moving_time for a in rides)
        ride_count = len(rides)
        powered_rides = [a for a in rides if a.avg_power is not None]
        powered_moving_s = sum(a.moving_time for a in powered_rides)
        ride_avg_power_w = (
            round(sum(a.avg_power * a.moving_time for a in powered_rides) / powered_moving_s)
            if powered_moving_s > 0
            else None
        )
        non_race_rides = [a for a in rides if a.id not in race_activity_ids]
        ride_long_km = round(max(a.distance_km for a in non_race_rides), 2) if non_race_rides else None

        hours = round(sum(a.moving_time for a in activities) / 3600.0, 2)
        sessions = len(activities)
    else:
        scheduled = list(
            ScheduledWorkout.objects.filter(
                athlete_id=athlete_id, date__range=(week_start_d, week_end_d)
            ).select_related("workout")
        )
        if not scheduled:
            tss = tss_races = None
            run_moving_s = run_count = None
            ride_moving_s = ride_count = None
            hours = sessions = None
        else:
            tss = sum(sw.workout.tss for sw in scheduled)
            tss_races = 0
            runs = [sw for sw in scheduled if sw.workout.sport == "run"]
            rides = [sw for sw in scheduled if sw.workout.sport == "bike"]
            run_moving_s = sum(sw.workout.duration for sw in runs)
            run_count = len(runs)
            ride_moving_s = sum(sw.workout.duration for sw in rides)
            ride_count = len(rides)
            hours = round(sum(sw.workout.duration for sw in scheduled) / 3600.0, 2)
            sessions = len(scheduled)
        # No distance/power data on a planned-only week - ScheduledWorkout carries
        # duration/TSS, not distance or intensity.
        run_km = run_avg_pace_s_per_km = run_long_km = None
        ride_km = ride_avg_power_w = ride_long_km = None

    series = fitness_series_with_plan(athlete_id, week_end_d, week_end_d, today)
    ctl = series[0]["ctl"] if series else None
    atl = series[0]["atl"] if series else None

    return {
        "week_start": week_start_d.isoformat(),
        "tss": tss,
        "tss_races": tss_races,
        "run_km": run_km,
        "run_moving_s": run_moving_s,
        "run_count": run_count,
        "run_avg_pace_s_per_km": run_avg_pace_s_per_km,
        "run_long_km": run_long_km,
        "ride_km": ride_km,
        "ride_moving_s": ride_moving_s,
        "ride_count": ride_count,
        "ride_avg_power_w": ride_avg_power_w,
        "ride_long_km": ride_long_km,
        "hours": hours,
        "sessions": sessions,
        "ctl": ctl,
        "atl": atl,
        "planned": is_planned,
        "races": races_list,
    }


# --- race-day form ------------------------------------------------------------------


def race_day_form(race: Race) -> dict[str, Any]:
    today = timezone.now().date()
    race_week_start = _week_start(race.date)
    prev_week_end = race_week_start - timedelta(days=1)

    prev_series = fitness_series_with_plan(race.athlete_id, prev_week_end, prev_week_end, today)
    ctl = prev_series[0]["ctl"] if prev_series else 0.0
    atl_prev = prev_series[0]["atl"] if prev_series else 0.0

    race_week_end = race_week_start + timedelta(days=6)
    race_activity_ids = {
        r.activity_id
        for r in Race.objects.filter(athlete_id=race.athlete_id, date__range=(race_week_start, race_week_end))
        if r.activity_id
    }
    if race.date <= today:
        week_tss = sum(_daily_tss(race.athlete_id, race_week_start, race_week_end).values())
        race_tss = sum(a.tss for a in Activity.objects.filter(athlete_id=race.athlete_id, pk__in=race_activity_ids))
        week_tss_excl_race = week_tss - race_tss
        has_data = week_tss > 0
    else:
        week_tss_excl_race = sum(_planned_daily_tss(race.athlete_id, race_week_start, race_week_end).values())
        has_data = week_tss_excl_race > 0

    if not has_data and race.date > today:
        return {
            "ctl": round(ctl, 1),
            "atl": None,
            "tsb": None,
            "band": None,
            "projected": True,
            "insufficient_data": True,
        }

    atl = 0.5 * atl_prev + 0.5 * (week_tss_excl_race / 7)
    tsb = ctl - atl
    return {
        "ctl": round(ctl, 1),
        "atl": round(atl, 1),
        "tsb": round(tsb, 1),
        "band": _tsb_band(tsb),
        "projected": race.date > today,
        "insufficient_data": False,
    }


# --- comparison block ---------------------------------------------------------------


def find_comparison_race(race: Race) -> Race | None:
    if not race.distance_km:
        return None
    lo = race.distance_km * (1 - COMPARISON_DISTANCE_TOLERANCE)
    hi = race.distance_km * (1 + COMPARISON_DISTANCE_TOLERANCE)
    return (
        Race.objects.filter(
            athlete_id=race.athlete_id,
            priority="A",
            sport=race.sport,
            distance_km__range=(lo, hi),
            date__lt=race.date,
        )
        .order_by("-date")
        .first()
    )


def comparison_weeks(n_weeks: int, comparison_race: Race, today: date) -> list[dict[str, Any]]:
    """Aligned counting back from the comparison race's own day, so its last week lines
    up with this block's last (race) week regardless of a block-length mismatch."""
    comp_race_week = _week_start(comparison_race.date)
    result = []
    for i in range(n_weeks):
        offset_from_end = n_weeks - 1 - i
        ws = comp_race_week - timedelta(weeks=offset_from_end)
        we = ws + timedelta(days=6)
        result.append(_weekly_aggregate(comparison_race.athlete_id, ws, we, today))
    return result


# --- Riegel prediction ---------------------------------------------------------------


def riegel_prediction(a_race: Race, block_start_week: date, block_end_week: date) -> dict[str, Any] | None:
    if a_race.sport != "run" or not a_race.distance_km:
        return None
    candidates = Race.objects.filter(
        athlete_id=a_race.athlete_id,
        priority__in=["B", "C"],
        sport="run",
        date__range=(block_start_week, block_end_week + timedelta(days=6)),
        result_time__isnull=False,
        distance_km__isnull=False,
    ).exclude(pk=a_race.pk)

    best: tuple[float, str] | None = None
    for c in candidates:
        predicted_s = c.result_time.total_seconds() * (a_race.distance_km / c.distance_km) ** RIEGEL_EXPONENT
        if best is None or predicted_s < best[0]:
            best = (predicted_s, c.id)
    if best is None:
        return None
    return {"time_s": round(best[0]), "from_race_id": best[1]}


# --- key sessions ----------------------------------------------------------------------


def _goal_pace_s_per_km(race: Race) -> float | None:
    if race.goal_time and race.distance_km:
        return race.goal_time.total_seconds() / race.distance_km
    return None


def _best_pace_meets_goal(activity: Activity, goal_pace_s_per_km: float, window_s: int = GOAL_PACE_WINDOW_S) -> bool:
    """True if some >= window_s-second span covered >= window_s/goal_pace km (i.e. ran at
    or faster than goal pace for at least that long), via a two-pointer scan over the
    activity's recorded (t, distance_km) stream."""
    rows = list(activity.records.order_by("t").values_list("t", "distance_km"))
    if len(rows) < 2:
        return False
    left = 0
    for right in range(len(rows)):
        while rows[right][0] - rows[left][0] > window_s:
            left += 1
        span_s = rows[right][0] - rows[left][0]
        span_km = (rows[right][1] or 0) - (rows[left][1] or 0)
        if span_s < window_s or span_km <= 0:
            continue
        if span_s / span_km <= goal_pace_s_per_km:
            return True
    return False


def _is_quality_activity(activity: Activity, race: Race) -> bool:
    if (activity.intensity or 0) >= QUALITY_INTENSITY_FACTOR:
        return True
    if activity.sport == "run":
        goal_pace = _goal_pace_s_per_km(race)
        if goal_pace:
            return _best_pace_meets_goal(activity, goal_pace)
    return False


def _quality_label(activity: Activity) -> str:
    workout_type = (activity.workout.type if activity.workout_id else "").lower()
    if "mp" in workout_type or "marathon pace" in workout_type:
        return "MP"
    if "threshold" in workout_type:
        return "Threshold"
    if "tempo" in workout_type:
        return "Tempo"
    if "vo2" in workout_type:
        return "VO2"
    if "race pace" in workout_type:
        return "Race pace"
    return "Quality"


def _is_long_or_quality_workout(workout: Any) -> bool:
    if workout.duration >= LONG_WORKOUT_DURATION_S:
        return True
    workout_type = (workout.type or "").lower()
    return any(k in workout_type for k in QUALITY_TYPE_KEYWORDS)


def _top3_activity_ids(athlete_id: str, start: date, end: date) -> dict[str, date]:
    """Activity ids (-> the qualifying best effort's date) whose best effort ranks in the
    top 3 by value among ALL of the athlete's best efforts for that kind/window, where
    the effort's own date falls in [start, end]. Reuses cap_per_window directly rather
    than re-deriving the ranking rule ActivityBestEffortRanksView already encodes."""
    all_efforts = list(BestEffort.objects.filter(athlete_id=athlete_id).select_related("activity"))
    by_kind: dict[str, list[BestEffort]] = {}
    for e in all_efforts:
        by_kind.setdefault(e.kind, []).append(e)

    result: dict[str, date] = {}
    for kind, efforts in by_kind.items():
        for e in cap_per_window(efforts, lower_is_better=kind in LOWER_IS_BETTER_KINDS, top_n=3):
            if start <= e.date <= end:
                result[e.activity_id] = e.date
    return result


def _activity_candidate(activity: Activity, reason: str, label: str) -> dict[str, Any]:
    return {
        "date": activity.start_date.date(),
        "name": activity.name,
        "activity_id": activity.id,
        "scheduled_workout_id": None,
        "reason": reason,
        "label": label,
        "km": activity.distance_km,
        "tss": activity.tss,
    }


def key_sessions(race: Race, window_start: date, window_end: date) -> dict[str, Any]:
    athlete_id = race.athlete_id
    candidates: list[dict[str, Any]] = []
    seen_activity_ids: set[str] = set()

    races_in_window = Race.objects.filter(athlete_id=athlete_id, date__range=(window_start, window_end)).exclude(
        pk=race.pk
    )
    for r in races_in_window:
        candidates.append(
            {
                "date": r.date,
                "name": r.name,
                "activity_id": r.activity_id,
                "scheduled_workout_id": None,
                "reason": "race",
                "label": f"{r.priority} race",
                "km": r.distance_km,
                "tss": r.activity.tss if r.activity_id else None,
            }
        )
        if r.activity_id:
            seen_activity_ids.add(r.activity_id)

    base_qs = Activity.objects.filter(
        athlete_id=athlete_id,
        start_date__date__range=(window_start, window_end),
        parent_activity__isnull=True,
        primary_activity__isnull=True,
    ).exclude(pk__in=seen_activity_ids)

    tag = Tag.objects.filter(athlete_id=athlete_id, name="Key session").first()
    if tag:
        for a in base_qs.filter(activity_tags__tag=tag):
            if a.id in seen_activity_ids:
                continue
            candidates.append(_activity_candidate(a, "tagged", "Key session"))
            seen_activity_ids.add(a.id)

    long_sport = "bike" if race.sport == "bike" else "run"
    long_candidates = (
        base_qs.filter(sport=long_sport, race_result__isnull=True)
        .exclude(pk__in=seen_activity_ids)
        .order_by("-distance_km")[:4]
    )
    for a in long_candidates:
        candidates.append(_activity_candidate(a, "long", "Long"))
        seen_activity_ids.add(a.id)

    for a in base_qs.exclude(pk__in=seen_activity_ids):
        if a.id in seen_activity_ids:
            continue
        if _is_quality_activity(a, race):
            candidates.append(_activity_candidate(a, "quality", _quality_label(a)))
            seen_activity_ids.add(a.id)

    for activity_id in _top3_activity_ids(athlete_id, window_start, window_end):
        if activity_id in seen_activity_ids:
            continue
        a = Activity.objects.filter(pk=activity_id).first()
        if a is None:
            continue
        candidates.append(_activity_candidate(a, "best_effort", "Best effort"))
        seen_activity_ids.add(a.id)

    today = timezone.now().date()
    planned_start = max(window_start, today)
    planned_count = 0
    if planned_start <= window_end:
        for sw in (
            ScheduledWorkout.objects.filter(
                athlete_id=athlete_id, status="planned", date__range=(planned_start, window_end)
            )
            .select_related("workout")
            .order_by("date")
        ):
            if planned_count >= 3:
                break
            if not _is_long_or_quality_workout(sw.workout):
                continue
            candidates.append(
                {
                    "date": sw.date,
                    "name": sw.workout.name,
                    "activity_id": None,
                    "scheduled_workout_id": sw.id,
                    "reason": "planned",
                    "label": "Planned",
                    "km": None,
                    "tss": sw.workout.tss,
                }
            )
            planned_count += 1

    total_candidates = len(candidates)
    candidates.sort(key=lambda c: c["date"])
    for c in candidates:
        c["date"] = c["date"].isoformat()
    return {"total_candidates": total_candidates, "items": candidates}


# --- sweat rate -----------------------------------------------------------------------


def sweat_rate(athlete_id: str) -> tuple[float, str]:
    qs = Activity.objects.filter(
        athlete_id=athlete_id,
        sport="run",
        moving_time__gte=3600,
        start_weight_kg__isnull=False,
        end_weight_kg__isnull=False,
        fluids_ml__isnull=False,
    )
    rates = []
    for a in qs:
        weight_loss_ml = (a.start_weight_kg - a.end_weight_kg) * 1000.0
        total_loss_ml = weight_loss_ml + a.fluids_ml
        hours = a.moving_time / 3600.0
        if hours > 0 and total_loss_ml > 0:
            rates.append((total_loss_ml / 1000.0) / hours)
    if not rates:
        return 0.9, "default"
    return round(sum(rates) / len(rates), 2), "measured"


# --- top-level assembler ----------------------------------------------------------------


def get_race_block(race: Race) -> dict[str, Any]:
    athlete: User = race.athlete
    today = timezone.now().date()
    block_weeks_pref = athlete.race_block_weeks

    if race.priority == "A":
        block = a_race_block_range(race, block_weeks_pref)
        start_week, end_week, n_weeks = block["start_week"], block["end_week"], block["weeks"]
        phase_list = phases(n_weeks)
        weeks_requested = block_weeks_pref
        truncated_by_race_id = block["truncated_by_race_id"]
        parent = None
    else:
        race_week = _week_start(race.date)
        start_week, end_week, n_weeks = race_week - timedelta(weeks=2), race_week + timedelta(weeks=2), 5
        phase_list = []
        weeks_requested = 5
        truncated_by_race_id = None
        parent_race = find_parent_a_race(race, block_weeks_pref)
        parent = None
        if parent_race:
            parent_block = a_race_block_range(parent_race, block_weeks_pref)
            week_num = (race_week - parent_block["start_week"]).days // 7 + 1
            parent = {"race_id": parent_race.id, "week": week_num, "of": parent_block["weeks"]}

    week_bounds = _week_bounds(start_week, n_weeks)
    weekly = [_weekly_aggregate(athlete.id, ws, we, today) for ws, we in week_bounds]
    sweat_rate_value, sweat_rate_source = sweat_rate(athlete.id)

    result: dict[str, Any] = {
        "start": start_week.isoformat(),
        "end": end_week.isoformat(),
        "weeks_requested": weeks_requested,
        "weeks": weekly,
        "truncated_by_race_id": truncated_by_race_id,
        "phases": phase_list,
        "race_day_form": race_day_form(race),
        "key_sessions": key_sessions(race, start_week, end_week),
        "sweat_rate_l_per_h": sweat_rate_value,
        "sweat_rate_source": sweat_rate_source,
    }

    if race.priority == "A":
        comparison_race = find_comparison_race(race)
        result["comparison"] = (
            {"race_id": comparison_race.id, "weeks": comparison_weeks(n_weeks, comparison_race, today)}
            if comparison_race
            else None
        )
        result["prediction"] = riegel_prediction(race, start_week, end_week)

        durability = None
        if race.sport in ("run", "bike"):
            internal_sport = race.sport
            current = _durability_section(athlete, internal_sport, start=start_week, end=end_week)
            comparison_section = None
            if comparison_race:
                comp_block = a_race_block_range(comparison_race, block_weeks_pref)
                comparison_section = _durability_section(
                    athlete, internal_sport, start=comp_block["start_week"], end=comp_block["end_week"]
                )
            durability = {
                "sport": _DURABILITY_WIRE_SPORT[internal_sport],
                "current": current,
                "comparison": comparison_section,
            }
        result["durability"] = durability
    else:
        result["parent"] = parent

    return result
