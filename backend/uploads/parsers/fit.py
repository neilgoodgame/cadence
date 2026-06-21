from fitparse import FitFile

from .types import ParsedActivity, Sample
from .utils import ensure_utc

SPORT_MAP = {
    "running": "run",
    "cycling": "bike",
    "swimming": "swim",
    "walking": "walk",
    "hiking": "walk",
}

SEMICIRCLE_TO_DEGREES = 180 / (2**31)


def _semicircles_to_degrees(value: float | None) -> float | None:
    return value * SEMICIRCLE_TO_DEGREES if value is not None else None


def _developer_field_scales(fit_file: FitFile) -> dict[str, tuple[float | None, float | None]]:
    """fitparse==1.2.0 has a bug: it never copies a developer field's declared scale/offset
    out of the file's own `field_description` messages onto the `DevField` it builds
    (`add_dev_field_description` in fitparse/records.py omits both keyword args entirely), so
    `message.get_value(name)` silently returns the raw encoded integer for any developer field
    that uses one - e.g. a CORE sensor's skin_temperature (scale 100) or heat_strain_index
    (scale 10). Read scale/offset ourselves straight from field_description instead of relying
    on fitparse to have applied them already.
    """
    scales: dict[str, tuple[float | None, float | None]] = {}
    for message in fit_file.get_messages("field_description"):
        name = message.get_value("field_name")
        if name:
            scales[name] = (message.get_value("scale"), message.get_value("offset"))
    return scales


def _developer_value(message, field_name: str, scales: dict[str, tuple[float | None, float | None]]):
    raw_value = message.get_value(field_name)
    if raw_value is None:
        return None
    scale, offset = scales.get(field_name, (None, None))
    if scale:
        raw_value = raw_value / scale
    if offset:
        raw_value = raw_value - offset
    return raw_value


def parse_fit(path: str) -> ParsedActivity:
    fit_file = FitFile(path)
    dev_field_scales = _developer_field_scales(fit_file)

    sport = "bike"
    for message in fit_file.get_messages("session"):
        raw_sport = message.get_value("sport")
        if raw_sport:
            sport = SPORT_MAP.get(str(raw_sport).lower(), "bike")
        break

    records = list(fit_file.get_messages("record"))
    if not records:
        raise ValueError("FIT file contains no record messages.")

    start_time = ensure_utc(records[0].get_value("timestamp"))
    if start_time is None:
        raise ValueError("FIT file's first record message has no timestamp.")
    samples: list[Sample] = []
    has_gps = False
    for message in records:
        timestamp = ensure_utc(message.get_value("timestamp"))
        t = int((timestamp - start_time).total_seconds()) if timestamp else len(samples)
        lat = _semicircles_to_degrees(message.get_value("position_lat"))
        lng = _semicircles_to_degrees(message.get_value("position_long"))
        if lat is not None and lng is not None:
            has_gps = True
        distance_m = message.get_value("distance")
        speed = message.get_value("enhanced_speed")
        if speed is None:
            speed = message.get_value("speed")
        altitude = message.get_value("enhanced_altitude")
        if altitude is None:
            altitude = message.get_value("altitude")
        power = message.get_value("power")
        if power is None:
            # Third-party run-power meters (e.g. Stryd) write power as a
            # developer field named "Power" rather than the standard
            # lowercase "power" field used by native power meters.
            power = _developer_value(message, "Power", dev_field_scales)
        samples.append(
            {
                "t": t,
                "lat": lat,
                "lng": lng,
                "altitude": altitude,
                "distance_km": distance_m / 1000 if distance_m is not None else None,
                "heartrate": message.get_value("heart_rate"),
                "cadence": message.get_value("cadence"),
                "power": power,
                "speed": speed,
                # Stryd footpod developer fields: ambient temperature/humidity.
                "air_temp": _developer_value(message, "Stryd Temperature", dev_field_scales),
                "humidity": _developer_value(message, "Stryd Humidity", dev_field_scales),
                # CORE body-temperature sensor developer fields.
                "core_temp": _developer_value(message, "core_temperature", dev_field_scales),
                "skin_temp": _developer_value(message, "skin_temperature", dev_field_scales),
                "heat_strain": _developer_value(message, "heat_strain_index", dev_field_scales),
            }
        )

    laps = []
    for index, message in enumerate(fit_file.get_messages("lap"), start=1):
        duration = int(message.get_value("total_elapsed_time") or 0)
        avg_power = message.get_value("avg_power")
        if avg_power is None:
            # Third-party run-power meters (e.g. Stryd) don't fill in the lap message's
            # own avg_power summary field - only a native power meter does. Fall back to
            # averaging the already Stryd-fallback-applied per-sample power (see `power`
            # above) over the lap's time window instead of reporting it as simply missing.
            lap_start = ensure_utc(message.get_value("start_time"))
            if lap_start is not None:
                lap_start_t = int((lap_start - start_time).total_seconds())
                lap_end_t = lap_start_t + duration
                powers = [s["power"] for s in samples if lap_start_t <= s["t"] < lap_end_t and s["power"] is not None]
                if powers:
                    avg_power = round(sum(powers) / len(powers))
        laps.append(
            {
                "index": index,
                "duration": duration,
                "distance_km": (message.get_value("total_distance") or 0) / 1000,
                "avg_hr": message.get_value("avg_heart_rate"),
                "avg_power": avg_power,
            }
        )

    return {
        "sport": sport,
        "environment": "outdoor" if has_gps else "indoor",
        "has_gps": has_gps,
        "start_date": start_time,
        "source": "fit",
        "samples": samples,
        "laps": laps,
        "distance_source": "gps" if has_gps else "trainer",
    }
