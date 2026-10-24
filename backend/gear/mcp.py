"""MCP tools for gear (bikes/shoes) - mirrors the Java backend's mcp/tools/gear/GearReadTools.java
and GearWriteTools.java. Reuses gear/views.py's and gear/serializers.py's existing queryset/
validation logic directly.
"""

from typing import Any

from django.db.models import Q
from django.shortcuts import get_object_or_404
from rest_framework.exceptions import ValidationError

from accounts.models import User
from authn.mcp_scopes import ACTIVITIES_READ, GEAR_WRITE
from authn.mcp_toolset import ScopedMCPToolset
from core.auth_context import get_effective_athlete_id
from core.exceptions import ConflictError

from .models import Bike, Shoe, ShoeModel, ShoeModelVersion
from .serializers import BikeCreateSerializer, BikeUpdateSerializer, ShoeUpdateSerializer
from .views import _display_name


def _bike_summary(bike: Bike) -> dict[str, Any]:
    return {
        "id": bike.id,
        "athlete_id": bike.athlete_id,
        "name": bike.name,
        "kind": bike.kind,
        "groupset": bike.groupset,
        "distance_km": bike.distance_km,
        "hours": bike.hours,
        "rides": bike.rides,
        "components": bike.components.count(),
    }


def _shoe_summary(shoe: Shoe) -> dict[str, Any]:
    return {
        "id": shoe.id,
        "athlete_id": shoe.athlete_id,
        "shoe_model_version_id": shoe.shoe_model_version_id,
        "manufacturer": shoe.shoe_model_version.shoe_model.manufacturer,
        "model": shoe.shoe_model_version.shoe_model.model,
        "version": shoe.shoe_model_version.version,
        "colourway": shoe.colourway,
        "name": shoe.name,
        "image": shoe.image,
        "role": shoe.role,
        "km": shoe.km,
        "limit_km": shoe.limit_km,
        "since": shoe.since.isoformat(),
    }


