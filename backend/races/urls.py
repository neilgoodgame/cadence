from django.urls import path

from .views import RaceDetailView, RaceListCreateView

urlpatterns = [
    path("v1/races", RaceListCreateView.as_view(), name="race-list"),
    path("v1/races/<str:id>", RaceDetailView.as_view(), name="race-detail"),
]
