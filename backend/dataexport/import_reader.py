"""Reads one athlete's exported data file back in, creating brand-new rows with fresh ids
(an import never updates or matches existing data - re-importing the same file twice
creates duplicates, same policy as the Java backend).

Mirrors backend_java's ImportReader (backend_java/src/main/java/com/cadence/api/imports/
ImportReader.java) section for section - equipment and workouts first (activities
reference them), activities next (races/scheduled_workouts reference them), then races
and scheduled_workouts, with the same deferred-link trick for multisport parent/primary
activity references that may point at an activity appearing later in the same array.

Streaming approach: ijson.items() can't be chained against one shared stream - each call
needs to see its target array from a stream position it recognizes, and one exhausted
generator does not reliably leave the underlying stream positioned exactly where the next
one expects (verified directly - chaining raised IncompleteJSONError partway through the
second section). Instead each section below re-opens a fresh gzip stream over the same
staged file and does its own independent full pass with ijson.items(fresh_stream, prefix),
skipping everything outside its own prefix cheaply. The file is only ever staged on local
disk once; a handful of re-reads of it is a trivial cost next to the DB writes either way.

Every ijson.items() call passes use_float=True - found the hard way against a real
2,615-activity/22-workout export: ijson's default is to parse JSON floats as Decimal, not
float, for precision. Field assignment into a Django FloatField tolerates that fine (the DB
adapter calls float() on it), but workouts/calculations.py's compute_duration_and_tss does
raw Python arithmetic on step target values, and float * Decimal raises TypeError - every
single workout silently landed in items_skipped until this was traced down.
"""

import datetime
import gzip
import logging
from collections.abc import Callable
from typing import Any

import ijson
from django.core.files.storage import default_storage
from django.utils.dateparse import parse_date, parse_datetime, parse_duration

from activities.models import Activity, ActivityTag, Lap, Record, Tag
from athletes.models import ThresholdHistory
from gear.models import Bike, Component, Shoe, ShoeModel, ShoeModelVersion
from races.models import Race
from scheduling.models import ScheduledWorkout
from workouts.calculations import compute_chart_preview, compute_duration_and_tss
from workouts.models import Workout, WorkoutStep

logger = logging.getLogger(__name__)

OnStep = Callable[[str], None]
OnTotal = Callable[[int], None]
OnProgress = Callable[[int], None]

# Persisting after every single item would mean one DB write per row in a multi-thousand-
# activity import - throttle to every 20 items (still gives a smooth-looking bar).
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


COUNT_KEYS = [
    "activities_imported",
    "races_imported",
    "workouts_imported",
    "scheduled_workouts_imported",
    "bikes_imported",
    "shoes_imported",
    "components_imported",
    "threshold_history_imported",
    "items_skipped",
]


def _stream(stored_path: str) -> gzip.GzipFile:
    return gzip.GzipFile(fileobj=default_storage.open(stored_path, "rb"), mode="rb")


def _read_counts(stored_path: str) -> dict | None:
    """Peeks at the "counts" metadata block near the top of the file (see export_writer.py's
    _counts) for the upfront per-category totals - a file exported before that field existed
    has no "counts" key, in which case there's nothing to report and the caller skips on_total."""
    with _stream(stored_path) as gz:
        return next(ijson.items(gz, "counts", use_float=True), None)


def _find_or_create_shoe_model_version(manufacturer: str, model: str, version: str) -> ShoeModelVersion:
    # .filter(...).first(), not .get() - this catalog is shared across every athlete with no
    # uniqueness constraint on (manufacturer, model), so more than one match is a real
    # possibility. The Java backend's first version used a query that assumed uniqueness and
    # threw the first time two rows legitimately matched (found via a large-account test, not
    # a unit test) - don't rediscover that bug here.
    shoe_model = ShoeModel.objects.filter(manufacturer=manufacturer, model=model).first()
    if shoe_model is None:
        shoe_model = ShoeModel.objects.create(manufacturer=manufacturer, model=model)
    smv = ShoeModelVersion.objects.filter(shoe_model=shoe_model, version=version).first()
    if smv is None:
        smv = ShoeModelVersion.objects.create(shoe_model=shoe_model, version=version)
    return smv


