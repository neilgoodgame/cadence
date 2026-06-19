from django.shortcuts import get_object_or_404
from rest_framework.exceptions import PermissionDenied
from rest_framework.response import Response
from rest_framework.views import APIView

from core.auth_context import get_effective_athlete_id
from core.permissions import user_may_read, user_may_write

from .models import Upload, UploadBatch
from .serializers import UploadBatchSerializer, UploadSerializer
from .services import create_activity_batch_upload


def _require_write(request, athlete_id):
    sub, _ = get_effective_athlete_id(request)
    if not user_may_write(sub, athlete_id):
        raise PermissionDenied("You do not have write access to that athlete's data.")


class ActivityBatchUploadView(APIView):
    def post(self, request):
        _, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)

        batch, status_code = create_activity_batch_upload(request, athlete_id)
        response = Response(UploadBatchSerializer(batch).data, status=status_code)
        response["Location"] = f"/v1/uploads/batches/{batch.id}"
        if status_code == 202:
            response["Retry-After"] = "5"
        return response


class UploadDetailView(APIView):
    def get(self, request, id):
        upload = get_object_or_404(Upload, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, upload.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")

        response = Response(UploadSerializer(upload).data)
        if upload.status in ("queued", "processing"):
            response["Retry-After"] = "3"
        return response


class UploadBatchDetailView(APIView):
    def get(self, request, id):
        batch = get_object_or_404(UploadBatch, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, batch.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")

        response = Response(UploadBatchSerializer(batch).data)
        if batch.status in ("unpacking", "processing"):
            response["Retry-After"] = "5"
        return response
