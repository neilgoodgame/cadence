from django.core.files.storage import default_storage
from django.shortcuts import get_object_or_404
from rest_framework.exceptions import PermissionDenied
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from core.auth_context import get_effective_athlete_id
from core.permissions import user_may_read, user_may_write

from .models import Upload, UploadBatch
from .serializers import UploadBatchSerializer, UploadSerializer
from .services import create_activity_batch_upload


def _require_write(request: Request, athlete_id: str) -> None:
    sub, _ = get_effective_athlete_id(request)
    if not user_may_write(sub, athlete_id):
        raise PermissionDenied("You do not have write access to that athlete's data.")


class UploadHistoryView(APIView):
    def delete(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)

        to_purge = Upload.objects.filter(athlete_id=athlete_id).exclude(status="ready", activity__isnull=False)
        stored_paths = list(to_purge.values_list("stored_path", flat=True))

        files_deleted = 0
        for path in stored_paths:
            if path:
                try:
                    default_storage.delete(path)
                    files_deleted += 1
                except Exception:
                    pass

        uploads_deleted, _ = to_purge.delete()
        UploadBatch.objects.filter(athlete_id=athlete_id, uploads__isnull=True).delete()

        return Response({"uploads_deleted": uploads_deleted, "files_deleted": files_deleted})


class ActivityBatchUploadView(APIView):
    def post(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)

        batch, status_code = create_activity_batch_upload(request, athlete_id)
        response = Response(UploadBatchSerializer(batch).data, status=status_code)
        response["Location"] = f"/v1/uploads/batches/{batch.id}"
        if status_code == 202:
            response["Retry-After"] = "5"
        return response


class UploadDetailView(APIView):
    def get(self, request: Request, id: str) -> Response:
        upload = get_object_or_404(Upload, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, upload.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")

        response = Response(UploadSerializer(upload).data)
        if upload.status in ("queued", "processing"):
            response["Retry-After"] = "3"
        return response


class UploadBatchDetailView(APIView):
    def get(self, request: Request, id: str) -> Response:
        batch = get_object_or_404(UploadBatch, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, batch.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")

        response = Response(UploadBatchSerializer(batch).data)
        if batch.status in ("unpacking", "processing"):
            response["Retry-After"] = "5"
        return response
