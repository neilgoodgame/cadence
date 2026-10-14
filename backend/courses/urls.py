from django.urls import path

from .views import (
    AthleteRecomputeCourseElevationView,
    CourseDetailView,
    CourseFromActivityView,
    CourseGpxView,
    CourseListCreateView,
    CourseRecomputeElevationView,
)

urlpatterns = [
    path("v1/courses", CourseListCreateView.as_view(), name="course-list"),
    path("v1/courses/from-activity", CourseFromActivityView.as_view(), name="course-from-activity"),
    path("v1/courses/<str:id>", CourseDetailView.as_view(), name="course-detail"),
    path("v1/courses/<str:id>/gpx", CourseGpxView.as_view(), name="course-gpx"),
    path(
        "v1/courses/<str:id>/recompute-elevation",
        CourseRecomputeElevationView.as_view(),
        name="course-recompute-elevation",
    ),
    path(
        "v1/athletes/<str:id>/recompute-elevation",
        AthleteRecomputeCourseElevationView.as_view(),
        name="athlete-recompute-course-elevation",
    ),
]
