import gzip
import json
from datetime import UTC, date, datetime

from django.core.files.uploadedfile import SimpleUploadedFile
from django.test import TestCase
from rest_framework.test import APIClient

from accounts.models import User
from activities.models import Activity, Lap, Record
from athletes.models import ThresholdHistory
from authn.oauth_utils import issue_token_pair
from gear.models import Bike, Component, Shoe, ShoeModel, ShoeModelVersion
from races.models import Race
from scheduling.models import ScheduledWorkout
from workouts.models import Workout, WorkoutStep

from .export_writer import write_export
from .import_reader import read_import
from .models import DATA_TRANSFER_STEPS


def _bearer_client(user, scope="activities:read activities:write coach"):
    # A local copy, not a cross-app import - matches the existing convention of each app
    # having its own (uploads/tests/helpers.py, activities/tests/helpers.py both do the same).
    access_token, _ = issue_token_pair(user, scope=scope)
    client = APIClient()
    client.credentials(HTTP_AUTHORIZATION=f"Bearer {access_token.token}")
    return client


def _make_activity(athlete: User, **kwargs) -> Activity:
    defaults = {
        "sport": "run",
        "environment": "outdoor",
        "name": "Activity",
        "start_date": datetime(2026, 1, 1, 7, 0, tzinfo=UTC),
        "moving_time": 1800,
        "distance_km": 5.0,
        "tss": 50,
    }
    defaults.update(kwargs)
    return Activity.objects.create(athlete=athlete, **defaults)


def _seed_full_account(athlete: User) -> dict:
    bike = Bike.objects.create(athlete=athlete, name="Road bike", kind="road")
    Component.objects.create(bike=bike, name="Chain", limit_km=3000)

    shoe_model = ShoeModel.objects.create(manufacturer="ImportTestCo", model="Roundtrip")
    smv = ShoeModelVersion.objects.create(shoe_model=shoe_model, version="v1")
    Shoe.objects.create(athlete=athlete, shoe_model_version=smv, name="Daily trainer")

    workout = Workout.objects.create(created_by=athlete, name="VO2 intervals", sport="run")
    WorkoutStep.objects.create(workout=workout, order=0, kind="block", end_type="time", duration=300, target_type="hr")

    parent = _make_activity(
        athlete, sport="multisport", name="Triathlon", start_date=datetime(2026, 1, 1, 7, 0, tzinfo=UTC)
    )
    child = _make_activity(
        athlete,
        sport="bike",
        name="Triathlon Bike Leg",
        start_date=datetime(2026, 1, 1, 8, 0, tzinfo=UTC),
        parent_activity=parent,
        workout=workout,
    )
    Lap.objects.create(activity=child, index=0, duration=600, distance_km=5.0)
    Record.objects.create(activity=child, t=0, ts=child.start_date, power=200, heartrate=140)

    Race.objects.create(athlete=athlete, name="Local 10k", date=date(2026, 1, 1), sport="bike", activity=child)
    ScheduledWorkout.objects.create(workout=workout, athlete=athlete, date=date(2026, 1, 1), activity=child)

    threshold_entry = ThresholdHistory.objects.create(
        athlete=athlete, field="ftp", value_numeric=250, source_activity=child, effective_from=date(2026, 1, 1)
    )

    return {"bike": bike, "workout": workout, "parent": parent, "child": child, "threshold_entry": threshold_entry}


