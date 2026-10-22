from rest_framework.exceptions import ValidationError
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from core.auth_context import authenticated_user
from uploads.serializers import UploadBatchSerializer

from . import crypto, sync
from .models import GarminConnection, GarminSyncRun
from .serializers import GarminConnectionSerializer, GarminConnectionWriteSerializer, GarminSyncRequestSerializer


class GarminConnectionView(APIView):
    def get(self, request: Request) -> Response:
        connection = GarminConnection.objects.filter(owner=authenticated_user(request)).first()
        body = (
            GarminConnectionSerializer.from_connection(connection)
            if connection
            else GarminConnectionSerializer.not_connected()
        )
        return Response(GarminConnectionSerializer(body).data)

    def put(self, request: Request) -> Response:
        serializer = GarminConnectionWriteSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data
        owner = authenticated_user(request)

        api_key = (data.get("api_key") or "").strip() or None
        connection = GarminConnection.objects.filter(owner=owner).first()
        if connection is None and api_key is None:
            raise ValidationError({"api_key": "A Garmin API key is required."})
        email = (data.get("email") or "").strip() or owner.email
        if not email:
            raise ValidationError({"email": "An email address is required."})

        if connection is None:
            connection = GarminConnection(owner=owner)
        if api_key is not None:
            connection.api_key_encrypted = crypto.encrypt(api_key, owner.id)
        connection.garmin_email = email
        connection.key_expires_on = data.get("key_expires_on")
        connection.save()
        return Response(GarminConnectionSerializer(GarminConnectionSerializer.from_connection(connection)).data)

    def delete(self, request: Request) -> Response:
        GarminConnection.objects.filter(owner=authenticated_user(request)).delete()
        return Response(status=204)


class GarminSyncView(APIView):
    def post(self, request: Request) -> Response:
        serializer = GarminSyncRequestSerializer(data=request.data or {})
        serializer.is_valid(raise_exception=True)
        data = serializer.validated_data

        result = sync.sync(authenticated_user(request), data.get("limit"), data.get("since"))
        run = result.run
        response = Response(
            {
                "object": "garmin_sync",
                "id": run.id,
                "found": run.found,
                "already_synced": run.already_synced,
                "requested": run.requested,
                "failed": run.failed,
                "truncated": run.truncated,
                "batch": UploadBatchSerializer(result.batch).data if result.batch else None,
            },
            status=202,
        )
        if result.batch:
            response["Location"] = f"/v1/uploads/batches/{result.batch.id}"
            response["Retry-After"] = "5"
        return response


class GarminSyncHistoryView(APIView):
    """Recent sync runs, newest first. ``batch`` and each activity's upload/activity fields are read
    live, so a run still being processed settles in place; they are null once the batch or
    activity has since been deleted (the Garmin id and start time are always present)."""

    def get(self, request: Request) -> Response:
        try:
            limit = int(request.query_params.get("limit", 20))
        except ValueError:
            limit = 20
        limit = max(1, min(limit, sync.RUNS_KEPT))
        runs = (
            GarminSyncRun.objects.filter(owner=authenticated_user(request))
            .select_related("batch")
            .order_by("-started_at")[:limit]
        )
        return Response({"data": [_run_body(run) for run in runs]})


def _run_body(run: GarminSyncRun) -> dict:
    uploads = {}
    batch_body = None
    if run.batch is not None:
        for upload in run.batch.uploads.select_related("activity"):
            uploads[upload.filename] = upload
        data = UploadBatchSerializer(run.batch).data
        batch_body = {"id": data["id"], "status": data["status"], "counts": data["counts"]}

    activities = []
    for synced in run.synced_activities.order_by("start_time"):
        # Garmin names every download "<id>_ACTIVITY.fit", which is how a synced id finds its upload.
        upload = uploads.get(f"{synced.garmin_activity_id}_ACTIVITY.fit")
        activity = upload.activity if upload else None
        activities.append(
            {
                "garmin_activity_id": synced.garmin_activity_id,
                "start_time": synced.start_time,
                "upload_status": upload.status if upload else None,
                "activity_id": activity.id if activity else None,
                "name": activity.name if activity else None,
                "sport": activity.sport if activity else None,
            }
        )
    return {
        "id": run.id,
        "started_at": run.started_at,
        "finished_at": run.finished_at,
        "found": run.found,
        "already_synced": run.already_synced,
        "requested": run.requested,
        "failed": run.failed,
        "truncated": run.truncated,
        "error": run.error or None,
        "batch": batch_body,
        "activities": activities,
    }
