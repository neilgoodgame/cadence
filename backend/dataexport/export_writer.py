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
from collections.abc import Callable
from typing import Any

from django.core.files.storage import default_storage
from django.core.serializers.json import DjangoJSONEncoder
from django.db.models import Q
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

OnStep = Callable[[str], None]

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


def _bikes_qs(athlete_id: str):
    return Bike.objects.filter(athlete_id=athlete_id)


def _shoes_qs(athlete_id: str):
    # Active gear only, matching the Java backend's export scope.
    return Shoe.objects.filter(athlete_id=athlete_id, retired=False)


def _components_qs(athlete_id: str):
    return Component.objects.filter(bike__athlete_id=athlete_id)


def _workouts_qs(athlete_id: str, sport: str | None):
    qs = Workout.objects.filter(created_by_id=athlete_id)
    if sport:
        qs = qs.filter(sport=sport)
    return qs


def _activities_qs(athlete_id: str, sport: str | None):
    # A multisport parent's own sport is "multisport", not e.g. "bike", so filtering here
    # naturally excludes multisport parents while still including matching individual legs -
    # no special-case code needed (same reasoning as the Java backend's ExportWriter).
    qs = Activity.objects.filter(athlete_id=athlete_id).order_by("start_date")
    if sport:
        qs = qs.filter(sport=sport)
    return qs


def _races_qs(athlete_id: str, sport: str | None):
    qs = Race.objects.filter(athlete_id=athlete_id).select_related("activity").order_by("date")
    if sport:
        # A race's own sport can be blank even when it's linked to an activity - linking to
        # an *existing* race (as opposed to "mark this activity as a race", which sets sport
        # at creation) never backfills it. Fall back to the linked activity's sport so a
        # sport-scoped export doesn't silently drop these. A race with no sport recorded and
        # no linked activity to infer one from is still excluded under a filter - ambiguous
        # otherwise (matches the Java backend).
        qs = qs.filter(Q(sport=sport) | Q(sport="", activity__sport=sport))
    return qs


def _scheduled_workouts_qs(athlete_id: str, sport: str | None):
    qs = ScheduledWorkout.objects.filter(athlete_id=athlete_id).order_by("date")
    if sport:
        qs = qs.filter(workout__sport=sport)
    return qs


def _counts(athlete_id: str, sport: str | None) -> dict:
    """Cheap upfront .count() queries (no row materialization) for the "counts" metadata block
    at the top of the file - the same filtered scope each _write_X function below uses, built
    from the same querysets so the two can never drift apart."""
    return {
        "activities": _activities_qs(athlete_id, sport).count(),
        "races": _races_qs(athlete_id, sport).count(),
        "workouts": _workouts_qs(athlete_id, sport).count(),
        "scheduled_workouts": _scheduled_workouts_qs(athlete_id, sport).count(),
        # Equipment is always exported in full, regardless of the sport filter - it's small,
        # and shoes have no sport of their own to filter by.
        "bikes": _bikes_qs(athlete_id).count(),
        "shoes": _shoes_qs(athlete_id).count(),
        "components": _components_qs(athlete_id).count(),
    }


def _write_equipment(gz: gzip.GzipFile, athlete_id: str) -> None:
    gz.write(b'{"bikes":')
    _write_array(gz, (BikeSerializer(b).data for b in _bikes_qs(athlete_id)))

    gz.write(b',"shoes":')
    shoes = _shoes_qs(athlete_id).select_related("shoe_model_version__shoe_model")
    _write_array(gz, (ShoeSerializer(s).data for s in shoes))

    gz.write(b',"components":')
    _write_array(gz, (ComponentSerializer(c).data for c in _components_qs(athlete_id)))
    gz.write(b"}")


def _write_workouts(gz: gzip.GzipFile, athlete_id: str, sport: str | None) -> None:
    qs = _workouts_qs(athlete_id, sport).prefetch_related("steps")
    _write_array(gz, (WorkoutDetailSerializer(w).data for w in qs))


def _write_activities(gz: gzip.GzipFile, athlete_id: str, sport: str | None) -> None:
    gz.write(b"[")
    first = True
    for activity in _activities_qs(athlete_id, sport).iterator(chunk_size=200):
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
    _write_array(gz, (RaceSerializer(r).data for r in _races_qs(athlete_id, sport)))


def _write_scheduled_workouts(gz: gzip.GzipFile, athlete_id: str, sport: str | None) -> None:
    _write_array(gz, (ScheduledWorkoutSerializer(s).data for s in _scheduled_workouts_qs(athlete_id, sport)))


def _ensure_parent_dir(relative_path: str) -> str:
    full_path = default_storage.path(relative_path)
    os.makedirs(os.path.dirname(full_path), exist_ok=True)
    return full_path


def write_export(athlete_id: str, sport: str | None, relative_path: str, on_step: OnStep | None = None) -> int:
    """Writes the gzip file to local disk (via default_storage's path resolution - this
    project's MEDIA_ROOT is local disk, which is what makes true incremental streaming
    writes possible; see the module docstring). Returns the file size in bytes.

    Field order matches Java's ExportWriter.write exactly: generated_at/athlete_id/counts
    first (counts is a handful of cheap .count() queries, computed upfront so a reader can see
    what the file contains without having to fully parse it), then equipment and workouts
    (which activities reference) before activities, activities (which races/scheduled_workouts
    reference) before those - dependency order, in case any consumer relies on it. `on_step`
    (if given) is called once per section, right as it starts - see ExportJob.current_step
    (models.py's DATA_TRANSFER_STEPS) for the fixed 5-step order this walks through. There's
    no finer-grained progress within "activities" (by far the slowest section, since it walks
    laps + full-resolution streams per activity) - a per-activity callback would mean either an
    upfront .count() query or DB writes on every single activity, and a coarse "which section"
    indicator already answers what a progress dialog needs.
    """
    full_path = _ensure_parent_dir(relative_path)
    with gzip.GzipFile(full_path, mode="wb") as gz:
        gz.write(b'{"generated_at":')
        gz.write(_dump(timezone.now().isoformat().replace("+00:00", "Z")))
        gz.write(b',"athlete_id":')
        gz.write(_dump(athlete_id))
        gz.write(b',"counts":')
        gz.write(_dump(_counts(athlete_id, sport)))

        if on_step:
            on_step("equipment")
        gz.write(b',"equipment":')
        _write_equipment(gz, athlete_id)

        if on_step:
            on_step("workouts")
        gz.write(b',"workouts":')
        _write_workouts(gz, athlete_id, sport)

        if on_step:
            on_step("activities")
        gz.write(b',"activities":')
        _write_activities(gz, athlete_id, sport)

        if on_step:
            on_step("races")
        gz.write(b',"races":')
        _write_races(gz, athlete_id, sport)

        if on_step:
            on_step("scheduled_workouts")
        gz.write(b',"scheduled_workouts":')
        _write_scheduled_workouts(gz, athlete_id, sport)

        gz.write(b"}")
    return os.path.getsize(full_path)
