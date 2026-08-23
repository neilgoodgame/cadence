"""MCP tools for gear (bikes/shoes) - mirrors the Java backend's mcp/tools/gear/GearReadTools.java.
Reuses gear/views.py's existing queryset logic directly.
"""

from typing import Any

from authn.mcp_scopes import ACTIVITIES_READ
from authn.mcp_toolset import ScopedMCPToolset

from .models import Bike, Shoe


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
        return {
            "data": [
                {
                    "id": b.id,
                    "athlete_id": b.athlete_id,
                    "name": b.name,
                    "kind": b.kind,
                    "groupset": b.groupset,
                    "distance_km": b.distance_km,
                    "hours": b.hours,
                    "rides": b.rides,
                    "components": b.components.count(),
                }
                for b in bikes
            ]
        }

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
        return {
            "data": [
                {
                    "id": s.id,
                    "athlete_id": s.athlete_id,
                    "shoe_model_version_id": s.shoe_model_version_id,
                    "manufacturer": s.shoe_model_version.shoe_model.manufacturer,
                    "model": s.shoe_model_version.shoe_model.model,
                    "version": s.shoe_model_version.version,
                    "colourway": s.colourway,
                    "name": s.name,
                    "image": s.image,
                    "role": s.role,
                    "km": s.km,
                    "limit_km": s.limit_km,
                    "since": s.since.isoformat(),
                }
                for s in shoes
            ]
        }
