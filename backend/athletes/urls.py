from django.urls import path

from .views import AthleteDetailView, BestEffortListView, FitnessListView, ZoneSetDetailView, ZoneSetListView

urlpatterns = [
    path("v1/athletes/<str:id>", AthleteDetailView.as_view(), name="athlete-detail"),
    path("v1/athletes/<str:id>/zones", ZoneSetListView.as_view(), name="athlete-zones"),
    path("v1/athletes/<str:id>/zones/<str:type>", ZoneSetDetailView.as_view(), name="athlete-zone-detail"),
    path("v1/athletes/<str:id>/best-efforts", BestEffortListView.as_view(), name="athlete-best-efforts"),
    path("v1/athletes/<str:id>/fitness", FitnessListView.as_view(), name="athlete-fitness"),
]
