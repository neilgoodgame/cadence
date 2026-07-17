import fitparse.base as _fitparse_base
import fitparse.records as _fitparse_records
from fitparse import FitFile

from .types import ParsedActivity, Sample
from .utils import ensure_utc


class NoActivityDataError(ValueError):
    """A structurally valid FIT file with no record messages. Garmin account exports mix
    these metadata stubs (device info, timestamp correlation) in with real activities, so
    batch imports treat them as skippable rather than failed."""


SPORT_MAP = {
    "running": "run",
    "cycling": "bike",
    "swimming": "swim",
    "walking": "walk",
    "hiking": "walk",
    "transition": "transition",
}

SEMICIRCLE_TO_DEGREES = 180 / (2**31)


# Multisport FIT files commonly redeclare a developer_data_index partway through
# the file (each sport segment gets its own definition messages). fitparse's
# add_dev_data_id() unconditionally clears the field registry for that index on
# every redeclaration, so a later segment referencing a dev field that wasn't
# re-declared in *that* segment's definition messages crashes parsing with
# "No such field N for dev_data_index M". Patch it to merge instead of clobber.
def _merge_add_dev_data_id(message) -> None:
    dev_data_index = message.get_raw_value("developer_data_index")
    application_id = message.get_raw_value("application_id")
    existing = _fitparse_records.DEV_TYPES.get(dev_data_index)
    fields = existing["fields"] if existing else {}
    _fitparse_records.DEV_TYPES[dev_data_index] = {
        "dev_data_index": dev_data_index,
        "application_id": application_id,
        "fields": fields,
    }


_fitparse_base.add_dev_data_id = _merge_add_dev_data_id


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


def _device_name(fit_file: FitFile) -> str:
    """Human-readable recording device from the file_id message, e.g. "Zwift" or
    "Garmin Edge 830". fitparse resolves known manufacturer/product enums to strings
    and leaves unknown ones as ints; only string values are usable as names. Zwift
    writes a product_name of garbage bytes (sometimes ASCII garbage like "&"), so
    product names are only kept when printable ASCII containing at least one letter.
    """
    file_id = next(iter(fit_file.get_messages("file_id")), None)
    if file_id is None:
        return ""
    manufacturer = file_id.get_value("manufacturer")
    if not isinstance(manufacturer, str):
        return ""
    device = manufacturer.replace("_", " ").title()
    product = file_id.get_value("garmin_product")
    if not isinstance(product, str):
        product = file_id.get_value("product_name")
    if isinstance(product, str) and product.isascii() and product.isprintable() and any(c.isalpha() for c in product):
        device = f"{device} {product.replace('_', ' ').title()}"
    return device


def _session_meta(message) -> dict:
    raw_sport = str(message.get_value("sport") or "").lower()
    return {
        "sport": SPORT_MAP.get(raw_sport, "bike"),
        "start_time": ensure_utc(message.get_value("start_time")),
        # Garmin's Firstbeat-derived training load, 0.0-5.0. Standard FIT
        # session fields (not developer fields) - only present on Garmin
        # devices that run that analytics.
        "aerobic_training_effect": message.get_value("total_training_effect"),
        "anaerobic_training_effect": message.get_value("total_anaerobic_training_effect"),
    }


def parse_fit(path: str) -> list[ParsedActivity]:
    """One activity for a normal file. A multisport file (more than one non-transition
    sport session, e.g. a duathlon's run + transition + ride) returns the parent first -
    sport "multisport", spanning every record in the file - followed by one child per
    session (transitions included) with its slice of the records and laps.
    """
    fit_file = FitFile(path)
    dev_field_scales = _developer_field_scales(fit_file)
    device = _device_name(fit_file)

    sessions = [_session_meta(m) for m in fit_file.get_messages("session")]

    records = list(fit_file.get_messages("record"))
    if not records:
        raise NoActivityDataError("FIT file contains no record messages.")
    laps = list(fit_file.get_messages("lap"))

    ordered = sorted((s for s in sessions if s["start_time"] is not None), key=lambda s: s["start_time"])
    sport_sessions = [s for s in ordered if s["sport"] != "transition"]
    if len(sport_sessions) <= 1:
        sport = sessions[0]["sport"] if sessions else "bike"
        te = sessions[0] if sessions else {}
        return [_build_activity(records, laps, sport, te, dev_field_scales, device)]

    # Multisport. Sessions partition the record stream into chained windows: each session's
    # window runs from its start_time to the next session's start_time (start_time has only
    # whole-second resolution while total_elapsed_time is ms-resolution and overhangs, so
    # deriving each window's end from the session's own elapsed time would drop or
    # double-assign boundary records). The last window is open-ended.
    #
    # TE accumulates over the whole session on the device, so the last non-transition
    # session carries the total for the parent.
    result = [_build_activity(records, laps, "multisport", sport_sessions[-1], dev_field_scales, device)]
    for i, session in enumerate(ordered):
        window_start = session["start_time"]
        window_end = ordered[i + 1]["start_time"] if i + 1 < len(ordered) else None

        def in_window(message) -> bool:
            ts = ensure_utc(
                message.get_value("timestamp") if message.name == "record" else message.get_value("start_time")
            )
            if ts is None or ts < window_start:  # noqa: B023 - consumed before the loop advances
                return False
            return window_end is None or ts < window_end  # noqa: B023

        slice_records = [r for r in records if in_window(r)]
        if not slice_records:
            continue
        slice_laps = [lap for lap in laps if in_window(lap)]
        result.append(_build_activity(slice_records, slice_laps, session["sport"], session, dev_field_scales, device))
    return result


def _build_activity(
    records, laps, sport: str, session_meta: dict, dev_field_scales: dict, device: str
) -> ParsedActivity:
    start_time = ensure_utc(records[0].get_value("timestamp"))
    if start_time is None:
        raise ValueError("FIT file's first record message has no timestamp.")

    # The file's distance stream is cumulative from the very first record; a multisport
    # leg's slice starts mid-stream, so re-base on the slice's first reading.
    distance_base_m = next((d for d in (m.get_value("distance") for m in records) if d is not None), None)

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
                "distance_km": max(0.0, (distance_m - distance_base_m) / 1000) if distance_m is not None else None,
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

    lap_summaries = []
    for index, message in enumerate(laps, start=1):
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
        lap_summaries.append(
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
        "device": device,
        "samples": samples,
        "laps": lap_summaries,
        "distance_source": "gps" if has_gps else "trainer",
        "aerobic_training_effect": session_meta.get("aerobic_training_effect"),
        "anaerobic_training_effect": session_meta.get("anaerobic_training_effect"),
    }
