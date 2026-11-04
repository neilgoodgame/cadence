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
from authn.mcp_scopes import ACTIVITIES_READ, ACTIVITIES_WRITE
from authn.mcp_toolset import ScopedMCPToolset
from core.derived import DEFAULT_FITNESS_WINDOW_DAYS, compute_fitness_series

from .models import ThresholdHistory
from .threshold_history import candidate_statuses
from .views import (
    BEST_EFFORT_PERIOD_DAYS,
    LOWER_IS_BETTER_KINDS,
    _apply_threshold_patch,
    _validate_threshold_field,
    cap_per_window,
    threshold_summary_for_field,
)
from .zones import ZONE_TYPES, get_or_create_zone_set, reference_for

_THRESHOLD_FIELDS = ("ftp", "critical_run_power", "threshold_pace")
# Real training sports only - multisport/transition are structural FIT-import artifacts, not
# something an athlete would ever ask for a fitness trend of.
_FITNESS_TREND_SPORTS = ("bike", "run", "swim", "walk", "row")


def _thresholds_summary(athlete: User) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for field in _THRESHOLD_FIELDS:
        summary = threshold_summary_for_field(athlete, field)
        if summary["effective_from"] is not None:
            summary["effective_from"] = summary["effective_from"].isoformat()
        result[field] = summary
    return result


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
            "running_power_source": athlete.running_power_source,
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
        return _thresholds_summary(athlete)

    def set_athlete_thresholds(
        self,
        ftp: int | None = None,
        critical_run_power: int | None = None,
        threshold_pace: str | None = None,
        effective_from: str | None = None,
        rerate_activities: bool = True,
        preview: bool = False,
    ) -> dict[str, Any]:
        """Set the athlete's FTP, critical running power, and/or threshold pace directly - e.g.
        from a recent field test, or a coach's assessment. Equivalent to editing these fields in
        Preferences: only the fields you pass are changed, and each changed value is trusted
        unconditionally and recorded in the threshold history ledger (it still ages out and can
        be superseded by a later qualifying activity or another edit, same as any other entry).
        Pace is "M:SS" per km.

        `effective_from` (YYYY-MM-DD, default today) backdates the edit - it's always written as
        a ledger row, but only becomes the live value if no later row already exists and it
        isn't already outside threshold_window_days as of today (otherwise it's a correction-
        only row: still used to re-rate past activities, excluded from "what's current").
        `rerate_activities` (default true) recomputes intensity/TSS for every activity of the
        affected sport on/after `effective_from`. `preview=true` computes the same outcome
        (including which activities would be re-rated and their current vs. new intensity/TSS)
        without saving anything - use it to check the impact before committing.

        Returns the athlete's threshold summary (same shape as get_athlete_thresholds) plus
        `threshold_edit_results`, keyed by whichever field(s) were passed: `changed`,
        `became_current`, `is_correction_only`, `rerated_count`, and `would_rerate` (populated
        only when `preview=true`)."""
        self._require_scope(ACTIVITIES_WRITE)
        athlete_id = self._effective_athlete_id()
        sub = self._require_write(athlete_id)

        data: dict[str, Any] = {}
        if ftp is not None:
            data["ftp"] = ftp
        if critical_run_power is not None:
            data["critical_run_power"] = critical_run_power
        if threshold_pace is not None:
            data["threshold_pace"] = threshold_pace
        if not data:
            raise ValidationError({"error": "Pass at least one of ftp, critical_run_power, threshold_pace."})
        if effective_from is not None:
            data["effective_from"] = effective_from
        data["rerate_activities"] = rerate_activities
        data["preview"] = preview

        athlete = get_object_or_404(User, pk=athlete_id)
        set_by = athlete if sub == athlete_id else get_object_or_404(User, pk=sub)
        _, threshold_results = _apply_threshold_patch(athlete, data, set_by)

        result = _thresholds_summary(athlete)
        result["threshold_edit_results"] = {
            field: {
                "changed": r.changed,
                "became_current": r.became_current,
                "is_correction_only": r.is_correction_only,
                "rerated_count": r.rerated_count,
                "would_rerate": [
                    {
                        "activity_id": d.activity_id,
                        "date": d.date.isoformat(),
                        "old_intensity": d.old_intensity,
                        "new_intensity": d.new_intensity,
                        "old_tss": d.old_tss,
                        "new_tss": d.new_tss,
                    }
                    for d in r.would_rerate
                ],
            }
            for field, r in threshold_results.items()
        }
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

    def get_fitness_trend(
        self, from_date: str | None = None, to_date: str | None = None, sport: str | None = None
    ) -> dict[str, Any]:
        """Get the authenticated athlete's daily CTL (fitness), ATL (fatigue), and TSB (form/
        freshness) trend over a date range - default is the trailing 84 days. Useful for 'am I
        overtrained' or 'when's my next good taper window' questions. `sport` (bike/run/swim/
        walk/row), if given, restricts the series to just that sport's activities - e.g. a
        bike-only CTL to compare against FTP, independent of running load."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        if sport is not None and sport not in _FITNESS_TREND_SPORTS:
            raise ValidationError({"sport": "Must be one of bike, run, swim, walk, row."})
        to_d = parse_date(to_date) if to_date else timezone.now().date()
        if to_d is None:
            raise ValidationError({"to_date": "Must be a date in YYYY-MM-DD format."})
        from_d = parse_date(from_date) if from_date else to_d - timedelta(days=DEFAULT_FITNESS_WINDOW_DAYS)
        if from_d is None:
            raise ValidationError({"from_date": "Must be a date in YYYY-MM-DD format."})
        if from_d > to_d:
            raise ValidationError({"from_date": "Must not be after to_date."})

        series = compute_fitness_series(athlete_id, from_d, to_d, sport)
        # Wrapped in {"data": [...]} rather than returned as a bare list - see
        # activities/mcp.py::get_activity_laps's comment for why.
        return {
            "data": [{"date": p["date"].isoformat(), "ctl": p["ctl"], "atl": p["atl"], "tsb": p["tsb"]} for p in series]
        }

    def list_threshold_history(
        self, field: str, from_date: str | None = None, to_date: str | None = None
    ) -> dict[str, Any]:
        """Get the full threshold-history ledger for one field, most recent first - every
        measured, manual, or decayed (see `kind`) entry, not just the current/previous pair
        get_athlete_thresholds returns. Useful for seeing exactly when the athlete was actually
        measured (vs. manually set or, once decay ships, eased down), and what a model would
        have had to predict between those dates. Optionally restricted to entries whose
        effective_from falls within [from_date, to_date]."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)
        field = _validate_threshold_field(field)
        athlete = get_object_or_404(User, pk=athlete_id)

        entries = ThresholdHistory.objects.filter(athlete=athlete, field=field)
        if from_date is not None:
            from_d = parse_date(from_date)
            if from_d is None:
                raise ValidationError({"from_date": "Must be a date in YYYY-MM-DD format."})
            entries = entries.filter(effective_from__gte=from_d)
        if to_date is not None:
            to_d = parse_date(to_date)
            if to_d is None:
                raise ValidationError({"to_date": "Must be a date in YYYY-MM-DD format."})
            entries = entries.filter(effective_from__lte=to_d)
        entries = entries.order_by("-effective_from")

        # Wrapped in {"data": [...]} rather than returned as a bare list - see
        # activities/mcp.py::get_activity_laps's comment for why.
        return {
            "field": field,
            "data": [
                {
                    "value": entry.value_pace if field == "threshold_pace" else entry.value_numeric,
                    "kind": entry.kind,
                    "source_activity_id": entry.source_activity_id,
                    "set_by_user_id": entry.set_by_id,
                    "effective_from": entry.effective_from.isoformat(),
                    "current_from": entry.current_from.isoformat(),
                    "is_correction_only": entry.is_correction_only,
                }
                for entry in entries
            ],
        }

    def list_threshold_candidates(self, field: str, from_date: str, to_date: str) -> dict[str, Any]:
        """For every one of the athlete's activities of `field`'s sport between from_date and
        to_date, return the implied value it would contribute to the rolling-window algorithm
        (e.g. 0.95 x best 20-minute power for ftp), the raw pre-transform value, the window
        label, and its status: 'qualified' (within the sanity band), 'rejected_sanity' (outside
        it), 'accepted' (explicitly accepted despite failing the band), or 'too_short' (no
        qualifying effort at all, e.g. too short for the field's window). Classified against the
        athlete's *current* profile value, a single snapshot rather than a moving reference - see
        current_window_value's own docstring for that distinction. Useful for seeing what a model
        would have had to predict between ledger entries, without reconstructing it by hand from
        stream summaries."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)
        field = _validate_threshold_field(field)
        from_d = parse_date(from_date)
        if from_d is None:
            raise ValidationError({"from_date": "Must be a date in YYYY-MM-DD format."})
        to_d = parse_date(to_date)
        if to_d is None:
            raise ValidationError({"to_date": "Must be a date in YYYY-MM-DD format."})
        if from_d > to_d:
            raise ValidationError({"from_date": "Must not be after to_date."})

        athlete = get_object_or_404(User, pk=athlete_id)
        candidates = candidate_statuses(athlete, field, from_d, to_d)
        # Wrapped in {"data": [...]} rather than returned as a bare list - see
        # activities/mcp.py::get_activity_laps's comment for why.
        return {
            "field": field,
            "data": [
                {
                    "activity_id": c.activity_id,
                    "date": c.date.isoformat(),
                    "status": c.status,
                    "implied_value": c.implied_value,
                    "raw_value": c.raw_value,
                    "window": c.window,
                }
                for c in candidates
            ],
        }

    def get_threshold_settings(self) -> dict[str, Any]:
        """Get the authenticated athlete's threshold-related settings: the trailing window
        (threshold_window_days) and sanity-band width (threshold_sanity_pct) the rolling-window
        algorithm uses, and which power-calculation method/source each threshold field is
        derived from. Useful for knowing which parameters a given ledger was produced under
        before calibrating anything against it."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)
        athlete = get_object_or_404(User, pk=athlete_id)
        return {
            "threshold_window_days": athlete.threshold_window_days,
            "threshold_sanity_pct": athlete.threshold_sanity_pct,
            "ftp_calculation_method": athlete.ftp_calculation_method,
            "running_power_source": athlete.running_power_source,
        }
