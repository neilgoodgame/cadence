from collections.abc import Sequence
from datetime import timedelta

from django.core.files.storage import default_storage
from django.utils import timezone

from accounts.models import User
from activities.models import Activity, ActivityTag, BestEffort, DurationCurve, Lap, Record, Tag
from athletes.zones import get_or_create_zone_set, reference_for
from scheduling.models import ScheduledWorkout
from scheduling.serializers import ScheduledWorkoutSerializer
from webhooks.events import fire_event

from .models import Upload
from .parsers import parse_file
from .parsers.fit import NoActivityDataError
from .parsers.types import Lap as LapDict
from .parsers.types import Sample

POWER_CURVE_DURATIONS = [5, 15, 30, 60, 300, 600, 1200, 3600]
HR_CURVE_DURATIONS = [60, 300, 600, 1200, 3600]
HR_BEST_EFFORT_WINDOWS = [
    ("1min", 60),
    ("5min", 300),
    ("10min", 600),
    ("20min", 1200),
    ("60min", 3600),
]
POWER_BEST_EFFORT_WINDOWS = [
    ("5s", 5),
    ("15s", 15),
    ("30s", 30),
    ("1min", 60),
    ("5min", 300),
    ("10min", 600),
    ("20min", 1200),
    ("60min", 3600),
]
PACE_BEST_EFFORT_DISTANCES_KM = [
    ("1km", 1.0),
    ("5km", 5.0),
    ("10km", 10.0),
    ("half_marathon", 21.0975),
    ("30km", 30.0),
    ("marathon", 42.195),
    ("50km", 50.0),
]

# Cutoffs a window's leaderboard is independently top-N'd against, in addition to unbounded
# all-time (see _trim_kind_window). 28/112 are what the Best Efforts screen's "4 weeks"/
# "16 weeks" tabs narrow down to client-side (PERIOD_CONFIG in
# frontend/src/screens/BestEffortsScreen.tsx); 90/365 are this API's own `period=3m|1y` values
# (BEST_EFFORT_PERIOD_DAYS in athletes/views.py). Keep in sync with
# BestEffortWindows.TRIM_PERIOD_DAYS in the Java backend - see ARCHITECTURE.md section 12.
BEST_EFFORT_TRIM_PERIOD_DAYS: tuple[int, ...] = (28, 90, 112, 365)

SPORT_LABELS = {
    "bike": "Bike",
    "run": "Run",
    "swim": "Swim",
    "walk": "Walk",
    "multisport": "Multisport",
    "transition": "Transition",
}


class UploadProcessingError(Exception):
    def __init__(self, code: str, message: str) -> None:
        self.code = code
        self.message = message
        super().__init__(message)


def _mean(values: Sequence[float | None]) -> float | None:
    filtered = [v for v in values if v is not None]
    return sum(filtered) / len(filtered) if filtered else None


def _max(values: Sequence[float | None]) -> float | None:
    filtered = [v for v in values if v is not None]
    return max(filtered) if filtered else None


def _min(values: Sequence[float | None]) -> float | None:
    filtered = [v for v in values if v is not None]
    return min(filtered) if filtered else None


def _moving_time(samples: Sequence[Sample]) -> int:
    if not samples:
        return 0
    return int(samples[-1]["t"] - samples[0]["t"] + 1)


def _total_distance_km(samples: Sequence[Sample], laps: Sequence[LapDict]) -> float:
    raw_distances = [s.get("distance_km") for s in samples]
    cumulative = [d for d in raw_distances if d is not None]
    if cumulative:
        return float(round(cumulative[-1], 3))
    if laps:
        return float(round(sum(lap["distance_km"] for lap in laps), 3))
    return 0.0


def _total_ascent(samples: Sequence[Sample]) -> int | None:
    raw_altitudes = [s.get("altitude") for s in samples]
    altitudes = [a for a in raw_altitudes if a is not None]
    if len(altitudes) < 2:
        return None
    gain = 0.0
    for prev, curr in zip(altitudes, altitudes[1:], strict=False):
        if curr > prev:
            gain += curr - prev
    return int(round(gain))


