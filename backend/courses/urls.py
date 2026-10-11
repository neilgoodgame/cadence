from django.urls import path

from .views import CourseDetailView, CourseFromActivityView, CourseGpxView, CourseListCreateView

urlpatterns = [
    path("v1/courses", CourseListCreateView.as_view(), name="course-list"),
    path("v1/courses/from-activity", CourseFromActivityView.as_view(), name="course-from-activity"),
    path("v1/courses/<str:id>", CourseDetailView.as_view(), name="course-detail"),
    path("v1/courses/<str:id>/gpx", CourseGpxView.as_view(), name="course-gpx"),
]