def _import_equipment(
    athlete_id: str,
    stored_path: str,
    bikes_by_old_id: dict[str, str],
    shoes_by_old_id: dict[str, str],
    counts: dict,
    progress: _Progress,
) -> None:
    with _stream(stored_path) as gz:
        for bike in ijson.items(gz, "equipment.bikes.item", use_float=True):
            try:
                created_bike = Bike.objects.create(
                    athlete_id=athlete_id,
                    name=bike["name"],
                    kind=bike.get("kind") or "",
                    groupset=bike.get("groupset") or "",
                    distance_km=bike.get("distance_km") or 0,
                )
                bikes_by_old_id[bike["id"]] = created_bike.id
                counts["bikes_imported"] += 1
            except Exception:
                logger.warning("Skipped bike import (old id %s)", bike.get("id"), exc_info=True)
                counts["items_skipped"] += 1
            progress.tick()

    with _stream(stored_path) as gz:
        for shoe in ijson.items(gz, "equipment.shoes.item", use_float=True):
            try:
                smv = _find_or_create_shoe_model_version(shoe["manufacturer"], shoe["model"], shoe.get("version") or "")
                created_shoe = Shoe.objects.create(
                    athlete_id=athlete_id,
                    shoe_model_version=smv,
                    colourway=shoe.get("colourway") or "",
                    name=shoe["name"],
                    image=shoe.get("image"),
                    role=shoe.get("role") or "",
                    limit_km=shoe.get("limit_km") or 0,
                )
                # `since` is auto_now_add=True - a plain create()/save() silently ignores any
                # value passed for it, always stamping "now" instead. .update() bypasses the
                # model's pre_save machinery (it's a straight SQL UPDATE), so it's the only way
                # to actually preserve the original date.
                if shoe.get("since"):
                    Shoe.objects.filter(pk=created_shoe.pk).update(since=shoe["since"])
                shoes_by_old_id[shoe["id"]] = created_shoe.id
                counts["shoes_imported"] += 1
            except Exception:
                logger.warning("Skipped shoe import (old id %s)", shoe.get("id"), exc_info=True)
                counts["items_skipped"] += 1
            progress.tick()

    with _stream(stored_path) as gz:
        for component in ijson.items(gz, "equipment.components.item", use_float=True):
            bike_id = bikes_by_old_id.get(component.get("bike_id"))
            if not bike_id:
                counts["items_skipped"] += 1
                progress.tick()
                continue
            try:
                Component.objects.create(
                    bike_id=bike_id,
                    name=component["name"],
                    km=component.get("km") or 0,
                    limit_km=component["limit_km"],
                    model=component.get("model") or "",
                )
                counts["components_imported"] += 1
            except Exception:
                logger.warning("Skipped component import (old id %s)", component.get("id"), exc_info=True)
                counts["items_skipped"] += 1
            progress.tick()


def _create_steps(workout: Workout, steps: list[dict[str, Any]], parent: WorkoutStep | None = None) -> None:
    # Mirrors workouts/views.py's _create_steps - not imported directly since that's a
    # module-private helper of the views module, not shared app infrastructure. Input here is
    # already-exported data (from this backend or Java's), not unclean user input, so it's
    # created directly rather than round-tripped through clean_step_tree's validation.
    for index, step in enumerate(steps):
        row = WorkoutStep.objects.create(
            workout=workout,
            parent=parent,
            order=index,
            kind=step["kind"],
            end_type=step.get("end_type") or "",
            duration=step.get("duration"),
            distance=step.get("distance"),
            target_type=step.get("target_type") or "",
            target_low=step.get("target_low"),
            target_high=step.get("target_high"),
            target2_type=step.get("target2_type") or "none",
            target2_low=step.get("target2_low"),
            target2_high=step.get("target2_high"),
            repeat=step.get("repeat") or 1,
            note=step.get("note") or "",
        )
        if step["kind"] == "repeat":
            _create_steps(workout, step.get("children") or [], parent=row)