def _total_descent(samples: Sequence[Sample]) -> int | None:
    raw_altitudes = [s.get("altitude") for s in samples]
    altitudes = [a for a in raw_altitudes if a is not None]
    if len(altitudes) < 2:
        return None
    loss = 0.0
    for prev, curr in zip(altitudes, altitudes[1:], strict=False):
        if curr < prev:
            loss += prev - curr
    return int(round(loss))


def compute_normalized_power(power_series: Sequence[float | None], window: int = 30) -> float | None:
    values = [p if p is not None else 0 for p in power_series]
    if not values:
        return None
    if len(values) < window:
        return sum(values) / len(values)
    rolling: list[float] = []
    window_sum = sum(values[:window])
    rolling.append(window_sum / window)
    for i in range(window, len(values)):
        window_sum += values[i] - values[i - window]
        rolling.append(window_sum / window)
    mean_fourth = sum(r**4 for r in rolling) / len(rolling)
    return float(mean_fourth**0.25)


def _sliding_window_best_avg(values: Sequence[float], window: int) -> float | None:
    n = len(values)
    if window > n or window <= 0:
        return None
    window_sum = sum(values[:window])
    best = window_sum / window
    for i in range(window, n):
        window_sum += values[i] - values[i - window]
        avg = window_sum / window
        if avg > best:
            best = avg
    return best


def _best_pace_seconds_per_km(
    t_series: Sequence[int], distance_km_series: Sequence[float | None], target_km: float
) -> float | None:
    """The fastest pace over any contiguous span of the activity covering at least
    target_km - a classic minimum-window two-pointer scan, not a variant of
    _sliding_window_best_avg: a fixed *distance* target needs a variable-length *time*
    window, the opposite shape of a fixed-duration best-effort.

    Takes t_series (each sample's real elapsed-seconds offset) rather than assuming
    samples are 1 Hz - some devices ("smart"/adaptive recording) log a sample every few
    seconds instead of every second, and using the sample *index* gap as a stand-in for
    elapsed time silently understates duration (and so overstates pace) on those files.
    """
    # Forward-fill: a None sample (e.g. a brief GPS dropout) means "no new distance
    # recorded yet," not "reset to zero" - the same convention _total_distance_km relies on.
    cumulative: list[float] = []
    last = 0.0
    for d in distance_km_series:
        if d is not None:
            last = d
        cumulative.append(last)

    n = len(cumulative)
    best: float | None = None
    left = 0
    right = 0
    while left < n:
        if right < left:
            right = left
        while right < n and cumulative[right] - cumulative[left] < target_km:
            right += 1
        if right >= n:
            break
        duration = t_series[right] - t_series[left]
        actual_distance = cumulative[right] - cumulative[left]
        if duration > 0 and actual_distance > 0:
            pace = duration / actual_distance
            if best is None or pace < best:
                best = pace
        left += 1
    return best


def compute_duration_curve(series: Sequence[float | None], durations: Sequence[int]) -> dict[str, float]:
    values = [v if v is not None else 0 for v in series]
    points: dict[str, float] = {}
    for duration in durations:
        best = _sliding_window_best_avg(values, duration)
        if best is not None:
            points[str(duration)] = round(best, 1)
    # The contract documents that a curve extends to the full activity length when it
    # exceeds the longest standard window (the API's `extends_to` field already reflects
    # this), with the final point being the whole-activity average - a "window == the
    # whole series" sliding window has exactly one position, so this is just its one value.
    n = len(values)
    if durations and n > max(durations):
        whole_activity_avg = _sliding_window_best_avg(values, n)
        if whole_activity_avg is not None:
            points[str(n)] = round(whole_activity_avg, 1)
    return points


def compute_time_in_zone_seconds(athlete: User, heartrate_series: Sequence[float | None]) -> dict[str, int] | None:
    zone_set = get_or_create_zone_set(athlete, "heart_rate")
    threshold = reference_for(athlete, "heart_rate")
    if not threshold:
        return None
    seconds_per_zone = {zone["name"]: 0 for zone in zone_set.zones}
    for hr in heartrate_series:
        if hr is None:
            continue
        pct = hr / threshold * 100
        for zone in zone_set.zones:
            if zone["low_pct"] <= pct <= zone["high_pct"]:
                seconds_per_zone[zone["name"]] += 1
                break
    return seconds_per_zone


