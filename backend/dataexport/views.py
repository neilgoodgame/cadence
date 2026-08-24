from datetime import date

from django.conf import settings
from django.core.files.storage import default_storage
from django.http import FileResponse
from django.shortcuts import get_object_or_404
from rest_framework.exceptions import PermissionDenied, ValidationError
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from accounts.models import User
from activities.models import Activity
from core.auth_context import get_effective_athlete_id
from core.permissions import user_may_read, user_may_write

from .models import ExportJob, ImportJob
from .serializers import ExportJobSerializer, ImportJobSerializer
from .tasks import run_export_task, run_import_task


def _require_write(request: Request, athlete_id: str) -> None:
    sub, _ = get_effective_athlete_id(request)
    if not user_may_write(sub, athlete_id):
        raise PermissionDenied("You do not have write access to that athlete's data.")


def _require_read(request: Request, athlete_id: str) -> None:
    sub, _ = get_effective_athlete_id(request)
    if not user_may_read(sub, athlete_id):
        raise PermissionDenied("You do not have access to that athlete's data.")


def _require_email_verified(athlete_id: str, action: str) -> None:
    """The enforcement half of the soft gate email_verified is - mirrors backend_java's
    UserService.requireEmailVerified, wired into the same two endpoints (POST /v1/export,
    POST /v1/import)."""
    athlete = get_object_or_404(User, pk=athlete_id)
    if not athlete.email_verified:
        raise PermissionDenied(f"Verify your email address before {action}.")


class ExportView(APIView):
    def post(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)
        _require_email_verified(athlete_id, "exporting your data")

        sport = request.query_params.get("sport") or None
        if sport and sport not in dict(Activity.SPORT_CHOICES):
            raise ValidationError({"sport": "Unrecognized sport."})

        # Only one export on record per athlete - replace any previous job and file
        # outright rather than keeping a history nobody asked for.
        existing = ExportJob.objects.filter(athlete_id=athlete_id).first()
        if existing:
            if existing.stored_path:
                try:
                    default_storage.delete(existing.stored_path)
                except Exception:
                    pass
            existing.delete()

        job = ExportJob.objects.create(athlete_id=athlete_id)
        run_export_task.delay(job.id, sport)

        response = Response(ExportJobSerializer(job).data, status=202)
        response["Location"] = f"/v1/export/{job.id}"
        response["Retry-After"] = "5"
        return response


class ExportDetailView(APIView):
    def get(self, request: Request, id: str) -> Response:
        job = get_object_or_404(ExportJob, pk=id)
        _require_read(request, job.athlete_id)

        response = Response(ExportJobSerializer(job).data)
        if job.status in ("queued", "processing"):
            response["Retry-After"] = "5"
        return response


class ExportDownloadView(APIView):
    def get(self, request: Request, id: str) -> FileResponse:
        job = get_object_or_404(ExportJob, pk=id)
        _require_read(request, job.athlete_id)
        if job.status != "ready":
            raise ValidationError({"status": "Export is not ready yet."})

        filename = f"cadence-export-{date.today().isoformat()}.json.gz"
        return FileResponse(
            default_storage.open(job.stored_path, "rb"),
            as_attachment=True,
            filename=filename,
            content_type="application/gzip",
        )


class ImportView(APIView):
    def post(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)
        _require_email_verified(athlete_id, "importing data")

        file_obj = request.FILES.get("file")
        if not file_obj:
            raise ValidationError({"file": "This field is required."})
        if file_obj.size > settings.MAX_IMPORT_SIZE_BYTES:
            raise ValidationError({"file": "Import file is too large."})

        job = ImportJob.objects.create(athlete_id=athlete_id)
        # Streamed to disk (via File.chunks(), not one big in-memory buffer - this file can
        # be up to MAX_IMPORT_SIZE_BYTES) so the async job has something stable to read once
        # the request has returned.
        stored_path = default_storage.save(f"imports/{athlete_id}/{job.id}_{file_obj.name}", file_obj)
        run_import_task.delay(job.id, stored_path)

        response = Response(ImportJobSerializer(job).data, status=202)
        response["Location"] = f"/v1/import/{job.id}"
        response["Retry-After"] = "5"
        return response


class ImportDetailView(APIView):
    def get(self, request: Request, id: str) -> Response:
        job = get_object_or_404(ImportJob, pk=id)
        _require_read(request, job.athlete_id)

        response = Response(ImportJobSerializer(job).data)
        if job.status in ("queued", "processing"):
            response["Retry-After"] = "5"
        return response
