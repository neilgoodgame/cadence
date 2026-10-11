from django.db import models

from accounts.models import User
from activities.models import Activity
from core.models import PrefixedIDModel

SOURCE_CHOICES = [
    ("gpx", "GPX"),
    ("activity", "Activity"),
]


class Course(PrefixedIDModel):
    id_prefix = "course"

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="courses")
    name = models.CharField(max_length=200)
    location_name = models.CharField(max_length=200, blank=True, default="")
    start_lat = models.FloatField(null=True, blank=True)
    start_lng = models.FloatField(null=True, blank=True)
    distance_km = models.FloatField(null=True, blank=True)
    elevation_gain_m = models.IntegerField(null=True, blank=True)
    elevation_loss_m = models.IntegerField(null=True, blank=True)
    elevation_max_m = models.IntegerField(null=True, blank=True)
    elevation_min_m = models.IntegerField(null=True, blank=True)
    is_loop = models.BooleanField(default=False)
    source = models.CharField(max_length=10, choices=SOURCE_CHOICES)
    source_activity = models.ForeignKey(
        Activity, null=True, blank=True, on_delete=models.SET_NULL, related_name="source_courses"
    )
    # A path into django.core.files.storage.default_storage, same convention as
    # uploads.models.Upload.stored_path - not a DB blob. Blank for a course created
    # from an activity (no original file to keep); the GPX download endpoint
    # regenerates one from route_polyline/elevation_profile in that case.
    gpx_file = models.CharField(max_length=255, blank=True, default="")
    gpx_filename = models.CharField(max_length=255, blank=True, default="")
    # Simplified [[lat, lng], ...] points (Douglas-Peucker, ~300-500 points).
    route_polyline = models.JSONField(default=list)
    # [[km, elevation_m], ...], ~200 points evenly spaced by distance.
    elevation_profile = models.JSONField(default=list)
    # [{start_km, end_km, gain_m, avg_grade_pct}, ...]
    climbs = models.JSONField(default=list)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-created_at"]

    def __str__(self) -> str:
        return self.name
