from django.urls import path

from .views import (
    AthleteDetailView,
    BestEffortExcludeView,
    BestEffortListView,
    BestEffortRecomputeView,
    BestEffortTrimView,
    FitnessListView,
    RecomputeAthleteTssView,
    ZoneSetDetailView,
    ZoneSetListView,
)

urlpatterns = [
    path("v1/athletes/<str:id>", AthleteDetailView.as_view(), name="athlete-detail"),
    path("v1/athletes/<str:id>/zones", ZoneSetListView.as_view(), name="athlete-zones"),
    path("v1/athletes/<str:id>/zones/<str:type>", ZoneSetDetailView.as_view(), name="athlete-zone-detail"),
    path("v1/athletes/<str:id>/best-efforts", BestEffortListView.as_view(), name="athlete-best-efforts"),
    path(
        "v1/athletes/<str:id>/best-efforts/by-activity/<str:activity_id>",
        BestEffortExcludeView.as_view(),
        name="athlete-best-efforts-exclude",
    ),
    path(
        "v1/athletes/<str:id>/best-efforts/recompute",
        BestEffortRecomputeView.as_view(),
        name="athlete-best-efforts-recompute",
    ),
    path("v1/athletes/<str:id>/best-efforts/trim", BestEffortTrimView.as_view(), name="athlete-best-efforts-trim"),
    path("v1/athletes/<str:id>/fitness", FitnessListView.as_view(), name="athlete-fitness"),
    path("v1/athletes/<str:id>/recompute-tss", RecomputeAthleteTssView.as_view(), name="athlete-recompute-tss"),
]