def _power_based_tss(norm_power: float | None, threshold_power: int | None, moving_time_seconds: int) -> int | None:
    if not norm_power or not threshold_power:
        return None
    intensity = norm_power / threshold_power
    # IF > 1.5 indicates corrupt power data (e.g. Garmin Running Power field overriding
    # a Stryd developer field in the FIT file, producing physically impossible wattage).
    if intensity > 1.5:
        return None
    return round((moving_time_seconds * norm_power * intensity) / (threshold_power * 3600) * 100)


def _hr_based_tss(athlete: User, heartrate_series: Sequence[float | None], moving_time_seconds: int) -> int:
    """Coarse hrTSS fallback when no power-based threshold is set: each HR
    zone is weighted by its %-of-threshold midpoint, since we have no LTHR-
    relative intensity-factor equivalent without a power meter.
    """
    zones_seconds = compute_time_in_zone_seconds(athlete, heartrate_series)
    if not zones_seconds:
        return 0
    zone_set = get_or_create_zone_set(athlete, "heart_rate")
    tss = 0.0
    for zone in zone_set.zones:
        seconds = zones_seconds.get(zone["name"], 0)
        midpoint_pct = (zone["low_pct"] + zone["high_pct"]) / 2
        tss += (seconds / 3600) * midpoint_pct
    return round(tss)


def compute_tss(
    activity: Activity, athlete: User, norm_power: float | None, heartrate_series: Sequence[float | None]
) -> int:
    threshold_power = None
    if activity.sport == "bike":
        threshold_power = athlete.ftp
    elif activity.sport == "run":
        threshold_power = athlete.critical_run_power

    power_tss = _power_based_tss(norm_power, threshold_power, activity.moving_time)
    if power_tss is not None:
        return power_tss
    return _hr_based_tss(athlete, heartrate_series, activity.moving_time)


def compute_edwards_trimp(athlete: User, heartrate_series: Sequence[float | None]) -> float | None:
    """Edwards' TRIMP: sum over HR zones of (minutes in zone * zone number 1-5). Chosen
    over Banister's original formula because it needs no resting-HR baseline - it reuses
    the same HR zone set already computed for hrTSS.
    """
    zone_set = get_or_create_zone_set(athlete, "heart_rate")
    zones_seconds = compute_time_in_zone_seconds(athlete, heartrate_series)
    if not zones_seconds:
        return None
    trimp = 0.0
    for zone_number, zone in enumerate(zone_set.zones, start=1):
        seconds = zones_seconds.get(zone["name"], 0)
        trimp += (seconds / 60) * zone_number
    return round(trimp, 1)


def compute_calories(power_series: Sequence[float | None], moving_time_seconds: int) -> int | None:
    """Power-based estimate only: work_kJ / 0.24 (a standard cycling efficiency
    approximation). Deliberately not falling back to an HR-based estimate for
    power-less activities, which would be a much rougher guess.
    """
    avg_power = _mean(power_series)
    if avg_power is None:
        return None
    work_kj = avg_power * moving_time_seconds / 1000
    return round(work_kj / 0.24)


def training_effect_label(aerobic_training_effect: float | None) -> str:
    """Maps Garmin's 0.0-5.0 aerobic training effect to its benefit label.

    Per Garmin's documented scale: 0.0-0.9 No Benefit, 1.0-1.9 Minor Benefit,
    2.0-2.9 Maintaining, 3.0-3.9 Improving, 4.0-4.9 Highly Improving, 5.0
    Overreaching.
    """
    if aerobic_training_effect is None:
        return ""
    if aerobic_training_effect < 1.0:
        return "No Benefit"
    if aerobic_training_effect < 2.0:
        return "Minor Benefit"
    if aerobic_training_effect < 3.0:
        return "Maintaining"
    if aerobic_training_effect < 4.0:
        return "Improving"
    if aerobic_training_effect < 5.0:
        return "Highly Improving"
    return "Overreaching"