class ExportImportRoundTripTests(TestCase):
    def setUp(self):
        self.source = User.objects.create_user(email="export-source@example.cc", password="x", name="Source")
        self.target = User.objects.create_user(email="export-target@example.cc", password="x", name="Target")
        _seed_full_account(self.source)

    def test_export_then_import_recreates_data_with_remapped_ids(self):
        source_client = _bearer_client(self.source)
        start = source_client.post("/v1/export")
        self.assertEqual(start.status_code, 202)
        export_id = start.json()["id"]

        status = source_client.get(f"/v1/export/{export_id}").json()
        self.assertEqual(status["status"], "ready")

        download = source_client.get(f"/v1/export/{export_id}/download")
        content = b"".join(download.streaming_content)

        target_client = _bearer_client(self.target)
        upload = target_client.post(
            "/v1/import", {"file": SimpleUploadedFile("export.json.gz", content)}, format="multipart"
        )
        self.assertEqual(upload.status_code, 202)
        import_id = upload.json()["id"]

        result = target_client.get(f"/v1/import/{import_id}").json()
        self.assertEqual(result["status"], "ready")
        counts = result["counts"]
        self.assertEqual(counts["activities_imported"], 2)
        self.assertEqual(counts["races_imported"], 1)
        self.assertEqual(counts["workouts_imported"], 1)
        self.assertEqual(counts["scheduled_workouts_imported"], 1)
        self.assertEqual(counts["bikes_imported"], 1)
        self.assertEqual(counts["shoes_imported"], 1)
        self.assertEqual(counts["components_imported"], 1)
        self.assertEqual(counts["threshold_history_imported"], 1)
        self.assertEqual(counts["items_skipped"], 0)

        imported_child = Activity.objects.get(athlete=self.target, name="Triathlon Bike Leg")
        imported_parent = Activity.objects.get(athlete=self.target, name="Triathlon")
        self.assertNotEqual(imported_child.id, self.child_id())
        self.assertEqual(imported_child.parent_activity_id, imported_parent.id)
        self.assertIsNotNone(imported_child.workout_id)
        self.assertEqual(Lap.objects.filter(activity=imported_child).count(), 1)
        self.assertEqual(Record.objects.filter(activity=imported_child).count(), 1)

        imported_threshold_entry = ThresholdHistory.objects.get(athlete=self.target)
        self.assertEqual(imported_threshold_entry.field, "ftp")
        self.assertEqual(imported_threshold_entry.value_numeric, 250)
        self.assertEqual(imported_threshold_entry.source_activity_id, imported_child.id)
        self.assertEqual(imported_threshold_entry.effective_from, date(2026, 1, 1))

        imported_race = Race.objects.get(athlete=self.target)
        self.assertEqual(imported_race.activity_id, imported_child.id)

        imported_scheduled = ScheduledWorkout.objects.get(athlete=self.target)
        self.assertEqual(imported_scheduled.activity_id, imported_child.id)
        self.assertEqual(imported_scheduled.status, "completed")

    def child_id(self):
        return Activity.objects.get(athlete=self.source, name="Triathlon Bike Leg").id


