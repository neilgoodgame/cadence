import io
from xml.sax.saxutils import escape

from django.core.files.storage import default_storage
from django.db.models import Q
from django.http import HttpResponse
from django.shortcuts import get_object_or_404
from rest_framework import status
from rest_framework.exceptions import PermissionDenied, ValidationError
from rest_framework.request import Request
from rest_framework.response import Response
from rest_framework.views import APIView

from activities.models import Activity
from core.auth_context import get_effective_athlete_id
from core.exceptions import UnprocessableEntityError
from core.permissions import user_may_read, user_may_write

from . import processing
from .geocoding import reverse_geocode
from .models import Course
from .serializers import CourseListSerializer, CourseSerializer, CourseUpdateSerializer


def _require_read(request: Request, athlete_id: str) -> None:
    sub, _ = get_effective_athlete_id(request)
    if not user_may_read(sub, athlete_id):
        raise PermissionDenied("You do not have access to that athlete's data.")


def _require_write(request: Request, athlete_id: str) -> None:
    sub, _ = get_effective_athlete_id(request)
    if not user_may_write(sub, athlete_id):
        raise PermissionDenied("You do not have write access to that athlete's data.")


class CourseListCreateView(APIView):
    def get(self, request: Request) -> Response:
        _, athlete_id = get_effective_athlete_id(request)
        _require_read(request, athlete_id)

        courses = Course.objects.filter(athlete_id=athlete_id)
        q = request.query_params.get("q", "").strip()
        if q:
            courses = courses.filter(Q(name__icontains=q) | Q(location_name__icontains=q))
        return Response({"data": CourseListSerializer(courses, many=True).data})

    def post(self, request: Request) -> Response:
        """Upload a GPX: multipart `gpx`, `name`, optional `location_name`."""
        _, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)

        file_obj = request.FILES.get("gpx")
        if not file_obj:
            raise ValidationError({"gpx": "This field is required."})
        name = (request.data.get("name") or "").strip()
        if not name:
            raise ValidationError({"name": "This field is required."})

        raw_bytes = file_obj.read()
        try:
            points = processing.parse_gpx_track(raw_bytes)
            fields = processing.process_track_points(points)
        except processing.GpxProcessingError as exc:
            raise UnprocessableEntityError(str(exc)) from exc

        location_name = (request.data.get("location_name") or "").strip() or reverse_geocode(
            fields["start_lat"], fields["start_lng"]
        )

        course = Course.objects.create(
            athlete_id=athlete_id, name=name, location_name=location_name, source="gpx", **fields
        )
        course.gpx_filename = file_obj.name
        course.gpx_file = default_storage.save(f"courses/{athlete_id}/{course.id}.gpx", io.BytesIO(raw_bytes))
        course.save(update_fields=["gpx_filename", "gpx_file"])

        return Response(CourseSerializer(course).data, status=status.HTTP_201_CREATED)


class CourseFromActivityView(APIView):
    def post(self, request: Request) -> Response:
        """`{activity_id, name}` - build a course from an activity's GPS track."""
        _, athlete_id = get_effective_athlete_id(request)
        _require_write(request, athlete_id)

        activity_id = request.data.get("activity_id")
        name = (request.data.get("name") or "").strip()
        if not activity_id:
            raise ValidationError({"activity_id": "This field is required."})
        if not name:
            raise ValidationError({"name": "This field is required."})

        activity = get_object_or_404(Activity, pk=activity_id)
        if activity.athlete_id != athlete_id:
            raise PermissionDenied("That activity does not belong to this athlete.")

        try:
            points = processing.track_points_from_activity(activity)
            fields = processing.process_track_points(points)
        except processing.GpxProcessingError as exc:
            raise UnprocessableEntityError(str(exc)) from exc

        location_name = reverse_geocode(fields["start_lat"], fields["start_lng"])
        course = Course.objects.create(
            athlete_id=athlete_id,
            name=name,
            location_name=location_name,
            source="activity",
            source_activity_id=activity_id,
            **fields,
        )
        return Response(CourseSerializer(course).data, status=status.HTTP_201_CREATED)


class CourseDetailView(APIView):
    def get(self, request: Request, id: str) -> Response:
        course = get_object_or_404(Course, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, course.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")
        return Response(CourseSerializer(course).data)

    def patch(self, request: Request, id: str) -> Response:
        course = get_object_or_404(Course, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, course.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        serializer = CourseUpdateSerializer(course, data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)
        serializer.save()
        return Response(CourseSerializer(course).data)

    def delete(self, request: Request, id: str) -> Response:
        course = get_object_or_404(Course, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_write(sub, course.athlete_id):
            raise PermissionDenied("You do not have write access to that athlete's data.")
        course.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)


def _generate_gpx(course: Course) -> bytes:
    """Minimal GPX for a course that has no original file (source=activity)."""
    points = "".join(f'<trkpt lat="{lat}" lon="{lng}"></trkpt>' for lat, lng in course.route_polyline)
    xml = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<gpx version="1.1" creator="Cadence">'
        f"<trk><name>{escape(course.name)}</name><trkseg>{points}</trkseg></trk>"
        "</gpx>"
    )
    return xml.encode("utf-8")


class CourseGpxView(APIView):
    def get(self, request: Request, id: str) -> HttpResponse:
        course = get_object_or_404(Course, pk=id)
        sub, _ = get_effective_athlete_id(request)
        if not user_may_read(sub, course.athlete_id):
            raise PermissionDenied("You do not have access to that athlete's data.")

        if course.gpx_file and default_storage.exists(course.gpx_file):
            with default_storage.open(course.gpx_file, "rb") as f:
                content = f.read()
            filename = course.gpx_filename or f"{course.name}.gpx"
        else:
            content = _generate_gpx(course)
            filename = f"{course.name}.gpx"

        response = HttpResponse(content, content_type="application/gpx+xml")
        response["Content-Disposition"] = f'attachment; filename="{filename}"'
        return response