def _attach_tag(activity: Activity, athlete_id: str, name: str) -> None:
    # Mirrors ActivityTagView.post's find-or-create-by-name path (activities/views.py) - that
    # logic isn't factored into shared app infrastructure, so it's replicated here rather than
    # importing a view.
    tag, _created = Tag.objects.get_or_create(athlete_id=athlete_id, name=name, defaults={"origin": "manual"})
    ActivityTag.objects.get_or_create(activity=activity, tag=tag)


def _import_workouts(
    athlete_id: str, stored_path: str, workouts_by_old_id: dict[str, str], counts: dict, progress: _Progress
) -> None:
    with _stream(stored_path) as gz:
        for workout in ijson.items(gz, "workouts.item", use_float=True):
            try:
                steps = workout.get("steps") or []
                created = Workout.objects.create(
                    created_by_id=athlete_id,
                    name=workout["name"],
                    sport=workout["sport"],
                    # folder_id is deliberately dropped - folders aren't part of the export
                    # scope, and any incoming id wouldn't resolve to a folder this athlete
                    # owns anyway. Matches the Java backend's ImportReader exactly.
                    folder=None,
                    tags=workout.get("tags") or [],
                )
                _create_steps(created, steps)
                duration, tss = compute_duration_and_tss(steps)
                created.duration = duration
                created.tss = tss
                created.chart_preview = compute_chart_preview(steps)
                created.save(update_fields=["duration", "tss", "chart_preview"])
                workouts_by_old_id[workout["id"]] = created.id
                counts["workouts_imported"] += 1
            except Exception:
                logger.warning("Skipped workout import (old id %s)", workout.get("id"), exc_info=True)
                counts["items_skipped"] += 1
            progress.tick()


def _build_records(activity: Activity, start_date: datetime.datetime, streams: dict) -> list[Record]:
    """Reconstructs per-second Record rows from the export's parallel-array stream shape.
    Everything except `latlng` aligns 1:1 by index with `time` - `latlng` drops points with
    no GPS fix and carries no index/time marker of its own, so pairing it against the first N
    time entries is the closest reconstruction possible without changing that shape; this can
    drift for activities with GPS dropouts (accepted, documented limitation - matches the Java
    backend's ImportReader exactly)."""
    fields = streams.get("fields") or {}
    time = fields.get("time") or []
    if not time:
        return []
    latlng = fields.get("latlng") or []
    rows = []
    for i, t in enumerate(time):
        lat = lng = None
        if i < len(latlng):
            lat, lng = latlng[i][0], latlng[i][1]
        rows.append(
            Record(
                activity=activity,
                t=t,
                ts=start_date + datetime.timedelta(seconds=t),
                power=_at(fields, "power", i),
                heartrate=_at(fields, "heartrate", i),
                cadence=_at(fields, "cadence", i),
                altitude=_at(fields, "altitude", i),
                lat=lat,
                lng=lng,
                speed=_at(fields, "speed", i),
                distance_km=_at(fields, "distance", i),
                air_temp=_at(fields, "air_temp", i),
                humidity=_at(fields, "humidity", i),
                skin_temp=_at(fields, "skin_temp", i),
                core_temp=_at(fields, "core_temp", i),
                heat_strain=_at(fields, "heat_strain", i),
            )
        )
    return rows


def _at(fields: dict, key: str, i: int) -> Any:
    values = fields.get(key)
    if not values or i >= len(values):
        return None
    return values[i]


