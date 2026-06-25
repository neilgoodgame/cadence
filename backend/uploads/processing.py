from collections.abc import Sequence
from datetime import timedelta

from django.core.files.storage import default_storage

from accounts.models import User
from activities.models import Activity, ActivityTag, BestEffort, DurationCurve, Lap, Record, Tag
from athletes.zones import get_or_create_zone_set, reference_for
from scheduling.models import ScheduledWorkout
from scheduling.serializers import ScheduledWorkoutSerializer
from webhooks.events import fire_event

from .models import Upload
from .parsers import parse_file
from .parsers.types import Lap as LapDict
from .parsers.types import Sample

POWER_CURVE_DURATIONS = [5, 15, 30, 60, 300, 600, 1200, 3600]
HR_CURVE_DURATIONS = [60, 300, 600, 1200, 3600]
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

SPORT_LABELS = {"bike": "Bike", "run": "Run", "swim": "Swim", "walk": "Walk"}


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


def _best_pace_seconds_per_km(distance_km_series: Sequence[float | None], target_km: float) -> float | None:
    """The fastest pace over any contiguous span of the activity covering at least
    target_km - a classic minimum-window two-pointer scan, not a variant of
    _sliding_window_best_avg: a fixed *distance* target needs a variable-length *time*
    window, the opposite shape of a fixed-duration best-effort.
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
        duration = right - left
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


def _update_power_best_efforts(
    activity: Activity, athlete: User, kind: str, power_series: Sequence[float | None]
) -> None:
    values = [p if p is not None else 0 for p in power_series]
    for window_label, seconds in POWER_BEST_EFFORT_WINDOWS:
        if seconds > len(values):
            continue
        best_avg = _sliding_window_best_avg(values, seconds)
        if best_avg is None:
            continue
        existing = BestEffort.objects.filter(athlete=athlete, kind=kind, window=window_label).first()
        if existing is None or best_avg > existing.value:
            BestEffort.objects.update_or_create(
                athlete=athlete,
                kind=kind,
                window=window_label,
                defaults={
                    "value": round(best_avg, 1),
                    "unit": "watts",
                    "date": activity.start_date.date(),
                    "activity": activity,
                },
            )


def _update_pace_best_efforts(activity: Activity, athlete: User, distance_km_series: Sequence[float | None]) -> None:
    for label, target_km in PACE_BEST_EFFORT_DISTANCES_KM:
        pace_sec_per_km = _best_pace_seconds_per_km(distance_km_series, target_km)
        if pace_sec_per_km is None:
            continue
        existing = BestEffort.objects.filter(athlete=athlete, kind="running_pace", window=label).first()
        if existing is None or pace_sec_per_km < existing.value:
            BestEffort.objects.update_or_create(
                athlete=athlete,
                kind="running_pace",
                window=label,
                defaults={
                    "value": round(pace_sec_per_km, 1),
                    "unit": "sec_per_km",
                    "date": activity.start_date.date(),
                    "activity": activity,
                },
            )


def update_best_efforts(
    activity: Activity,
    athlete: User,
    power_series: Sequence[float | None],
    distance_km_series: Sequence[float | None],
) -> None:
    if activity.sport == "bike" and athlete.ftp:
        _update_power_best_efforts(activity, athlete, "cycling_power", power_series)
    elif activity.sport == "run":
        if athlete.critical_run_power and any(p for p in power_series):
            _update_power_best_efforts(activity, athlete, "running_power", power_series)
        _update_pace_best_efforts(activity, athlete, distance_km_series)


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
    try:
        parsed = parse_file(default_storage.path(upload.stored_path), upload.filename)
    except Exception as exc:
        raise UploadProcessingError("corrupt_file", str(exc)) from exc

    samples = parsed["samples"]
    if not samples:
        raise UploadProcessingError("empty_file", "No samples found in file.")

    athlete = upload.athlete
    laps = parsed.get("laps", [])

    activity = Activity.objects.create(
        athlete=athlete,
        sport=parsed["sport"],
        environment=parsed["environment"],
        has_gps=parsed["has_gps"],
        name=f"{SPORT_LABELS.get(parsed['sport'], 'Activity')} on {parsed['start_date']:%Y-%m-%d}",
        start_date=parsed["start_date"],
        source=parsed.get("source", ""),
        moving_time=_moving_time(samples),
        distance_km=_total_distance_km(samples, laps),
        distance_source=parsed.get("distance_source", "gps" if parsed["has_gps"] else "manual"),
        ascent=_total_ascent(samples),
        start_weight_kg=upload.weight_before_kg,
        end_weight_kg=upload.weight_after_kg,
        fluids_ml=upload.fluids_ml,
        shoe_id=upload.shoe_id,
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

    aerobic_te = parsed.get("aerobic_training_effect")
    anaerobic_te = parsed.get("anaerobic_training_effect")
    if aerobic_te is not None or anaerobic_te is not None:
        activity.aerobic_training_effect = aerobic_te
        activity.anaerobic_training_effect = anaerobic_te
        activity.training_effect_label = training_effect_label(aerobic_te)
        update_fields.extend(["aerobic_training_effect", "anaerobic_training_effect", "training_effect_label"])

    activity.save(update_fields=update_fields)

    _write_duration_curves(activity, power_series, hr_series)
    distance_km_series = [s.get("distance_km") for s in samples]
    update_best_efforts(activity, athlete, power_series, distance_km_series)
    attempt_workout_match(activity, athlete)

    return activity
