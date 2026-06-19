from django.urls import path

from .views import (
    BikeDetailView,
    BikeListCreateView,
    ComponentDetailView,
    ComponentListCreateView,
    ComponentServiceView,
    ShoeCatalogView,
    ShoeDetailView,
    ShoeListCreateView,
)

urlpatterns = [
    path("v1/gear/bikes", BikeListCreateView.as_view(), name="gear-bike-list"),
    path("v1/gear/bikes/<str:id>", BikeDetailView.as_view(), name="gear-bike-detail"),
    path("v1/gear/bikes/<str:id>/components", ComponentListCreateView.as_view(), name="gear-component-list"),
    path("v1/gear/components/<str:id>", ComponentDetailView.as_view(), name="gear-component-detail"),
    path("v1/gear/components/<str:id>/service", ComponentServiceView.as_view(), name="gear-component-service"),
    path("v1/gear/shoes", ShoeListCreateView.as_view(), name="gear-shoe-list"),
    path("v1/gear/shoes/<str:id>", ShoeDetailView.as_view(), name="gear-shoe-detail"),
    path("v1/gear/shoe-catalog", ShoeCatalogView.as_view(), name="gear-shoe-catalog"),
]