def _import_activities(
    athlete_id: str,
    stored_path: str,
    workouts_by_old_id: dict[str, str],
    bikes_by_old_id: dict[str, str],
    shoes_by_old_id: dict[str, str],
    activity_id_map: dict[str, str],
    deferred_links: list[tuple[str, str, bool]],
    counts: dict,
    progress: _Progress,
) -> None:
    with _stream(stored_path) as gz:
        for entry in ijson.items(gz, "activities.item", use_float=True):
            ar = entry["activity"]
            try:
                # Parsed explicitly rather than left as the raw JSON string: Activity.create()
                # doesn't coerce it to a real datetime until save-time SQL adapting, so
                # `activity.start_date` right after create() could still be the string -
                # _build_records needs a real datetime.timedelta-addable value.
                start_date = parse_datetime(ar["start_date"])
                if start_date is None:
                    raise ValueError(f"Unparseable start_date: {ar['start_date']!r}")

                activity = Activity.objects.create(
                    athlete_id=athlete_id,
                    sport=ar["sport"],
                    environment=ar.get("environment") or "outdoor",
                    has_gps=ar.get("has_gps") or False,
                    name=ar["name"],
                    start_date=start_date,
                    source=ar.get("source") or "",
                    device=ar.get("device") or "",
                    moving_time=ar.get("moving_time") or 0,
                    distance_km=ar.get("distance_km") or 0,
                    distance_source=ar.get("distance_source") or "gps",
                    avg_power=ar.get("avg_power"),
                    norm_power=ar.get("norm_power"),
                    intensity=ar.get("intensity"),
                    tss=ar.get("tss") or 0,
                    avg_hr=ar.get("avg_hr"),
                    max_hr=ar.get("max_hr"),
                    ascent=ar.get("ascent"),
                    max_power=ar.get("max_power"),
                    avg_cadence=ar.get("avg_cadence"),
                    max_cadence=ar.get("max_cadence"),
                    max_speed=ar.get("max_speed"),
                    total_descent=ar.get("total_descent"),
                    elevation_min=ar.get("elevation_min"),
                    elevation_max=ar.get("elevation_max"),
                    calories=ar.get("calories"),
                    trimp=ar.get("trimp"),
                    avg_left_balance_pct=ar.get("avg_left_balance_pct"),
                    start_weight_kg=ar.get("start_weight_kg"),
                    end_weight_kg=ar.get("end_weight_kg"),
                    fluids_ml=ar.get("fluids_ml"),
                    avg_air_temp=ar.get("avg_air_temp"),
                    avg_humidity=ar.get("avg_humidity"),
                    aerobic_training_effect=ar.get("aerobic_training_effect"),
                    anaerobic_training_effect=ar.get("anaerobic_training_effect"),
                    training_effect_label=ar.get("training_effect_label") or "",
                    workout_id=workouts_by_old_id.get(ar.get("workout_id")),
                    bike_id=bikes_by_old_id.get(ar.get("bike_id")),
                    shoe_id=shoes_by_old_id.get(ar.get("shoe_id")),
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
                        for lap in entry.get("laps") or []
                    ]
                )

                records = _build_records(activity, start_date, entry.get("streams") or {})
                if records:
                    Record.objects.bulk_create(records, batch_size=5000)

                for tag_name in ar.get("tags") or []:
                    _attach_tag(activity, athlete_id, tag_name)

                activity_id_map[ar["id"]] = activity.id
                if ar.get("parent_activity_id"):
                    deferred_links.append((activity.id, ar["parent_activity_id"], True))
                if ar.get("primary_activity_id"):
                    deferred_links.append((activity.id, ar["primary_activity_id"], False))
                counts["activities_imported"] += 1
            except Exception:
                logger.warning("Skipped activity import (old id %s)", ar.get("id"), exc_info=True)
                counts["items_skipped"] += 1
            progress.tick()


def _resolve_deferred_links(deferred_links: list[tuple[str, str, bool]], activity_id_map: dict[str, str]) -> None:
    for new_child_id, old_target_id, is_parent in deferred_links:
        new_target_id = activity_id_map.get(old_target_id)
        if not new_target_id:
            continue  # the referenced activity wasn't part of this import
        if is_parent:
            Activity.objects.filter(pk=new_child_id).update(parent_activity_id=new_target_id)
        else:
            Activity.objects.filter(pk=new_child_id).update(primary_activity_id=new_target_id)


