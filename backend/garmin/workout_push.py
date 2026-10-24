"""The outbox behind "push to Garmin Connect": the browser queues operations, a bridge on the
athlete's own machine applies them with the athlete's own Garmin login and reports the Garmin ids
back, which are kept as links so later edits, reschedules and cancels target the right Garmin
objects. Operations on the same workout or scheduled workout that are still pending are merged
rather than queued twice. Mirrors ``GarminWorkoutPushService`` in backend_java."""

from datetime import UTC, datetime

from django.db import transaction
from django.utils import timezone
from rest_framework.exceptions import NotFound, ValidationError

from accounts.models import User
from core.exceptions import ConflictError
from scheduling.models import ScheduledWorkout
from workouts.models import Workout

from .models import GarminConnection, GarminScheduleLink, GarminWorkoutLink, GarminWorkoutOp

# Finished (done or failed) operations kept per athlete.
FINISHED_KEPT = 100


def _require_usable_connection(owner: User) -> None:
    connection = GarminConnection.objects.filter(owner=owner).first()
    if connection is None:
        raise ConflictError("Set up your Garmin connection in Preferences first.")
    if connection.key_expires_on is not None and connection.key_expires_on < datetime.now(UTC).date():
        raise ConflictError("Your Garmin API key has expired. Replace it in Preferences first.")


def _require_visible_workout(owner: User, workout_id: str) -> None:
    workout = Workout.objects.filter(pk=workout_id).first()
    visible = workout is not None and (
        workout.created_by_id == owner.id
        or ScheduledWorkout.objects.filter(athlete=owner, workout_id=workout_id).exists()
    )
    if not visible:
        raise NotFound("No such workout.")


def _new_op(owner: User, kind: str, workout_id: str) -> GarminWorkoutOp:
    return GarminWorkoutOp(owner=owner, kind=kind, workout_id=workout_id)


def enqueue(owner: User, data: dict) -> GarminWorkoutOp | None:
    """None when there was nothing to do (e.g. cancelling something that never reached Garmin)."""
    _require_usable_connection(owner)
    kind = data["kind"]
    if kind != "unschedule":
        _require_visible_workout(owner, data["workout_id"])
    with transaction.atomic():
        if kind == "upsert_workout":
            return _upsert_workout(owner, data)
        if kind in ("schedule", "reschedule"):
            return _ensure_scheduled(owner, data)
        return _unschedule(owner, data)


def _upsert_workout(owner: User, data: dict) -> GarminWorkoutOp:
    if not (data.get("tcx") or "").strip():
        raise ValidationError({"tcx": "tcx is required to push a workout."})
    op = GarminWorkoutOp.objects.filter(
        owner=owner, status="pending", kind="upsert_workout", workout_id=data["workout_id"]
    ).first() or _new_op(owner, "upsert_workout", data["workout_id"])
    op.tcx = data["tcx"]
    op.name = data.get("name") or ""
    op.save()
    return op


def _require_scheduled_fields(data: dict) -> None:
    if not data.get("scheduled_workout_id"):
        raise ValidationError({"scheduled_workout_id": "scheduled_workout_id is required."})
    if not data.get("date"):
        raise ValidationError({"date": "date is required."})


def _ensure_scheduled(owner: User, data: dict) -> GarminWorkoutOp:
    """schedule and reschedule both mean "this scheduled workout should be on Garmin on this date"."""
    _require_scheduled_fields(data)
    existing = GarminWorkoutOp.objects.filter(
        owner=owner,
        status="pending",
        scheduled_workout_id=data["scheduled_workout_id"],
        kind__in=("schedule", "reschedule"),
    ).first()
    if existing is not None:
        existing.op_date = data["date"]
        existing.save()
        return existing
    on_garmin = GarminScheduleLink.objects.filter(
        owner=owner, scheduled_workout_id=data["scheduled_workout_id"]
    ).exists()
    op = _new_op(owner, "reschedule" if on_garmin else "schedule", data["workout_id"])
    op.scheduled_workout_id = data["scheduled_workout_id"]
    op.op_date = data["date"]
    op.save()
    return op


def _unschedule(owner: User, data: dict) -> GarminWorkoutOp | None:
    if not data.get("scheduled_workout_id"):
        raise ValidationError({"scheduled_workout_id": "scheduled_workout_id is required."})
    pending = GarminWorkoutOp.objects.filter(
        owner=owner, status="pending", scheduled_workout_id=data["scheduled_workout_id"]
    )
    already_queued = pending.filter(kind="unschedule").exists()
    # A schedule that never reached Garmin is simply dropped.
    pending.exclude(kind="unschedule").delete()
    on_garmin = GarminScheduleLink.objects.filter(
        owner=owner, scheduled_workout_id=data["scheduled_workout_id"]
    ).exists()
    if not on_garmin or already_queued:
        return None
    op = _new_op(owner, "unschedule", data["workout_id"])
    op.scheduled_workout_id = data["scheduled_workout_id"]
    op.save()
    return op


