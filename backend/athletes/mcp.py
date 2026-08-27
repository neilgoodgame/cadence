"""MCP tools for athlete profile/zones/thresholds/best-efforts/fitness trend - mirrors the Java
backend's mcp/tools/athletes/*.java (AthleteProfileTools, BestEffortTools, FitnessTrendTools).
Reuses athletes/views.py's existing logic directly (Django has no separate service layer).
"""

from datetime import timedelta
from typing import Any

from django.shortcuts import get_object_or_404
from django.utils import timezone
from django.utils.dateparse import parse_date
from rest_framework.exceptions import ValidationError

from accounts.models import User
from activities.models import BestEffort
from authn.mcp_scopes import ACTIVITIES_READ
from authn.mcp_toolset import ScopedMCPToolset
from core.derived import DEFAULT_FITNESS_WINDOW_DAYS, compute_fitness_series

from .views import BEST_EFFORT_PERIOD_DAYS, LOWER_IS_BETTER_KINDS, cap_per_window, threshold_summary_for_field
from .zones import ZONE_TYPES, get_or_create_zone_set, reference_for

_THRESHOLD_FIELDS = ("ftp", "critical_run_power", "threshold_pace")


class AthleteMCPTools(ScopedMCPToolset):
    def get_me(self) -> dict[str, Any]:
        """Get the authenticated athlete's profile and training context: age, weight, FTP,
        critical run power, threshold pace/HR, and whether they coach other athletes. Useful as
        a first call to establish training-zone context before interpreting other tools'
        numbers."""
        self._require_scope(ACTIVITIES_READ)
        # Deliberately the delegated athlete (_effective_athlete_id), not the token's own
        # subject - matches every other tool in this file, and this tool's own docstring
        # promises the training context needed "to interpret other tools' numbers", which are
        # all athlete-scoped too. This used to read the raw sub instead - correct only while no
        # MCP credential could actually delegate, which stopped being true once PAT/OAuth2
        # delegation shipped (see core.auth_context.get_effective_athlete_id); surfaced live
        # when a virtual coach's get_me came back as its own empty profile instead of the
        # athlete's.
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)
        athlete = get_object_or_404(User, pk=athlete_id)

        return {
            "id": athlete.id,
            "name": athlete.name,
            "age": athlete.age,
            "weight_kg": athlete.weight_kg,
            "ftp": athlete.ftp,
            "ftp_calculation_method": athlete.ftp_calculation_method,
            "critical_run_power": athlete.critical_run_power,
            "threshold_pace": athlete.threshold_pace or None,
            "lthr": athlete.lthr,
            "max_hr": athlete.max_hr,
            "resting_hr": athlete.resting_hr,
            "is_coach": athlete.is_coach,
        }

    def get_athlete_zones(self) -> dict[str, Any]:
        """Get the authenticated athlete's training zones (heart rate, bike power, run power,
        pace) - each zone's name, %-of-threshold range, and the reference value it's computed
        from."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)
        athlete = get_object_or_404(User, pk=athlete_id)

        # Wrapped in {"data": [...]} rather than returned as a bare list - see
        # activities/mcp.py::get_activity_laps's comment for why (django-mcp-server serializes a
        # bare list as one content block per item).
        return {
            "data": [
                {
                    "type": zt,
                    "reference": reference_for(athlete, zt),
                    "zones": get_or_create_zone_set(athlete, zt).zones,
                }
                for zt in ZONE_TYPES
            ]
        }

    def get_athlete_thresholds(self) -> dict[str, Any]:
        """Get the authenticated athlete's current FTP, critical run power, and threshold pace -
        each with its previous value, the activity it was derived from, and whether it's gone
        stale (source activity aged out of the trailing window)."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)
        athlete = get_object_or_404(User, pk=athlete_id)

        result: dict[str, Any] = {}
        for field in _THRESHOLD_FIELDS:
            summary = threshold_summary_for_field(athlete, field)
            if summary["effective_from"] is not None:
                summary["effective_from"] = summary["effective_from"].isoformat()
            result[field] = summary
        return result

    def list_best_efforts(self, kind: str, period: str = "all") -> dict[str, Any]:
        """Get the authenticated athlete's best efforts for one metric (e.g. best 5-min power
        ever recorded) across all tracked durations, optionally restricted to a recent period.
        Useful for 'what's my best 20-minute power' or judging whether a recent activity set a
        new PR."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        if kind not in dict(BestEffort.KIND_CHOICES):
            raise ValidationError(
                {"kind": "Must be one of cycling_hr, cycling_power, running_hr, running_pace, running_power."}
            )
        if period not in ("4w", "3m", "16w", "1y", "all"):
            raise ValidationError({"period": "Must be one of 4w, 3m, 16w, 1y, all."})

        qs = BestEffort.objects.filter(athlete_id=athlete_id, kind=kind).order_by("window", "-value")
        if period in BEST_EFFORT_PERIOD_DAYS:
            cutoff = timezone.now().date() - timedelta(days=BEST_EFFORT_PERIOD_DAYS[period])
            qs = qs.filter(date__gte=cutoff)

        athlete = get_object_or_404(User, pk=athlete_id)
        capped = cap_per_window(list(qs), kind in LOWER_IS_BETTER_KINDS, athlete.best_effort_top_n)

        return {
            "kind": kind,
            "period": period,
            "data": [
                {
                    "window": e.window,
                    "value": e.value,
                    "unit": e.unit,
                    "date": e.date.isoformat(),
                    "activity_id": e.activity_id,
                }
                for e in capped
            ],
        }

    def get_fitness_trend(self, from_date: str | None = None, to_date: str | None = None) -> dict[str, Any]:
        """Get the authenticated athlete's daily CTL (fitness), ATL (fatigue), and TSB (form/
        freshness) trend over a date range - default is the trailing 84 days. Useful for 'am I
        overtrained' or 'when's my next good taper window' questions."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        to_d = parse_date(to_date) if to_date else timezone.now().date()
        if to_d is None:
            raise ValidationError({"to_date": "Must be a date in YYYY-MM-DD format."})
        from_d = parse_date(from_date) if from_date else to_d - timedelta(days=DEFAULT_FITNESS_WINDOW_DAYS)
        if from_d is None:
            raise ValidationError({"from_date": "Must be a date in YYYY-MM-DD format."})
        if from_d > to_d:
            raise ValidationError({"from_date": "Must not be after to_date."})

        series = compute_fitness_series(athlete_id, from_d, to_d)
        # Wrapped in {"data": [...]} rather than returned as a bare list - see
        # activities/mcp.py::get_activity_laps's comment for why.
        return {
            "data": [{"date": p["date"].isoformat(), "ctl": p["ctl"], "atl": p["atl"], "tsb": p["tsb"]} for p in series]
        }