def _import_races(
    athlete_id: str, stored_path: str, activity_id_map: dict[str, str], counts: dict, progress: _Progress
) -> None:
    with _stream(stored_path) as gz:
        for race in ijson.items(gz, "races.item", use_float=True):
            try:
                Race.objects.create(
                    athlete_id=athlete_id,
                    name=race["name"],
                    date=race["date"],
                    sport=race.get("sport") or "",
                    distance_km=race.get("distance_km"),
                    # parse_duration, not the raw "H:MM:SS"/"HH:MM:SS" string - both this and
                    # the Java backend's writer are lenient about the hour digit count on
                    # output, but DurationField's DB adapter expects an actual timedelta.
                    goal_time=parse_duration(race["goal_time"]) if race.get("goal_time") else None,
                    result_time=parse_duration(race["result_time"]) if race.get("result_time") else None,
                    activity_id=activity_id_map.get(race.get("activity_id")),
                    url=race.get("url"),
                    results_url=race.get("results_url"),
                    notes=race.get("notes") or "",
                )
                counts["races_imported"] += 1
            except Exception:
                logger.warning("Skipped race import (old id %s)", race.get("id"), exc_info=True)
                counts["items_skipped"] += 1
            progress.tick()


def _import_scheduled_workouts(
    athlete_id: str,
    stored_path: str,
    workouts_by_old_id: dict[str, str],
    activity_id_map: dict[str, str],
    counts: dict,
    progress: _Progress,
) -> None:
    with _stream(stored_path) as gz:
        for scheduled in ijson.items(gz, "scheduled_workouts.item", use_float=True):
            workout_id = workouts_by_old_id.get(scheduled.get("workout_id"))
            if not workout_id:
                counts["items_skipped"] += 1
                progress.tick()
                continue
            try:
                activity_id = activity_id_map.get(scheduled.get("activity_id"))
                # A MISSED original status has no direct equivalent worth reconstructing here -
                # it comes back "planned" (or "completed" if it had a matched activity), a
                # minor accepted gap matching the Java backend's ImportReader.
                ScheduledWorkout.objects.create(
                    workout_id=workout_id,
                    athlete_id=athlete_id,
                    date=scheduled["date"],
                    time_of_day=scheduled.get("time_of_day") or "",
                    status="completed" if activity_id else "planned",
                    activity_id=activity_id,
                )
                counts["scheduled_workouts_imported"] += 1
            except Exception:
                logger.warning("Skipped scheduled workout import (old id %s)", scheduled.get("id"), exc_info=True)
                counts["items_skipped"] += 1
            progress.tick()


def _import_threshold_history(
    athlete_id: str, stored_path: str, activity_id_map: dict[str, str], counts: dict, progress: _Progress
) -> None:
    """Carries over the source athlete's own threshold-history ledger, re-linked to the newly-
    created activity ids - the *only* way an imported activity's own zones stay historically
    accurate now that there's no per-activity snapshot column to fall back to (see
    export_writer.py's _write_threshold_history). Deliberately does NOT run the windowed
    recompute (athletes/threshold_history.py::recompute_for_activity) against these activities
    or touch the importing athlete's own live profile/ledger - an imported activity's own zones
    should reflect what was actually true for that effort when it happened, not get re-derived
    against a possibly-unrelated athlete's current fitness. A file exported before this feature
    existed has no "threshold_history" section at all - those activities simply end up with no
    history entries (zones with no activity-scoped reference), same graceful degradation as any
    other schema-evolution gap in this reader.
    """
    entries: list[ThresholdHistory] = []
    with _stream(stored_path) as gz:
        for row in ijson.items(gz, "threshold_history.item", use_float=True):
            new_activity_id = activity_id_map.get(row.get("source_activity_id"))
            effective_from = parse_date(row["effective_from"]) if row.get("effective_from") else None
            if not new_activity_id or effective_from is None:
                # The source activity failed to import (or the row is malformed) - skip rather
                # than create a dangling reference.
                counts["items_skipped"] += 1
                progress.tick()
                continue
            entries.append(
                ThresholdHistory(
                    athlete_id=athlete_id,
                    field=row["field"],
                    value_numeric=row.get("value_numeric"),
                    value_pace=row.get("value_pace") or "",
                    source_activity_id=new_activity_id,
                    effective_from=effective_from,
                )
            )
            counts["threshold_history_imported"] += 1
            progress.tick()
    ThresholdHistory.objects.bulk_create(entries, batch_size=500)