class GearMCPTools(ScopedMCPToolset):
    def list_bikes(self) -> dict[str, Any]:
        """List the authenticated athlete's bikes - name, kind, groupset, and lifetime distance/
        hours/rides. Useful for correlating an activity's power data with which bike was used."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        bikes = Bike.objects.filter(athlete_id=athlete_id).order_by("-id")
        # Wrapped in {"data": [...]} rather than returned as a bare list - see
        # activities/mcp.py::get_activity_laps's comment for why.
        return {"data": [_bike_summary(b) for b in bikes]}

    def list_shoes(self) -> dict[str, Any]:
        """List the authenticated athlete's running shoes - model, role (e.g. daily trainer,
        race), and distance logged vs. its retirement limit."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        shoes = (
            Shoe.objects.filter(athlete_id=athlete_id, retired=False)
            .select_related("shoe_model_version", "shoe_model_version__shoe_model")
            .order_by("-id")
        )
        return {"data": [_shoe_summary(s) for s in shoes]}

    def search_shoe_catalog(self, q: str | None = None) -> dict[str, Any]:
        """Search the shared shoe catalog (manufacturer + model + version) for a
        shoe_model_version_id to pass to create_shoe. The catalog is shared across every
        athlete - if nothing matches, use create_shoe_catalog_entry to add a new one first."""
        self._require_scope(ACTIVITIES_READ)
        versions = ShoeModelVersion.objects.select_related("shoe_model").order_by(
            "shoe_model__manufacturer", "shoe_model__model"
        )
        q = (q or "").strip()
        if q:
            versions = versions.filter(Q(shoe_model__manufacturer__icontains=q) | Q(shoe_model__model__icontains=q))
        return {
            "data": [
                {
                    "shoe_model_version_id": v.id,
                    "manufacturer": v.shoe_model.manufacturer,
                    "model": v.shoe_model.model,
                    "version": v.version,
                    "display_name": _display_name(v.shoe_model.manufacturer, v.shoe_model.model, v.version),
                }
                for v in versions
            ]
        }

    def create_bike(
        self,
        name: str,
        kind: str | None = None,
        groupset: str | None = None,
        distance_km: int | None = None,
    ) -> dict[str, Any]:
        """Add a bike to the athlete's gear library. kind is one of road, indoor, gravel, tt
        (optional); groupset is freeform (e.g. "Shimano Ultegra Di2"); distance_km seeds its
        lifetime distance, defaulting to 0."""
        self._require_scope(GEAR_WRITE)
        athlete_id = self._effective_athlete_id()
        self._require_write(athlete_id)

        payload: dict[str, Any] = {"name": name}
        if kind is not None:
            payload["kind"] = kind
        if groupset is not None:
            payload["groupset"] = groupset
        if distance_km is not None:
            payload["distance_km"] = distance_km
        serializer = BikeCreateSerializer(data=payload)
        serializer.is_valid(raise_exception=True)
        bike = Bike.objects.create(athlete_id=athlete_id, **serializer.validated_data)
        return _bike_summary(bike)

    def update_bike(
        self,
        bike_id: str,
        name: str | None = None,
        kind: str | None = None,
        groupset: str | None = None,
        distance_km: int | None = None,
    ) -> dict[str, Any]:
        """Update a bike's name/kind/groupset/lifetime distance - only the fields you pass are
        changed."""
        self._require_scope(GEAR_WRITE)
        bike = get_object_or_404(Bike, pk=bike_id)
        self._require_write(bike.athlete_id)

        payload: dict[str, Any] = {}
        if name is not None:
            payload["name"] = name
        if kind is not None:
            payload["kind"] = kind
        if groupset is not None:
            payload["groupset"] = groupset
        if distance_km is not None:
            payload["distance_km"] = distance_km
        serializer = BikeUpdateSerializer(bike, data=payload, partial=True)
        serializer.is_valid(raise_exception=True)
        serializer.save()
        return _bike_summary(bike)

    def delete_bike(self, bike_id: str) -> dict[str, Any]:
        """Permanently delete a bike from the gear library. Any tracked wear components are
        deleted too; activities previously linked to it keep their history but lose the link."""
        self._require_scope(GEAR_WRITE)
        bike = get_object_or_404(Bike, pk=bike_id)
        self._require_write(bike.athlete_id)
        bike.delete()
        return {"id": bike_id, "deleted": True}

    def create_shoe(
        self,
        shoe_model_version_id: str,
        colourway: str,
        name: str | None = None,
        limit_km: int | None = None,
        image: str | None = None,
    ) -> dict[str, Any]:
        """Add a pair of shoes to the athlete's gear library. shoe_model_version_id comes from
        search_shoe_catalog. name defaults to "<manufacturer> <model> <version> <colourway>" and
        must be unique per athlete; limit_km defaults to the athlete's own default_shoe_limit_km
        preference."""
        self._require_scope(GEAR_WRITE)
        athlete_id = self._effective_athlete_id()
        self._require_write(athlete_id)

        shoe_model_version = get_object_or_404(
            ShoeModelVersion.objects.select_related("shoe_model"), pk=shoe_model_version_id
        )
        resolved_name = name or " ".join(
            part
            for part in (
                shoe_model_version.shoe_model.manufacturer,
                shoe_model_version.shoe_model.model,
                shoe_model_version.version,
                colourway,
            )
            if part
        )
        if Shoe.objects.filter(athlete_id=athlete_id, name=resolved_name).exists():
            raise ConflictError("A pair of shoes with that name already exists.")

        resolved_limit_km = (
            limit_km
            if limit_km is not None
            else User.objects.values_list("default_shoe_limit_km", flat=True).get(pk=athlete_id)
        )

        shoe = Shoe.objects.create(
            athlete_id=athlete_id,
            shoe_model_version=shoe_model_version,
            colourway=colourway,
            name=resolved_name,
            limit_km=resolved_limit_km,
            image=image,
        )
        return _shoe_summary(shoe)

    def update_shoe(
        self,
        shoe_id: str,
        name: str | None = None,
        limit_km: int | None = None,
        km: int | None = None,
        image: str | None = None,
        retired: bool | None = None,
    ) -> dict[str, Any]:
        """Update a pair of shoes - only the fields you pass are changed. retired soft-archives
        it (excluded from list_shoes) without deleting its history."""
        self._require_scope(GEAR_WRITE)
        shoe = get_object_or_404(
            Shoe.objects.select_related("shoe_model_version", "shoe_model_version__shoe_model"), pk=shoe_id
        )
        self._require_write(shoe.athlete_id)

        if name is not None and Shoe.objects.filter(athlete_id=shoe.athlete_id, name=name).exclude(pk=shoe.pk).exists():
            raise ConflictError("A pair of shoes with that name already exists.")

        payload: dict[str, Any] = {}
        if name is not None:
            payload["name"] = name
        if limit_km is not None:
            payload["limit_km"] = limit_km
        if km is not None:
            payload["km"] = km
        if image is not None:
            payload["image"] = image
        if retired is not None:
            payload["retired"] = retired
        serializer = ShoeUpdateSerializer(shoe, data=payload, partial=True)
        serializer.is_valid(raise_exception=True)
        serializer.save()
        return _shoe_summary(shoe)

    def delete_shoe(self, shoe_id: str) -> dict[str, Any]:
        """Permanently delete a pair of shoes from the gear library, including its wear photos.
        Activities previously linked to it keep their history but lose the link."""
        self._require_scope(GEAR_WRITE)
        shoe = get_object_or_404(Shoe, pk=shoe_id)
        self._require_write(shoe.athlete_id)
        shoe.delete()
        return {"id": shoe_id, "deleted": True}

    def create_shoe_catalog_entry(self, manufacturer: str, model: str, version: str = "") -> dict[str, Any]:
        """Add a new manufacturer/model/version to the shared shoe catalog (used by
        search_shoe_catalog and create_shoe), for when nothing existing matches. The catalog is
        shared across every athlete - search first to avoid creating a near-duplicate entry."""
        self._require_scope(GEAR_WRITE)
        if not manufacturer or not manufacturer.strip():
            raise ValidationError({"manufacturer": "manufacturer is required."})
        if not model or not model.strip():
            raise ValidationError({"model": "model is required."})
        sub, _ = get_effective_athlete_id(self.request)

        shoe_model = ShoeModel.objects.create(manufacturer=manufacturer, model=model, created_by_id=sub)
        smv = ShoeModelVersion.objects.create(shoe_model=shoe_model, version=version)
        return {
            "shoe_model_version_id": smv.id,
            "manufacturer": shoe_model.manufacturer,
            "model": shoe_model.model,
            "version": smv.version,
            "display_name": _display_name(shoe_model.manufacturer, shoe_model.model, smv.version),
        }