def _write_duration_curves(
    activity: Activity, power_series: Sequence[float | None], hr_series: Sequence[float | None]
) -> None:
    n = len(power_series)
    if any(p is not None for p in power_series):
        points = compute_duration_curve(power_series, POWER_CURVE_DURATIONS)
        if points:
            DurationCurve.objects.update_or_create(
                activity=activity, metric="power", defaults={"extends_to": n, "points": points}
            )
    if any(h is not None for h in hr_series):
        points = compute_duration_curve(hr_series, HR_CURVE_DURATIONS)
        if points:
            DurationCurve.objects.update_or_create(
                activity=activity, metric="heartrate", defaults={"extends_to": n, "points": points}
            )


def _trim_kind_window(athlete_id: str, kind: str, window: str, lower_is_better: bool, top_n: int) -> None:
    """Deletes (not just hides) anything outside the athlete's top N for this kind/window in
    EVERY period it tracks - a row survives if it's in the top N all-time, OR in the top N of
    any cutoff in BEST_EFFORT_TRIM_PERIOD_DAYS (as of today, not the activity's own date - this
    keeps a rolling "last 28 days" window rolling even when replaying old activities during a
    recompute). This is why the Best Efforts screen's period filters can show a recent effort
    that isn't an all-time record: it only needs to be a record within ITS OWN period.

    Storage is still bounded, just not to a single top_n anymore: at most
    top_n * (len(BEST_EFFORT_TRIM_PERIOD_DAYS) + 1) rows per (athlete, kind, window), and
    typically far fewer since the periods overlap and mostly keep the same rows.
    """
    if top_n == 0:
        return
    qs = BestEffort.objects.filter(athlete_id=athlete_id, kind=kind, window=window)
    order = "value" if lower_is_better else "-value"
    ranked = list(qs.order_by(order).values_list("id", "date"))
    if len(ranked) <= top_n:
        return

    keeper_ids: set[int] = {row_id for row_id, _row_date in ranked[:top_n]}
    today = timezone.now().date()
    for days in BEST_EFFORT_TRIM_PERIOD_DAYS:
        cutoff = today - timedelta(days=days)
        kept_in_period = 0
        for row_id, row_date in ranked:
            if row_date < cutoff:
                continue
            keeper_ids.add(row_id)
            kept_in_period += 1
            if kept_in_period == top_n:
                break

    qs.exclude(id__in=keeper_ids).delete()


def _update_power_best_efforts(
    activity: Activity, athlete: User, kind: str, power_series: Sequence[float | None]
) -> None:
    values = [p if p is not None else 0 for p in power_series]
    top_n = athlete.best_effort_top_n
    for window_label, seconds in POWER_BEST_EFFORT_WINDOWS:
        if seconds > len(values):
            continue
        best_avg = _sliding_window_best_avg(values, seconds)
        if best_avg is None:
            continue
        BestEffort.objects.update_or_create(
            athlete=athlete,
            kind=kind,
            window=window_label,
            activity=activity,
            defaults={
                "value": round(best_avg, 1),
                "unit": "watts",
                "date": activity.start_date.date(),
            },
        )
        _trim_kind_window(athlete.id, kind, window_label, False, top_n)


def _update_hr_best_efforts(activity: Activity, athlete: User, kind: str, hr_series: Sequence[float | None]) -> None:
    values = [h if h is not None else 0 for h in hr_series]
    top_n = athlete.best_effort_top_n
    for window_label, seconds in HR_BEST_EFFORT_WINDOWS:
        if seconds > len(values):
            continue
        best_avg = _sliding_window_best_avg(values, seconds)
        if best_avg is None:
            continue
        BestEffort.objects.update_or_create(
            athlete=athlete,
            kind=kind,
            window=window_label,
            activity=activity,
            defaults={
                "value": round(best_avg, 1),
                "unit": "bpm",
                "date": activity.start_date.date(),
            },
        )
        _trim_kind_window(athlete.id, kind, window_label, False, top_n)


def _update_pace_best_efforts(
    activity: Activity, athlete: User, t_series: Sequence[int], distance_km_series: Sequence[float | None]
) -> None:
    top_n = athlete.best_effort_top_n
    for label, target_km in PACE_BEST_EFFORT_DISTANCES_KM:
        pace_sec_per_km = _best_pace_seconds_per_km(t_series, distance_km_series, target_km)
        if pace_sec_per_km is None:
            continue
        BestEffort.objects.update_or_create(
            athlete=athlete,
            kind="running_pace",
            window=label,
            activity=activity,
            defaults={
                "value": round(pace_sec_per_km, 1),
                "unit": "sec_per_km",
                "date": activity.start_date.date(),
            },
        )
        _trim_kind_window(athlete.id, "running_pace", label, True, top_n)


