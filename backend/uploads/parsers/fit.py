from fitparse import FitFile

from .utils import ensure_utc

SPORT_MAP = {
    "running": "run",
    "cycling": "bike",
    "swimming": "swim",
    "walking": "walk",
    "hiking": "walk",
}

SEMICIRCLE_TO_DEGREES = 180 / (2**31)


def _semicircles_to_degrees(value):
    return value * SEMICIRCLE_TO_DEGREES if value is not None else None


def parse_fit(path):
    fit_file = FitFile(path)

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
    samples = []
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
            power = message.get_value("Power")
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
            }
        )

    laps = []
    for index, message in enumerate(fit_file.get_messages("lap"), start=1):
        laps.append(
            {
                "index": index,
                "duration": int(message.get_value("total_elapsed_time") or 0),
                "distance_km": (message.get_value("total_distance") or 0) / 1000,
                "avg_hr": message.get_value("avg_heart_rate"),
                "avg_power": message.get_value("avg_power"),
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
