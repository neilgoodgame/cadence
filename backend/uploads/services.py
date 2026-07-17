import hashlib
import io
import zipfile
from typing import Any

from django.core.files.storage import default_storage
from django.core.files.uploadedfile import UploadedFile
from django.utils import timezone
from rest_framework.exceptions import ValidationError
from rest_framework.request import Request

from gear.models import Shoe

from .models import Upload, UploadBatch
from .tasks import _maybe_finalize_batch
from .tasks import process_upload as process_upload_task

SUPPORTED_EXTENSIONS = {".fit", ".gpx", ".tcx"}
MAX_BATCH_FILES = 10000
MAX_BATCH_BYTES = 200 * 1024 * 1024


def _extension(filename: str) -> str:
    return "." + filename.rsplit(".", 1)[-1].lower() if "." in filename else ""


def _hash_fileobj(file_obj: UploadedFile) -> str:
    file_obj.seek(0)
    digest = hashlib.sha256()
    for chunk in file_obj.chunks():
        digest.update(chunk)
    file_obj.seek(0)
    return digest.hexdigest()


def _existing_ready_upload(athlete_id: str, file_hash: str) -> Upload | None:
    return (
        Upload.objects.filter(athlete_id=athlete_id, file_hash=file_hash, status="ready")
        .exclude(activity__isnull=True)
        .order_by("-received_at")
        .first()
    )


def _to_float(value: Any) -> float | None:
    return float(value) if value not in (None, "") else None


def _to_int(value: Any) -> int | None:
    return int(value) if value not in (None, "") else None


def create_activity_upload(request: Request, athlete_id: str) -> tuple[Upload, int]:
    file_obj = request.FILES.get("file")
    if not file_obj:
        raise ValidationError({"file": "This field is required."})

    if _extension(file_obj.name) not in SUPPORTED_EXTENSIONS:
        raise ValidationError({"file": f"Unsupported file type '{_extension(file_obj.name)}'."})

    shoe_id = request.data.get("shoe_id") or None
    if shoe_id and not Shoe.objects.filter(pk=shoe_id, athlete_id=athlete_id).exists():
        raise ValidationError({"shoe_id": f"Unknown shoe '{shoe_id}'."})

    file_hash = _hash_fileobj(file_obj)
    existing = _existing_ready_upload(athlete_id, file_hash)

    upload = Upload.objects.create(
        athlete_id=athlete_id,
        filename=file_obj.name,
        file_hash=file_hash,
        weight_before_kg=_to_float(request.data.get("weight_before_kg")),
        weight_after_kg=_to_float(request.data.get("weight_after_kg")),
        fluids_ml=_to_int(request.data.get("fluids_ml")),
        shoe_id=shoe_id,
    )

    if existing:
        upload.status = "duplicate"
        upload.activity_id = existing.activity_id
        upload.completed_at = timezone.now()
        upload.save(update_fields=["status", "activity", "completed_at"])
        return upload, 409

    upload.stored_path = default_storage.save(f"uploads/{athlete_id}/{upload.id}_{file_obj.name}", file_obj)
    upload.save(update_fields=["stored_path"])
    process_upload_task.delay(upload.id)
    return upload, 202


def create_activity_batch_upload(request: Request, athlete_id: str) -> tuple[UploadBatch, int]:
    archive = request.FILES.get("file")
    if not archive:
        raise ValidationError({"file": "This field is required."})

    on_duplicate = request.data.get("on_duplicate", "skip")
    if on_duplicate not in ("skip", "replace"):
        raise ValidationError({"on_duplicate": "Must be 'skip' or 'replace'."})

    if archive.size > MAX_BATCH_BYTES:
        raise ValidationError({"file": "Archive exceeds the 200 MB limit."})

    try:
        zf = zipfile.ZipFile(archive)
        names = [n for n in zf.namelist() if not n.endswith("/") and _extension(n) in SUPPORTED_EXTENSIONS]
    except zipfile.BadZipFile as exc:
        raise ValidationError({"file": "The archive could not be read."}) from exc

    if not names:
        raise ValidationError({"file": "The archive contained no supported files."})
    if len(names) > MAX_BATCH_FILES:
        raise ValidationError({"file": f"Archive exceeds the {MAX_BATCH_FILES}-file limit."})

    batch = UploadBatch.objects.create(
        athlete_id=athlete_id, filename=archive.name, on_duplicate=on_duplicate, status="processing"
    )

    for name in names:
        content = zf.read(name)
        file_hash = hashlib.sha256(content).hexdigest()
        short_name = name.rsplit("/", 1)[-1]
        existing = _existing_ready_upload(athlete_id, file_hash)

        upload = Upload.objects.create(athlete_id=athlete_id, batch=batch, filename=short_name, file_hash=file_hash)

        if existing and on_duplicate == "skip":
            upload.status = "duplicate"
            upload.activity_id = existing.activity_id
            upload.completed_at = timezone.now()
            upload.save(update_fields=["status", "activity", "completed_at"])
            continue

        if existing and on_duplicate == "replace" and existing.activity is not None:
            existing.activity.delete()

        upload.stored_path = default_storage.save(f"uploads/{athlete_id}/{upload.id}_{short_name}", io.BytesIO(content))
        upload.save(update_fields=["stored_path"])
        process_upload_task.delay(upload.id)

    # A batch whose files all settled at staging (e.g. every one a duplicate) queued no
    # tasks, so no task completion will ever finalize it. Use the shared finalizer so the
    # upload_batch.completed webhook fires for this path too.
    _maybe_finalize_batch(batch.id)
    batch.refresh_from_db()

    return batch, 202