def compute_kind_best_efforts(
    activity: Activity,
    athlete: User,
    kind: str,
    power_series: Sequence[float | None],
    t_series: Sequence[int],
    distance_km_series: Sequence[float | None],
    hr_series: Sequence[float | None] | None = None,
) -> None:
    if hr_series is None:
        hr_series = []
    has_hr = any(h is not None for h in hr_series)
    if kind == "cycling_power":
        if activity.sport == "bike" and athlete.ftp:
            _update_power_best_efforts(activity, athlete, "cycling_power", power_series)
    elif kind == "cycling_hr":
        if activity.sport == "bike" and has_hr:
            _update_hr_best_efforts(activity, athlete, "cycling_hr", hr_series)
    elif kind == "running_power":
        if activity.sport == "run" and athlete.critical_run_power and any(p for p in power_series):
            _update_power_best_efforts(activity, athlete, "running_power", power_series)
    elif kind == "running_pace":
        if activity.sport == "run":
            _update_pace_best_efforts(activity, athlete, t_series, distance_km_series)
    elif kind == "running_hr":
        if activity.sport == "run" and has_hr:
            _update_hr_best_efforts(activity, athlete, "running_hr", hr_series)


def trim_best_efforts(athlete: User) -> None:
    top_n = athlete.best_effort_top_n
    if top_n == 0:
        return
    for window_label, _ in HR_BEST_EFFORT_WINDOWS:
        _trim_kind_window(athlete.id, "cycling_hr", window_label, False, top_n)
        _trim_kind_window(athlete.id, "running_hr", window_label, False, top_n)
    for window_label, _ in POWER_BEST_EFFORT_WINDOWS:
        _trim_kind_window(athlete.id, "cycling_power", window_label, False, top_n)
        _trim_kind_window(athlete.id, "running_power", window_label, False, top_n)
    for label, _ in PACE_BEST_EFFORT_DISTANCES_KM:
        _trim_kind_window(athlete.id, "running_pace", label, True, top_n)


def update_best_efforts(
    activity: Activity,
    athlete: User,
    power_series: Sequence[float | None],
    t_series: Sequence[int],
    distance_km_series: Sequence[float | None],
    hr_series: Sequence[float | None] | None = None,
) -> None:
    if hr_series is None:
        hr_series = []
    has_hr = any(h is not None for h in hr_series)
    if activity.sport == "bike":
        if athlete.ftp:
            _update_power_best_efforts(activity, athlete, "cycling_power", power_series)
        if has_hr:
            _update_hr_best_efforts(activity, athlete, "cycling_hr", hr_series)
    elif activity.sport == "run":
        if athlete.critical_run_power and any(p for p in power_series):
            _update_power_best_efforts(activity, athlete, "running_power", power_series)
        _update_pace_best_efforts(activity, athlete, t_series, distance_km_series)
        if has_hr:
            _update_hr_best_efforts(activity, athlete, "running_hr", hr_series)


def attempt_workout_match(activity: Activity, athlete: User) -> None:
    candidate = (
        ScheduledWorkout.objects.filter(
            athlete=athlete,
            date=activity.start_date.date(),
            status="planned",
            activity__isnull=True,
            workout__sport=activity.sport,
        )
        .select_related("workout")
        .first()
    )
    if candidate is None:
        return
    candidate.activity = activity
    candidate.status = "completed"
    candidate.save(update_fields=["activity", "status"])
    activity.workout = candidate.workout
    activity.save(update_fields=["workout"])
    tag, _created = Tag.objects.get_or_create(athlete=athlete, name="Auto-matched", defaults={"origin": "auto"})
    ActivityTag.objects.get_or_create(activity=activity, tag=tag)
    fire_event("scheduled_workout.matched", athlete.id, ScheduledWorkoutSerializer(candidate).data)


