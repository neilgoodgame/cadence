"""Keeps Bike.distance_km/Shoe.km in sync with the activities currently linked to them.

Both fields are also directly, manually editable (the /gear screen, CSV import, data
export/import) — this only ever applies a *delta* on top of whatever's there, it never
treats the field as purely computed.
"""

from .models import Bike, Shoe


def _apply_bike_delta(bike_id: str | None, delta: int) -> None:
    if not bike_id or not delta:
        return
    bike = Bike.objects.get(pk=bike_id)
    bike.distance_km += delta
    bike.save(update_fields=["distance_km"])


def _apply_shoe_delta(shoe_id: str | None, delta: int) -> None:
    if not shoe_id or not delta:
        return
    shoe = Shoe.objects.get(pk=shoe_id)
    shoe.km += delta
    shoe.save(update_fields=["km"])


def apply_bike_change(old_bike_id: str | None, new_bike_id: str | None, distance_km: float) -> None:
    """Call whenever an activity's bike link changes (set, cleared, or swapped) — moves the
    activity's distance off the old bike and onto the new one. A no-op if the id didn't change."""
    if old_bike_id == new_bike_id:
        return
    delta = round(distance_km)
    _apply_bike_delta(old_bike_id, -delta)
    _apply_bike_delta(new_bike_id, delta)


def apply_shoe_change(old_shoe_id: str | None, new_shoe_id: str | None, distance_km: float) -> None:
    """Same as apply_bike_change, for a shoe link."""
    if old_shoe_id == new_shoe_id:
        return
    delta = round(distance_km)
    _apply_shoe_delta(old_shoe_id, -delta)
    _apply_shoe_delta(new_shoe_id, delta)