def read_import(
    athlete_id: str,
    stored_path: str,
    on_step: OnStep | None = None,
    on_total: OnTotal | None = None,
    on_progress: OnProgress | None = None,
) -> dict[str, int]:
    """`on_step` (if given) is called once per section, right as it starts - see
    ImportJob.current_step (models.py's DATA_TRANSFER_STEPS) for the fixed 6-step order this
    walks through, same as export_writer.write_export's on_step. `on_total`/`on_progress` (if
    given) turn that into a fine-grained item-level progress bar *scoped to the current
    section*, mirroring write_export's: `on_total` fires once per section, right after on_step,
    with that section's own count read from the file's own "counts" metadata block (_read_counts)
    - a file exported before that field existed has no "counts" key, in which case on_total is
    simply never called. `on_progress` fires with a running count *reset to 0 at the start of
    each section* as each item in it is processed (imported or skipped - either way the reader
    has moved past it), throttled (see _Progress). "equipment" covers bikes+shoes+components
    combined (one section/one on_step call), so its total is the sum of those three."""
    counts = dict.fromkeys(COUNT_KEYS, 0)
    bikes_by_old_id: dict[str, str] = {}
    shoes_by_old_id: dict[str, str] = {}
    workouts_by_old_id: dict[str, str] = {}
    activity_id_map: dict[str, str] = {}
    deferred_links: list[tuple[str, str, bool]] = []
    file_counts = _read_counts(stored_path) if on_total else None

    def start_section(step: str, total_key: str | None) -> "_Progress":
        if on_step:
            on_step(step)
        if on_total and file_counts is not None:
            total = (
                file_counts["bikes"] + file_counts["shoes"] + file_counts["components"]
                if total_key is None
                # .get(..., 0), not [...]: a file exported before the "threshold_history"
                # section existed has a "counts" block without that key.
                else file_counts.get(total_key, 0)
            )
            on_total(total)
        return _Progress(on_progress)

    progress = start_section("equipment", None)
    _import_equipment(athlete_id, stored_path, bikes_by_old_id, shoes_by_old_id, counts, progress)
    progress.flush()

    progress = start_section("workouts", "workouts")
    _import_workouts(athlete_id, stored_path, workouts_by_old_id, counts, progress)
    progress.flush()

    progress = start_section("activities", "activities")
    _import_activities(
        athlete_id,
        stored_path,
        workouts_by_old_id,
        bikes_by_old_id,
        shoes_by_old_id,
        activity_id_map,
        deferred_links,
        counts,
        progress,
    )
    progress.flush()

    progress = start_section("races", "races")
    _import_races(athlete_id, stored_path, activity_id_map, counts, progress)
    progress.flush()

    progress = start_section("scheduled_workouts", "scheduled_workouts")
    _import_scheduled_workouts(athlete_id, stored_path, workouts_by_old_id, activity_id_map, counts, progress)
    progress.flush()
    _resolve_deferred_links(deferred_links, activity_id_map)

    progress = start_section("threshold_history", "threshold_history")
    _import_threshold_history(athlete_id, stored_path, activity_id_map, counts, progress)
    progress.flush()

    return counts
