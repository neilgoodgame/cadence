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
OnTotal = Callable[[int], None]
OnProgress = Callable[[int], None]

# Persisting after every single item would mean one DB write per row in a multi-thousand-
# activity export - throttle to every 20 items (still gives a smooth-looking bar).
_PROGRESS_INTERVAL = 20


class _Progress:
    """Wraps an `on_progress` callback with throttling. `flush()` bypasses the throttle -
    called at section boundaries so the persisted count never drifts far behind reality
    (otherwise a section with, say, 7 items never fires at all, since 7 % 20 != 0)."""

    def __init__(self, on_progress: OnProgress | None):
        self._on_progress = on_progress
        self.count = 0

    def tick(self) -> None:
        self.count += 1
        if self._on_progress and self.count % _PROGRESS_INTERVAL == 0:
            self._on_progress(self.count)

    def flush(self) -> None:
        if self._on_progress:
            self._on_progress(self.count)


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


def _write_array(gz: gzip.GzipFile, items: Any, progress: "_Progress | None" = None) -> None:
    gz.write(b"[")
    first = True
    for item in items:
        if not first:
            gz.write(b",")
        first = False
        gz.write(_dump(item))
        if progress:
            progress.tick()
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


def _write_equipment(gz: gzip.GzipFile, athlete_id: str, progress: "_Progress | None" = None) -> None:
    gz.write(b'{"bikes":')
    _write_array(gz, (BikeSerializer(b).data for b in _bikes_qs(athlete_id)), progress)

    gz.write(b',"shoes":')
    shoes = _shoes_qs(athlete_id).select_related("shoe_model_version__shoe_model")
    _write_array(gz, (ShoeSerializer(s).data for s in shoes), progress)

    gz.write(b',"components":')
    _write_array(gz, (ComponentSerializer(c).data for c in _components_qs(athlete_id)), progress)
    gz.write(b"}")


def _write_workouts(gz: gzip.GzipFile, athlete_id: str, sport: str | None, progress: "_Progress | None" = None) -> None:
    qs = _workouts_qs(athlete_id, sport).prefetch_related("steps")
    _write_array(gz, (WorkoutDetailSerializer(w).data for w in qs), progress)


# Deliberately never exported: transient, in-app-only bookkeeping (a pending threshold-increase
# suggestion, and whether detection has ever run) rather than data describing the activity
# itself - re-importing a file shouldn't resurrect an already-accepted-or-dismissed suggestion on
# a brand-new row, and a freshly-imported row should start out "not yet checked" like any other
# row import creates, not inherit whatever was true in the source account. ftp_snapshot/
# critical_run_power_snapshot/threshold_pace_snapshot ARE exported (ActivitySerializer already
# includes them) - those describe what actually happened, same as every other stat field.
_TRANSIENT_ACTIVITY_FIELDS = (
    "suggested_ftp",
    "suggested_critical_run_power",
    "suggested_threshold_pace",
    "threshold_checked",
)


def _write_activities(
    gz: gzip.GzipFile, athlete_id: str, sport: str | None, progress: "_Progress | None" = None
) -> None:
    gz.write(b"[")
    first = True
    for activity in _activities_qs(athlete_id, sport).iterator(chunk_size=200):
        if not first:
            gz.write(b",")
        first = False
        activity_data = ActivitySerializer(activity).data
        for field in _TRANSIENT_ACTIVITY_FIELDS:
            activity_data.pop(field, None)
        entry = {
            "activity": activity_data,
            "laps": LapSerializer(Lap.objects.filter(activity=activity), many=True).data,
            "streams": _build_streams(activity),
        }
        gz.write(_dump(entry))
        if progress:
            progress.tick()
    gz.write(b"]")


def _write_races(gz: gzip.GzipFile, athlete_id: str, sport: str | None, progress: "_Progress | None" = None) -> None:
    _write_array(gz, (RaceSerializer(r).data for r in _races_qs(athlete_id, sport)), progress)


def _write_scheduled_workouts(
    gz: gzip.GzipFile, athlete_id: str, sport: str | None, progress: "_Progress | None" = None
) -> None:
    _write_array(gz, (ScheduledWorkoutSerializer(s).data for s in _scheduled_workouts_qs(athlete_id, sport)), progress)


def _ensure_parent_dir(relative_path: str) -> str:
    full_path = default_storage.path(relative_path)
    os.makedirs(os.path.dirname(full_path), exist_ok=True)
    return full_path


def write_export(
    athlete_id: str,
    sport: str | None,
    relative_path: str,
    on_step: OnStep | None = None,
    on_total: OnTotal | None = None,
    on_progress: OnProgress | None = None,
) -> int:
    """Writes the gzip file to local disk (via default_storage's path resolution - this
    project's MEDIA_ROOT is local disk, which is what makes true incremental streaming
    writes possible; see the module docstring). Returns the file size in bytes.

    Field order matches Java's ExportWriter.write exactly: generated_at/athlete_id/counts
    first (counts is a handful of cheap .count() queries, computed upfront so a reader can see
    what the file contains without having to fully parse it), then equipment and workouts
    (which activities reference) before activities, activities (which races/scheduled_workouts
    reference) before those - dependency order, in case any consumer relies on it. `on_step`
    (if given) is called once per section, right as it starts. `on_total`/`on_progress` (if
    given) turn that into a fine-grained item-level progress bar *scoped to the current
    section* - e.g. while `on_step` is "activities", `on_total`/`on_progress` report "how many
    of this athlete's activities have been written", not a blended count across every kind of
    data. `on_total` fires once per section, right after `on_step`, with that section's own
    count from counts.py's .count() queries (the same ones the "counts" file field already
    computes - so this is free, no second query); `on_progress` fires with a running count
    *reset to 0 at the start of each section* as each item in it is written, throttled (see
    _Progress) so a multi-thousand-row export doesn't turn into a DB write per row. "equipment"
    covers bikes+shoes+components combined (they're one section/one on_step call), so its total
    is the sum of those three.
    """
    full_path = _ensure_parent_dir(relative_path)
    with gzip.GzipFile(full_path, mode="wb") as gz:
        gz.write(b'{"generated_at":')
        gz.write(_dump(timezone.now().isoformat().replace("+00:00", "Z")))
        gz.write(b',"athlete_id":')
        gz.write(_dump(athlete_id))
        counts = _counts(athlete_id, sport)
        gz.write(b',"counts":')
        gz.write(_dump(counts))

        def start_section(step: str, total: int) -> "_Progress":
            if on_step:
                on_step(step)
            if on_total:
                on_total(total)
            return _Progress(on_progress)

        progress = start_section("equipment", counts["bikes"] + counts["shoes"] + counts["components"])
        gz.write(b',"equipment":')
        _write_equipment(gz, athlete_id, progress)
        progress.flush()

        progress = start_section("workouts", counts["workouts"])
        gz.write(b',"workouts":')
        _write_workouts(gz, athlete_id, sport, progress)
        progress.flush()

        progress = start_section("activities", counts["activities"])
        gz.write(b',"activities":')
        _write_activities(gz, athlete_id, sport, progress)
        progress.flush()

        progress = start_section("races", counts["races"])
        gz.write(b',"races":')
        _write_races(gz, athlete_id, sport, progress)
        progress.flush()

        progress = start_section("scheduled_workouts", counts["scheduled_workouts"])
        gz.write(b',"scheduled_workouts":')
        _write_scheduled_workouts(gz, athlete_id, sport, progress)
        progress.flush()

        gz.write(b"}")
    return os.path.getsize(full_path)