def record_result(owner: User, op_id: str, data: dict) -> GarminWorkoutOp:
    op = GarminWorkoutOp.objects.filter(pk=op_id, owner=owner).first()
    if op is None:
        raise NotFound("No such operation.")
    if op.status != "pending":
        raise ConflictError("That operation has already been reported.")
    with transaction.atomic():
        if data["status"] == "failed":
            op.error = data.get("error") or "Failed."
        else:
            _apply_done(owner, op, data)
        op.status = data["status"]
        op.completed_at = timezone.now()
        op.save()
        _prune_finished(owner)
    return op


def _apply_done(owner: User, op: GarminWorkoutOp, data: dict) -> None:
    if op.kind == "upsert_workout":
        garmin_workout_id = _require_id(data.get("garmin_workout_id"), "garmin_workout_id")
        op.garmin_workout_id = garmin_workout_id
        GarminWorkoutLink.objects.update_or_create(
            owner=owner, workout_id=op.workout_id, defaults={"garmin_workout_id": garmin_workout_id}
        )
    elif op.kind in ("schedule", "reschedule"):
        garmin_schedule_id = _require_id(data.get("garmin_schedule_id"), "garmin_schedule_id")
        garmin_workout_id = data.get("garmin_workout_id")
        if garmin_workout_id is None:
            link = GarminWorkoutLink.objects.filter(owner=owner, workout_id=op.workout_id).first()
            if link is None:
                raise ValidationError({"garmin_workout_id": "garmin_workout_id is required."})
            garmin_workout_id = link.garmin_workout_id
        op.garmin_workout_id = garmin_workout_id
        op.garmin_schedule_id = garmin_schedule_id
        GarminScheduleLink.objects.update_or_create(
            owner=owner,
            scheduled_workout_id=op.scheduled_workout_id,
            defaults={
                "garmin_schedule_id": garmin_schedule_id,
                "garmin_workout_id": garmin_workout_id,
                "scheduled_for": op.op_date,
            },
        )
    else:
        GarminScheduleLink.objects.filter(owner=owner, scheduled_workout_id=op.scheduled_workout_id).delete()


def _require_id(value, field: str) -> int:
    if value is None or value < 1:
        raise ValidationError({field: f"{field} is required."})
    return value


def _prune_finished(owner: User) -> None:
    stale = list(
        GarminWorkoutOp.objects.filter(owner=owner)
        .exclude(status="pending")
        .order_by("-created")
        .values_list("id", flat=True)
    )[FINISHED_KEPT:]
    if stale:
        GarminWorkoutOp.objects.filter(id__in=stale).delete()


def discard(owner: User, op_id: str) -> None:
    op = GarminWorkoutOp.objects.filter(pk=op_id, owner=owner).first()
    if op is None:
        raise NotFound("No such operation.")
    if op.status == "done":
        raise ConflictError("A completed operation cannot be discarded.")
    op.delete()


def op_body(owner: User, op: GarminWorkoutOp) -> dict:
    """``tcx`` is only included for pending operations (that is what the bridge applies). The Garmin
    ids are resolved from earlier results where known, so the bridge can target the right objects."""
    garmin_workout_id = op.garmin_workout_id
    if garmin_workout_id is None:
        link = GarminWorkoutLink.objects.filter(owner=owner, workout_id=op.workout_id).first()
        garmin_workout_id = link.garmin_workout_id if link else None
    garmin_schedule_id = op.garmin_schedule_id
    if garmin_schedule_id is None and op.scheduled_workout_id:
        link = GarminScheduleLink.objects.filter(owner=owner, scheduled_workout_id=op.scheduled_workout_id).first()
        garmin_schedule_id = link.garmin_schedule_id if link else None
    return {
        "object": "garmin_workout_op",
        "id": op.id,
        "kind": op.kind,
        "status": op.status,
        "workout_id": op.workout_id,
        "scheduled_workout_id": op.scheduled_workout_id or None,
        "date": op.op_date,
        "name": op.name or None,
        "tcx": op.tcx if op.status == "pending" else None,
        "garmin_workout_id": garmin_workout_id,
        "garmin_schedule_id": garmin_schedule_id,
        "error": op.error or None,
        "created": op.created,
        "completed_at": op.completed_at,
    }


def links_body(owner: User) -> dict:
    return {
        "workouts": [
            {"workout_id": link.workout_id, "garmin_workout_id": link.garmin_workout_id, "pushed_at": link.pushed_at}
            for link in GarminWorkoutLink.objects.filter(owner=owner)
        ],
        "schedules": [
            {
                "scheduled_workout_id": link.scheduled_workout_id,
                "garmin_schedule_id": link.garmin_schedule_id,
                "scheduled_for": link.scheduled_for,
            }
            for link in GarminScheduleLink.objects.filter(owner=owner)
        ],
        "pending": GarminWorkoutOp.objects.filter(owner=owner, status="pending").count(),
        "failed": GarminWorkoutOp.objects.filter(owner=owner, status="failed").count(),
    }