def ingest_upload(upload: Upload) -> Activity:
    """Creates the activity (or activities) for an upload and runs the full derived-data
    pipeline. A multisport FIT file arrives from the parser parent-first; the parent is
    ingested without TSS/curves/best-efforts/workout-matching (its stream mixes sports),
    each leg is ingested in full and linked via parent_activity, and the parent's TSS is
    then set to the sum of its legs'. Returns the parent (or the sole activity).
    """
    try:
        parsed_activities = parse_file(default_storage.path(upload.stored_path), upload.filename)
    except NoActivityDataError as exc:
        raise UploadProcessingError("no_activity_data", str(exc)) from exc
    except Exception as exc:
        raise UploadProcessingError("corrupt_file", str(exc)) from exc

    if not parsed_activities or not parsed_activities[0]["samples"]:
        raise UploadProcessingError("empty_file", "No samples found in file.")

    athlete = upload.athlete
    multisport = parsed_activities[0]["sport"] == "multisport"

    primary = _ingest_activity(upload, parsed_activities[0], athlete, parent=None, multisport=multisport)
    if multisport:
        children = [
            _ingest_activity(upload, parsed, athlete, parent=primary, multisport=True)
            for parsed in parsed_activities[1:]
        ]
        # The parent's training load is the sum of its legs' - computing TSS over the
        # mixed-sport stream directly would need a single threshold that doesn't exist.
        primary.tss = sum(child.tss or 0 for child in children)
        primary.save(update_fields=["tss"])
    return primary


