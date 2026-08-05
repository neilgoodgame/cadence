"""Streams one athlete's full data export to a gzip file on local disk.

The JSON shape here is a deliberate mirror of the Java backend's ExportWriter
(backend_java/src/main/java/com/cadence/api/export/ExportWriter.java) - same top-level
keys, same nesting, same field names - so a file exported from either backend can be
imported into the other. See import_reader.py for the read side of that contract.

Unlike Java (which had to guard against Hibernate's persistence context pinning every
loaded entity in memory - see ExportWriter's Javadoc), Django's ORM has no equivalent
identity map, so there's no analogous per-activity "clear the session" step needed here.
The one thing this still has to get right is not materializing a huge activity's whole
Record queryset at once - see _build_streams' use of .iterator().
"""

import gzip
import json
import os
from typing import Any

from django.core.files.storage import default_storage
from django.core.serializers.json import DjangoJSONEncoder
from django.utils import timezone

from activities.models import Activity, Lap, Record
from activities.serializers import ActivitySerializer, LapSerializer
from gear.models import Bike, Component, Shoe
from gear.serializers import BikeSerializer, ComponentSerializer, ShoeSerializer
from races.models import Race
from races.serializers import RaceSerializer
from scheduling.models import ScheduledWorkout
from scheduling.serializers import ScheduledWorkoutSerializer
from workouts.models import Workout
from workouts.serializers import WorkoutDetailSerializer

# Same 12 scalar fields (in the same order) as the Java backend's StreamService.SCALAR_FIELDS -
# always present, filled with None wherever the source data lacks that channel.
STREAM_FIELDS = [
    "time",
    "power",
    "heartrate",
    "cadence",
    "altitude",
    "distance",
    "speed",
    "air_temp",
    "humidity",
    "core_temp",
    "skin_temp",
    "heat_strain",
]
_RECORD_ATTR = {
    "time": "t",
    "power": "power",
    "heartrate": "heartrate",
    "cadence": "cadence",
    "altitude": "altitude",
    "distance": "distance_km",
    "speed": "speed",
    "air_temp": "air_temp",
    "humidity": "humidity",
    "core_temp": "core_temp",
    "skin_temp": "skin_temp",
    "heat_strain": "heat_strain",
}


def _dump(value: Any) -> bytes:
    return json.dumps(value, cls=DjangoJSONEncoder).encode("utf-8")


def _write_array(gz: gzip.GzipFile, items: Any) -> None:
    gz.write(b"[")
    first = True
    for item in items:
        if not first:
            gz.write(b",")
        first = False
        gz.write(_dump(item))
    gz.write(b"]")


def _build_streams(activity: Activity) -> dict:
    fields: dict[str, list] = {name: [] for name in STREAM_FIELDS}
    latlng: list[list[float]] = []
    for record in Record.objects.filter(activity=activity).iterator(chunk_size=2000):
        for field_name, attr in _RECORD_ATTR.items():
            fields[field_name].append(getattr(record, attr))
        if activity.has_gps and record.lat is not None and record.lng is not None:
            latlng.append([record.lat, record.lng])
    if activity.has_gps:
        fields["latlng"] = latlng
    return {"object": "streams", "resolution": "high", "fields": fields}


def _write_equipment(gz: gzip.GzipFile, athlete_id: str) -> None:
    gz.write(b'{"bikes":')
    bikes = list(Bike.objects.filter(athlete_id=athlete_id))
    _write_array(gz, (BikeSerializer(b).data for b in bikes))

    gz.write(b',"shoes":')
    # Active gear only, matching the Java backend's export scope.
    shoes = Shoe.objects.filter(athlete_id=athlete_id, retired=False).select_related("shoe_model_version__shoe_model")
    _write_array(gz, (ShoeSerializer(s).data for s in shoes))

    gz.write(b',"components":')
    components = Component.objects.filter(bike__athlete_id=athlete_id)
    _write_array(gz, (ComponentSerializer(c).data for c in components))
    gz.write(b"}")


def _write_workouts(gz: gzip.GzipFile, athlete_id: str, sport: str | None) -> None:
    qs = Workout.objects.filter(created_by_id=athlete_id).prefetch_related("steps")
    if sport:
        qs = qs.filter(sport=sport)
    _write_array(gz, (WorkoutDetailSerializer(w).data for w in qs))


def _write_activities(gz: gzip.GzipFile, athlete_id: str, sport: str | None) -> None:
    # A multisport parent's own sport is "multisport", not e.g. "bike", so filtering here
    # naturally excludes multisport parents while still including matching individual legs -
    # no special-case code needed (same reasoning as the Java backend's ExportWriter).
    qs = Activity.objects.filter(athlete_id=athlete_id).order_by("start_date")
    if sport:
        qs = qs.filter(sport=sport)

    gz.write(b"[")
    first = True
    for activity in qs.iterator(chunk_size=200):
        if not first:
            gz.write(b",")
        first = False
        entry = {
            "activity": ActivitySerializer(activity).data,
            "laps": LapSerializer(Lap.objects.filter(activity=activity), many=True).data,
            "streams": _build_streams(activity),
        }
        gz.write(_dump(entry))
    gz.write(b"]")


def _write_races(gz: gzip.GzipFile, athlete_id: str, sport: str | None) -> None:
    qs = Race.objects.filter(athlete_id=athlete_id).order_by("date")
    if sport:
        # A race with no sport recorded is excluded under a filter - ambiguous otherwise
        # (matches the Java backend). Race.sport defaults to "", which never equals a
        # real sport value, so this falls out of the plain filter with no extra code.
        qs = qs.filter(sport=sport)
    _write_array(gz, (RaceSerializer(r).data for r in qs))


def _write_scheduled_workouts(gz: gzip.GzipFile, athlete_id: str, sport: str | None) -> None:
    qs = ScheduledWorkout.objects.filter(athlete_id=athlete_id).order_by("date")
    if sport:
        qs = qs.filter(workout__sport=sport)
    _write_array(gz, (ScheduledWorkoutSerializer(s).data for s in qs))


def _ensure_parent_dir(relative_path: str) -> str:
    full_path = default_storage.path(relative_path)
    os.makedirs(os.path.dirname(full_path), exist_ok=True)
    return full_path


def write_export(athlete_id: str, sport: str | None, relative_path: str) -> int:
    """Writes the gzip file to local disk (via default_storage's path resolution - this
    project's MEDIA_ROOT is local disk, which is what makes true incremental streaming
    writes possible; see the module docstring). Returns the file size in bytes.

    Field order matches Java's ExportWriter.write exactly: equipment and workouts (which
    activities reference) before activities, activities (which races/scheduled_workouts
    reference) before those - dependency order, in case any consumer relies on it.
    """
    full_path = _ensure_parent_dir(relative_path)
    with gzip.GzipFile(full_path, mode="wb") as gz:
        gz.write(b'{"generated_at":')
        gz.write(_dump(timezone.now().isoformat().replace("+00:00", "Z")))
        gz.write(b',"athlete_id":')
        gz.write(_dump(athlete_id))

        gz.write(b',"equipment":')
        _write_equipment(gz, athlete_id)

        gz.write(b',"workouts":')
        _write_workouts(gz, athlete_id, sport)

        gz.write(b',"activities":')
        _write_activities(gz, athlete_id, sport)

        gz.write(b',"races":')
        _write_races(gz, athlete_id, sport)

        gz.write(b',"scheduled_workouts":')
        _write_scheduled_workouts(gz, athlete_id, sport)

        gz.write(b"}")
    return os.path.getsize(full_path)