class ImportActivityDedupTests(TestCase):
    """Activities are deduplicated against the target athlete's existing ones by
    (date, sport, distance_km, moving_time) before creating a row - re-importing an athlete's
    own already-existing data (e.g. testing the export/import round trip against a live
    account) shouldn't duplicate every activity. Found live: exactly this corrupted a real
    account's activity history.

    Deliberately seeds only plain activities, not _seed_full_account's full equipment/workout
    set - re-importing a shoe/bike the athlete already has hits an unrelated, pre-existing gap
    (equipment import isn't savepoint-isolated per item, so a unique-constraint conflict
    aborts the whole transaction) that's out of scope for this fix."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="dedup-target@example.cc", password="x", name="Dedup")
        self.run = _make_activity(
            self.athlete,
            sport="run",
            name="Morning run",
            start_date=datetime(2026, 1, 1, 7, 0, tzinfo=UTC),
            moving_time=1800,
            distance_km=5.0,
        )
        self.ride = _make_activity(
            self.athlete,
            sport="bike",
            name="Evening ride",
            start_date=datetime(2026, 1, 2, 18, 0, tzinfo=UTC),
            moving_time=3600,
            distance_km=30.0,
        )

    def _export_then_import_into_self(self) -> dict:
        client = _bearer_client(self.athlete)
        start = client.post("/v1/export")
        export_id = start.json()["id"]
        client.get(f"/v1/export/{export_id}")
        download = client.get(f"/v1/export/{export_id}/download")
        content = b"".join(download.streaming_content)

        upload = client.post("/v1/import", {"file": SimpleUploadedFile("export.json.gz", content)}, format="multipart")
        import_id = upload.json()["id"]
        result = client.get(f"/v1/import/{import_id}").json()
        self.assertEqual(result["status"], "ready")
        return result["counts"]

    def test_reimporting_the_same_athletes_own_export_does_not_duplicate_activities(self):
        before_count = Activity.objects.filter(athlete=self.athlete).count()

        counts = self._export_then_import_into_self()

        self.assertEqual(counts["activities_imported"], 0)
        self.assertEqual(counts["items_skipped"], 2)  # both matched an existing activity
        self.assertEqual(Activity.objects.filter(athlete=self.athlete).count(), before_count)

    def test_an_activity_that_no_longer_matches_is_reimported_not_deduped(self):
        client = _bearer_client(self.athlete)
        start = client.post("/v1/export")
        export_id = start.json()["id"]
        client.get(f"/v1/export/{export_id}")
        download = client.get(f"/v1/export/{export_id}/download")
        content = b"".join(download.streaming_content)

        # self.run's own copy changed after the export was taken - the exported (stale) version
        # of it no longer matches anything currently on the athlete's account, so it must import
        # as a new activity rather than being silently treated as a duplicate.
        self.run.distance_km = 8.0
        self.run.save(update_fields=["distance_km"])
        before_count = Activity.objects.filter(athlete=self.athlete).count()

        upload = client.post("/v1/import", {"file": SimpleUploadedFile("export.json.gz", content)}, format="multipart")
        import_id = upload.json()["id"]
        result = client.get(f"/v1/import/{import_id}").json()
        self.assertEqual(result["status"], "ready")
        counts = result["counts"]

        self.assertEqual(counts["activities_imported"], 1)  # the run - no longer matches
        self.assertEqual(counts["items_skipped"], 1)  # the ride - still matches
        self.assertEqual(Activity.objects.filter(athlete=self.athlete).count(), before_count + 1)


class ImportThresholdHistoryTests(TestCase):
    """threshold_history entries carry the source athlete's own historical values verbatim -
    imported activities' zones should reflect what was actually true when they happened, not get
    re-derived against the importing athlete's own (possibly unrelated) fitness."""

    def test_imported_activity_carries_over_the_sources_own_historical_value_unchanged(self):
        source = User.objects.create_user(email="threshold-history-source@example.cc", password="x", name="Source")
        target = User.objects.create_user(
            email="threshold-history-target@example.cc", password="x", name="Target", ftp=222
        )
        activity = _make_activity(source, sport="bike", name="Ride")
        ThresholdHistory.objects.create(
            athlete=source, field="ftp", value_numeric=250, source_activity=activity, effective_from=date(2026, 1, 1)
        )

        relative_path = "exports/test/threshold-history-roundtrip.json.gz"
        write_export(source.id, None, relative_path)
        read_import(target.id, relative_path)

        imported_activity = Activity.objects.get(athlete=target, name="Ride")
        imported_entry = ThresholdHistory.objects.get(athlete=target)
        self.assertEqual(imported_entry.field, "ftp")
        self.assertEqual(imported_entry.value_numeric, 250)
        self.assertEqual(imported_entry.source_activity_id, imported_activity.id)
        # The target's own live ftp is untouched by the import - only the ledger is populated.
        target.refresh_from_db()
        self.assertEqual(target.ftp, 222)

        from django.core.files.storage import default_storage

        default_storage.delete(relative_path)

    def test_a_manually_entered_value_round_trips_with_no_source_activity(self):
        # source_activity=None (see threshold_history.py::record_manual_value) - not a malformed
        # or unmapped reference, so this must NOT be treated as a dangling link and skipped.
        source = User.objects.create_user(email="threshold-manual-source@example.cc", password="x", name="Source")
        target = User.objects.create_user(email="threshold-manual-target@example.cc", password="x", name="Target")
        ThresholdHistory.objects.create(
            athlete=source, field="ftp", value_numeric=260, source_activity=None, effective_from=date(2026, 1, 1)
        )

        relative_path = "exports/test/threshold-history-manual-roundtrip.json.gz"
        write_export(source.id, None, relative_path)
        counts = read_import(target.id, relative_path)

        self.assertEqual(counts["threshold_history_imported"], 1)
        self.assertEqual(counts["items_skipped"], 0)
        imported_entry = ThresholdHistory.objects.get(athlete=target)
        self.assertEqual(imported_entry.value_numeric, 260)
        self.assertIsNone(imported_entry.source_activity_id)

        from django.core.files.storage import default_storage

        default_storage.delete(relative_path)

    def test_a_dangling_source_activity_reference_is_still_skipped(self):
        # Unlike a manual entry (source_activity_id absent from the row entirely), this row HAS a
        # source_activity_id but no matching activity appears anywhere in the file - genuinely
        # unmappable (e.g. that specific activity failed to import), must still be skipped rather
        # than silently imported as a "manual" entry.
        import os

        from django.core.files.storage import default_storage

        target = User.objects.create_user(email="threshold-dangling-target@example.cc", password="x", name="Target")
        relative_path = "exports/test/threshold-history-dangling.json.gz"
        full_path = default_storage.path(relative_path)
        os.makedirs(os.path.dirname(full_path), exist_ok=True)
        with gzip.GzipFile(full_path, mode="wb") as gz:
            gz.write(
                json.dumps(
                    {
                        "generated_at": "2026-01-01T00:00:00Z",
                        "athlete_id": "someone",
                        "equipment": {"bikes": [], "shoes": [], "components": []},
                        "workouts": [],
                        "activities": [],
                        "races": [],
                        "scheduled_workouts": [],
                        "threshold_history": [
                            {
                                "field": "ftp",
                                "value_numeric": 260,
                                "value_pace": "",
                                "source_activity_id": "act_doesnotexist",
                                "effective_from": "2026-01-01",
                            }
                        ],
                    }
                ).encode("utf-8")
            )

        counts = read_import(target.id, relative_path)

        self.assertEqual(counts["threshold_history_imported"], 0)
        self.assertEqual(counts["items_skipped"], 1)
        self.assertEqual(ThresholdHistory.objects.filter(athlete=target).count(), 0)

        default_storage.delete(relative_path)

    def test_a_file_exported_before_this_feature_existed_imports_with_an_empty_ledger(self):
        # No "threshold_history" key at all in the source document - the pre-feature shape.
        import os

        from django.core.files.storage import default_storage

        target = User.objects.create_user(email="threshold-history-pre-feature@example.cc", password="x", name="Target")
        relative_path = "exports/test/threshold-history-pre-feature.json.gz"
        full_path = default_storage.path(relative_path)
        os.makedirs(os.path.dirname(full_path), exist_ok=True)
        with gzip.GzipFile(full_path, mode="wb") as gz:
            gz.write(
                json.dumps(
                    {
                        "generated_at": "2026-01-01T00:00:00Z",
                        "athlete_id": "someone",
                        "equipment": {"bikes": [], "shoes": [], "components": []},
                        "workouts": [],
                        "activities": [],
                        "races": [],
                        "scheduled_workouts": [],
                    }
                ).encode("utf-8")
            )

        counts = read_import(target.id, relative_path)
        self.assertEqual(counts["threshold_history_imported"], 0)
        self.assertEqual(ThresholdHistory.objects.filter(athlete=target).count(), 0)

        default_storage.delete(relative_path)


class SportFilterTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="sport-filter@example.cc", password="x", name="Athlete")
        _seed_full_account(self.athlete)
        _make_activity(
            self.athlete, sport="run", name="Standalone Run", start_date=datetime(2026, 1, 2, 7, 0, tzinfo=UTC)
        )

    def test_bike_filter_excludes_run_and_multisport_parent_but_keeps_matching_leg(self):
        relative_path = "exports/test/sport-filter.json.gz"
        write_export(self.athlete.id, "bike", relative_path)

        from django.core.files.storage import default_storage

        with default_storage.open(relative_path, "rb") as raw, gzip.GzipFile(fileobj=raw) as gz:
            doc = json.loads(gz.read())

        names = {entry["activity"]["name"] for entry in doc["activities"]}
        self.assertEqual(names, {"Triathlon Bike Leg"})
        self.assertEqual(len(doc["workouts"]), 0)  # the only workout is sport=run
        self.assertEqual(len(doc["races"]), 1)  # the seeded race is sport=bike
        # Equipment is always exported in full, regardless of the sport filter.
        self.assertEqual(len(doc["equipment"]["bikes"]), 1)
        self.assertEqual(len(doc["equipment"]["shoes"]), 1)

        default_storage.delete(relative_path)

    def test_bike_filter_includes_a_linked_race_whose_own_sport_was_never_set(self):
        # Reproduces linking to an *existing* race via the UI (as opposed to "mark this
        # activity as a race", which sets sport at creation) - the race's own sport stays
        # blank even though it's now tied to a bike activity.
        unsported_bike_activity = _make_activity(
            self.athlete, sport="bike", name="Another Ride", start_date=datetime(2026, 1, 3, 7, 0, tzinfo=UTC)
        )
        Race.objects.create(
            athlete=self.athlete,
            name="Linked, sport never set",
            date=date(2026, 1, 3),
            activity=unsported_bike_activity,
        )

        relative_path = "exports/test/sport-filter-linked-race.json.gz"
        write_export(self.athlete.id, "bike", relative_path)

        from django.core.files.storage import default_storage

        with default_storage.open(relative_path, "rb") as raw, gzip.GzipFile(fileobj=raw) as gz:
            doc = json.loads(gz.read())

        race_names = {r["name"] for r in doc["races"]}
        self.assertIn("Linked, sport never set", race_names)

        default_storage.delete(relative_path)

    def test_a_race_with_no_sport_and_no_linked_activity_is_still_excluded_by_a_sport_filter(self):
        Race.objects.create(athlete=self.athlete, name="Unlinked, no sport", date=date(2026, 1, 4))

        relative_path = "exports/test/sport-filter-unlinked-race.json.gz"
        write_export(self.athlete.id, "bike", relative_path)

        from django.core.files.storage import default_storage

        with default_storage.open(relative_path, "rb") as raw, gzip.GzipFile(fileobj=raw) as gz:
            doc = json.loads(gz.read())

        race_names = {r["name"] for r in doc["races"]}
        self.assertNotIn("Unlinked, no sport", race_names)

        default_storage.delete(relative_path)