def _ingest_activity(
    upload: Upload, parsed: dict, athlete: User, parent: Activity | None, multisport: bool
) -> Activity:
    samples = parsed["samples"]
    laps = parsed.get("laps", [])
    sport = parsed["sport"]
    is_multisport_parent = multisport and parent is None

    if multisport:
        # The shoe travels with the run/walk legs; the parent spans sports it wasn't worn for.
        wears_shoe = parent is not None and sport in ("run", "walk")
    else:
        wears_shoe = True

    activity = Activity.objects.create(
        athlete=athlete,
        sport=sport,
        environment=parsed["environment"],
        has_gps=parsed["has_gps"],
        name=f"{SPORT_LABELS.get(sport, 'Activity')} on {parsed['start_date']:%Y-%m-%d}",
        start_date=parsed["start_date"],
        source=parsed.get("source", ""),
        device=parsed.get("device", ""),
        moving_time=_moving_time(samples),
        distance_km=_total_distance_km(samples, laps),
        distance_source=parsed.get("distance_source", "gps" if parsed["has_gps"] else "manual"),
        ascent=_total_ascent(samples),
        total_descent=_total_descent(samples),
        parent_activity=parent,
        # Upload-level metadata describes the session as a whole, so it lives on the
        # parent (or sole) activity rather than being duplicated onto each leg.
        start_weight_kg=upload.weight_before_kg if parent is None else None,
        end_weight_kg=upload.weight_after_kg if parent is None else None,
        fluids_ml=upload.fluids_ml if parent is None else None,
        shoe_id=upload.shoe_id if wears_shoe else None,
    )

    Record.objects.bulk_create(
        [
            Record(
                activity=activity,
                t=s["t"],
                ts=activity.start_date + timedelta(seconds=s["t"]),
                power=s.get("power"),
                heartrate=s.get("heartrate"),
                cadence=s.get("cadence"),
                altitude=s.get("altitude"),
                lat=s.get("lat"),
                lng=s.get("lng"),
                speed=s.get("speed"),
                distance_km=s.get("distance_km"),
                air_temp=s.get("air_temp"),
                humidity=s.get("humidity"),
                skin_temp=s.get("skin_temp"),
                core_temp=s.get("core_temp"),
                heat_strain=s.get("heat_strain"),
            )
            for s in samples
        ],
        batch_size=5000,
    )

    Lap.objects.bulk_create(
        [
            Lap(
                activity=activity,
                index=lap["index"],
                duration=lap["duration"],
                distance_km=lap["distance_km"],
                avg_hr=lap.get("avg_hr"),
                avg_power=lap.get("avg_power"),
            )
            for lap in laps
        ]
    )

    power_series = [s.get("power") for s in samples]
    hr_series = [s.get("heartrate") for s in samples]

    norm_power = compute_normalized_power(power_series) if any(p is not None for p in power_series) else None
    avg_power = _mean(power_series)
    avg_hr = _mean(hr_series)
    max_hr = _max(hr_series)

    activity.avg_power = round(avg_power) if avg_power is not None else None
    activity.norm_power = round(norm_power) if norm_power is not None else None
    activity.avg_hr = round(avg_hr) if avg_hr is not None else None
    activity.max_hr = max_hr
    if norm_power and activity.sport == "bike" and athlete.ftp:
        activity.intensity = round(norm_power / athlete.ftp, 3)
    elif norm_power and activity.sport == "run" and athlete.critical_run_power:
        activity.intensity = round(norm_power / athlete.critical_run_power, 3)
    if not is_multisport_parent:
        # The multisport parent's TSS is set by the caller as the sum of its legs'.
        activity.tss = compute_tss(activity, athlete, norm_power, hr_series)

    update_fields = ["avg_power", "norm_power", "avg_hr", "max_hr", "intensity", "tss"]
    if activity.sport == "run":
        air_temp_series = [s.get("air_temp") for s in samples]
        humidity_series = [s.get("humidity") for s in samples]
        if any(v is not None for v in air_temp_series):
            avg_air_temp = _mean(air_temp_series)
            activity.avg_air_temp = round(avg_air_temp, 1) if avg_air_temp is not None else None
            update_fields.append("avg_air_temp")
        if any(v is not None for v in humidity_series):
            avg_humidity = _mean(humidity_series)
            activity.avg_humidity = round(avg_humidity) if avg_humidity is not None else None
            update_fields.append("avg_humidity")

    max_power = _max(power_series)
    if max_power is not None:
        activity.max_power = round(max_power)
        update_fields.append("max_power")

    cadence_series = [s.get("cadence") for s in samples]
    if any(c is not None for c in cadence_series):
        avg_cadence = _mean(cadence_series)
        max_cadence = _max(cadence_series)
        activity.avg_cadence = round(avg_cadence) if avg_cadence is not None else None
        activity.max_cadence = round(max_cadence) if max_cadence is not None else None
        update_fields.extend(["avg_cadence", "max_cadence"])

    speed_series = [s.get("speed") for s in samples]
    max_speed_ms = _max(speed_series)
    if max_speed_ms is not None:
        activity.max_speed = round(max_speed_ms * 3.6, 1)
        update_fields.append("max_speed")

    altitude_series = [s.get("altitude") for s in samples]
    if any(a is not None for a in altitude_series):
        elevation_min = _min(altitude_series)
        elevation_max = _max(altitude_series)
        activity.elevation_min = round(elevation_min) if elevation_min is not None else None
        activity.elevation_max = round(elevation_max) if elevation_max is not None else None
        update_fields.extend(["elevation_min", "elevation_max"])

    calories = compute_calories(power_series, activity.moving_time)
    if calories is not None:
        activity.calories = calories
        update_fields.append("calories")

    trimp = compute_edwards_trimp(athlete, hr_series)
    if trimp is not None:
        activity.trimp = trimp
        update_fields.append("trimp")

    left_balance_series = [s.get("left_balance_pct") for s in samples]
    if any(v is not None for v in left_balance_series):
        avg_left_balance = _mean(left_balance_series)
        activity.avg_left_balance_pct = round(avg_left_balance, 1) if avg_left_balance is not None else None
        update_fields.append("avg_left_balance_pct")

    aerobic_te = parsed.get("aerobic_training_effect")
    anaerobic_te = parsed.get("anaerobic_training_effect")
    if aerobic_te is not None or anaerobic_te is not None:
        activity.aerobic_training_effect = aerobic_te
        activity.anaerobic_training_effect = anaerobic_te
        activity.training_effect_label = training_effect_label(aerobic_te)
        update_fields.extend(["aerobic_training_effect", "anaerobic_training_effect", "training_effect_label"])

    activity.save(update_fields=update_fields)

    if not is_multisport_parent:
        # Duration curves and best efforts compare like-for-like within a sport, and no
        # multisport workouts exist to match - the parent's legs handle all three instead.
        _write_duration_curves(activity, power_series, hr_series)
        t_series = [s["t"] for s in samples]
        distance_km_series = [s.get("distance_km") for s in samples]
        update_best_efforts(activity, athlete, power_series, t_series, distance_km_series, hr_series)
        attempt_workout_match(activity, athlete)

    return activity