class ProgressStepTests(TestCase):
    """write_export/read_import's on_step callback - the ExportJob/ImportJob.current_step
    field these feed is what the frontend's progress dialog polls for."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="progress-steps@example.cc", password="x", name="Athlete")
        _seed_full_account(self.athlete)

    def test_write_export_calls_on_step_for_every_section_in_order(self):
        relative_path = "exports/test/progress-steps.json.gz"
        seen: list[str] = []

        write_export(self.athlete.id, None, relative_path, on_step=seen.append)

        self.assertEqual(seen, [key for key, _ in DATA_TRANSFER_STEPS])

        from django.core.files.storage import default_storage

        default_storage.delete(relative_path)

    def test_read_import_calls_on_step_for_every_section_in_order(self):
        relative_path = "exports/test/progress-steps-source.json.gz"
        write_export(self.athlete.id, None, relative_path)

        target = User.objects.create_user(email="progress-steps-target@example.cc", password="x", name="Target")
        seen: list[str] = []

        read_import(target.id, relative_path, on_step=seen.append)

        self.assertEqual(seen, [key for key, _ in DATA_TRANSFER_STEPS])

        from django.core.files.storage import default_storage

        default_storage.delete(relative_path)


class ProgressItemsTests(TestCase):
    """write_export/read_import's on_total/on_progress callbacks - the ExportJob/ImportJob
    total_items/processed_items fields these feed drive the fine-grained "N of M" progress bar,
    same pattern as the FIT-archive-import batch progress bar. Scoped *per section* - e.g. while
    on_step is "activities", on_total/on_progress report activities specifically, not a blend
    across every kind of data - so each one resets at the start of every new section."""

    def setUp(self):
        self.athlete = User.objects.create_user(email="progress-items@example.cc", password="x", name="Athlete")
        _seed_full_account(self.athlete)
        # _seed_full_account: 2 activities, 1 race, 1 workout, 1 scheduled_workout, 1 bike,
        # 1 shoe, 1 component, 1 threshold_history entry. Equipment is one section
        # (bikes+shoes+components combined) = 3.
        self.expected_totals_by_step = {
            "equipment": 3,
            "workouts": 1,
            "activities": 2,
            "races": 1,
            "scheduled_workouts": 1,
            "threshold_history": 1,
        }

    def test_write_export_calls_on_total_and_on_progress_per_section(self):
        relative_path = "exports/test/progress-items.json.gz"
        events: list[tuple[str, int]] = []

        write_export(
            self.athlete.id,
            None,
            relative_path,
            on_step=lambda step: events.append(("step", step)),
            on_total=lambda total: events.append(("total", total)),
            on_progress=lambda processed: events.append(("progress", processed)),
        )

        for step, total in self.expected_totals_by_step.items():
            # Each section's on_total fires with just that section's own count, and its final
            # on_progress (the flush at the section's end) reaches that same count - never a
            # blend with any other section's items.
            self.assertIn(("step", step), events)
            self.assertIn(("total", total), events)
            step_index = events.index(("step", step))
            total_index = events.index(("total", total), step_index)
            self.assertEqual(events[total_index + 1], ("progress", total))

        from django.core.files.storage import default_storage

        default_storage.delete(relative_path)

    def test_read_import_calls_on_total_and_on_progress_per_section(self):
        relative_path = "exports/test/progress-items-source.json.gz"
        write_export(self.athlete.id, None, relative_path)
        target = User.objects.create_user(email="progress-items-target@example.cc", password="x", name="Target")
        events: list[tuple[str, int]] = []

        read_import(
            target.id,
            relative_path,
            on_step=lambda step: events.append(("step", step)),
            on_total=lambda total: events.append(("total", total)),
            on_progress=lambda processed: events.append(("progress", processed)),
        )

        for step, total in self.expected_totals_by_step.items():
            self.assertIn(("step", step), events)
            self.assertIn(("total", total), events)
            step_index = events.index(("step", step))
            total_index = events.index(("total", total), step_index)
            self.assertEqual(events[total_index + 1], ("progress", total))

        from django.core.files.storage import default_storage

        default_storage.delete(relative_path)

    def test_read_import_skips_on_total_for_a_file_with_no_counts_block(self):
        # A file exported before the "counts" field existed - on_total should simply never
        # fire rather than crash or report a bogus 0.
        import gzip as gzip_module

        from django.core.files.storage import default_storage

        relative_path = "exports/test/progress-items-no-counts.json.gz"
        full_path = default_storage.path(relative_path)
        import os

        os.makedirs(os.path.dirname(full_path), exist_ok=True)
        with gzip_module.GzipFile(full_path, mode="wb") as gz:
            gz.write(
                json.dumps(
                    {
                        "generated_at": "2026-01-01T00:00:00Z",
                        "athlete_id": self.athlete.id,
                        "equipment": {"bikes": [], "shoes": [], "components": []},
                        "workouts": [],
                        "activities": [],
                        "races": [],
                        "scheduled_workouts": [],
                    }
                ).encode("utf-8")
            )

        target = User.objects.create_user(email="progress-items-nocounts@example.cc", password="x", name="Target")
        totals: list[int] = []

        read_import(target.id, relative_path, on_total=totals.append)

        self.assertEqual(totals, [])

        default_storage.delete(relative_path)


class ExportCountsTests(TestCase):
    def setUp(self):
        self.athlete = User.objects.create_user(email="export-counts@example.cc", password="x", name="Athlete")
        _seed_full_account(self.athlete)

    def test_unfiltered_export_counts_match_the_full_seeded_account(self):
        relative_path = "exports/test/counts-full.json.gz"
        write_export(self.athlete.id, None, relative_path)

        from django.core.files.storage import default_storage

        with default_storage.open(relative_path, "rb") as raw, gzip.GzipFile(fileobj=raw) as gz:
            doc = json.loads(gz.read())

        self.assertEqual(
            doc["counts"],
            {
                "activities": 2,
                "races": 1,
                "workouts": 1,
                "scheduled_workouts": 1,
                "threshold_history": 1,
                "bikes": 1,
                "shoes": 1,
                "components": 1,
            },
        )
        # And they genuinely match what's in the rest of the same file, not just a hardcoded number.
        self.assertEqual(doc["counts"]["activities"], len(doc["activities"]))
        self.assertEqual(doc["counts"]["races"], len(doc["races"]))
        self.assertEqual(doc["counts"]["workouts"], len(doc["workouts"]))
        self.assertEqual(doc["counts"]["scheduled_workouts"], len(doc["scheduled_workouts"]))
        self.assertEqual(doc["counts"]["threshold_history"], len(doc["threshold_history"]))
        self.assertEqual(doc["counts"]["bikes"], len(doc["equipment"]["bikes"]))
        self.assertEqual(doc["counts"]["shoes"], len(doc["equipment"]["shoes"]))
        self.assertEqual(doc["counts"]["components"], len(doc["equipment"]["components"]))

        default_storage.delete(relative_path)

    def test_sport_filtered_export_counts_reflect_the_filtered_scope(self):
        relative_path = "exports/test/counts-bike.json.gz"
        write_export(self.athlete.id, "bike", relative_path)

        from django.core.files.storage import default_storage

        with default_storage.open(relative_path, "rb") as raw, gzip.GzipFile(fileobj=raw) as gz:
            doc = json.loads(gz.read())

        # Multisport parent excluded (own sport is "multisport"), workout/scheduled_workout are
        # sport=run so drop to 0 - the ftp threshold_history entry maps to sport=bike so it
        # stays 1 - equipment stays full regardless of the filter.
        self.assertEqual(
            doc["counts"],
            {
                "activities": 1,
                "races": 1,
                "workouts": 0,
                "scheduled_workouts": 0,
                "threshold_history": 1,
                "bikes": 1,
                "shoes": 1,
                "components": 1,
            },
        )

        default_storage.delete(relative_path)
