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
from activities.models import Activity, BestEffort
from authn.mcp_scopes import ACTIVITIES_READ, ACTIVITIES_WRITE
from authn.mcp_toolset import ScopedMCPToolset
from core.derived import DEFAULT_FITNESS_WINDOW_DAYS, compute_fitness_series

from .decay_load import above_z2_load_for_activity
from .models import ThresholdHistory
from .threshold_decay_recompute import gather_preview_steps
from .threshold_history import FIELD_SPORT, _seconds_to_mmss, candidate_statuses
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

    def list_best_efforts(self, kind: str, period: str = "all", include_duplicates: bool = False) -> dict[str, Any]:
        """Get the authenticated athlete's best efforts for one metric (e.g. best 5-min power
        ever recorded) across all tracked durations, optionally restricted to a recent period.
        Useful for 'what's my best 20-minute power' or judging whether a recent activity set a
        new PR. `include_duplicates` (default false) also considers same-ride-twice duplicate
        copies' own best-effort rows, which are otherwise excluded entirely (issue #498)."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)

        if kind not in dict(BestEffort.KIND_CHOICES):
            raise ValidationError(
                {"kind": "Must be one of cycling_hr, cycling_power, running_hr, running_pace, running_power."}
            )
        if period not in ("4w", "3m", "16w", "1y", "all"):
            raise ValidationError({"period": "Must be one of 4w, 3m, 16w, 1y, all."})

        qs = BestEffort.objects.filter(athlete_id=athlete_id, kind=kind)
        if not include_duplicates:
            qs = qs.filter(activity__primary_activity__isnull=True)
        qs = qs.order_by("window", "-value")
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
        self,
        from_date: str | None = None,
        to_date: str | None = None,
        sport: str | None = None,
        include_duplicates: bool = False,
    ) -> dict[str, Any]:
        """Get the authenticated athlete's daily CTL (fitness), ATL (fatigue), and TSB (form/
        freshness) trend over a date range - default is the trailing 84 days. Useful for 'am I
        overtrained' or 'when's my next good taper window' questions. `sport` (bike/run/swim/
        walk/row), if given, restricts the series to just that sport's activities - e.g. a
        bike-only CTL to compare against FTP, independent of running load. `include_duplicates`
        (default false) counts same-ride-twice duplicate copies' TSS too, which otherwise never
        counts towards the trend (issue #498) - useful for seeing how inflated the trend used to
        be before a pair was resolved, not for everyday use."""
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

        series = compute_fitness_series(athlete_id, from_d, to_d, sport, include_duplicates=include_duplicates)
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
                    "created_at": entry.created_at.isoformat(),
                }
                for entry in entries
            ],
        }

    def list_threshold_candidates(
        self, field: str, from_date: str, to_date: str, include_duplicates: bool = False
    ) -> dict[str, Any]:
        """For every one of the athlete's activities of `field`'s sport between from_date and
        to_date, return the implied value it would contribute to the rolling-window algorithm
        (e.g. 0.95 x best 20-minute power for ftp), the raw pre-transform value, the window
        label, and its status: 'qualified' (within the sanity band), 'rejected_sanity' (outside
        it), 'accepted' (explicitly accepted despite failing the band), or 'too_short' (no
        qualifying effort at all, e.g. too short for the field's window). Classified against the
        athlete's *current* profile value, a single snapshot rather than a moving reference - see
        current_window_value's own docstring for that distinction. Useful for seeing what a model
        would have had to predict between ledger entries, without reconstructing it by hand from
        stream summaries. `include_duplicates` (default false) also classifies same-ride-twice
        duplicate copies, which are otherwise excluded entirely (issue #498) - useful for
        inspecting a specific duplicate pair rather than everyday candidate review."""
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
        candidates = candidate_statuses(athlete, field, from_d, to_d, include_duplicates=include_duplicates)
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
            "bike_power_source": athlete.bike_power_source,
        }

    def preview_threshold_decay(
        self,
        field: str,
        from_date: str,
        to_date: str,
        tau_days: float | None = None,
        min_pct: float | None = None,
        max_pct: float | None = None,
    ) -> dict[str, Any]:
        """Issue #487: models what the threshold-decay ledger would look like for field between
        from_date and to_date, using tau_days/min_pct/max_pct in place of the athlete's own saved
        decay settings where given (any omitted one falls back to the athlete's saved value) -
        for calibrating those parameters against real history before turning decay on, or trying
        alternatives once it already is. Never writes to the ledger, and the simulation always
        runs as if decay were enabled regardless of the athlete's own threshold_decay_enabled -
        otherwise every override here would be silently ignored. settings_used reports the
        tau_days/min_pct/max_pct the simulation actually ran with."""
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
        # Same bounds AthleteUpdateSerializer enforces on the saved settings (athletes/
        # serializers.py) - checked against the effective (override-or-saved) value, so a
        # tau_days=0 override can't reach threshold_decay.py's math.exp(-1.0/tau_days) and
        # raise a ZeroDivisionError instead of a clean validation error.
        effective_tau = tau_days if tau_days is not None else athlete.threshold_decay_tau_days
        effective_min = min_pct if min_pct is not None else athlete.threshold_decay_min_pct
        effective_max = max_pct if max_pct is not None else athlete.threshold_decay_max_pct
        if not (7 <= effective_tau <= 90):
            raise ValidationError({"tau_days": "Must be between 7 and 90."})
        if not (0 <= effective_min <= 10):
            raise ValidationError({"min_pct": "Must be between 0 and 10."})
        if not (0 <= effective_max <= 30):
            raise ValidationError({"max_pct": "Must be between 0 and 30."})
        if effective_min > effective_max:
            raise ValidationError({"min_pct": "Must not exceed max_pct."})

        steps, settings_used = gather_preview_steps(athlete, field, from_d, to_d, tau_days, min_pct, max_pct)
        return {
            "field": field,
            "settings_used": {
                "tau_days": settings_used.tau_days,
                "min_pct": settings_used.min_pct,
                "max_pct": settings_used.max_pct,
            },
            "data": [
                {
                    "value": _seconds_to_mmss(step.value) if field == "threshold_pace" else round(step.value),
                    "kind": step.kind,
                    "activity_id": step.activity_id,
                    "effective_from": step.effective_from.isoformat(),
                    "current_from": step.current_from.isoformat(),
                }
                for step in steps
            ],
        }

    def list_activity_above_z2_load(
        self,
        field: str,
        from_date: str,
        to_date: str,
        reference: int | None = None,
        include_duplicates: bool = False,
    ) -> dict[str, Any]:
        """Issue #487: for every one of the athlete's activities of field's sport between
        from_date and to_date, the above-Z2 training load above_z2_load_for_activity computes
        from its stored intensity histogram - the same per-activity number the threshold-decay
        load term sums into a 42-day EWMA, exposed directly here for calibrating that load term
        against real history. reference, if given, overrides every activity's own in-effect
        threshold (otherwise each uses whatever was actually recorded as current on its own
        date) - e.g. to ask what this load would have looked like if FTP had been a fixed value
        the whole time. above_z2_load is null for an activity with no usable histogram data, or
        no reference to measure it against - never a fabricated 0.0. include_duplicates
        (default false) also includes same-ride-twice duplicate copies, which are otherwise
        excluded entirely (issue #498)."""
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
        qs = Activity.objects.filter(
            athlete=athlete, sport=FIELD_SPORT[field], start_date__date__gte=from_d, start_date__date__lte=to_d
        )
        if not include_duplicates:
            qs = qs.filter(primary_activity__isnull=True)

        return {
            "field": field,
            "data": [
                {
                    "activity_id": activity.id,
                    "date": activity.start_date.date().isoformat(),
                    "above_z2_load": above_z2_load_for_activity(activity, field, reference),
                }
                for activity in qs.order_by("start_date")
            ],
        }

    def get_sensor_preferences(self) -> dict[str, Any]:
        """Get the authenticated athlete's per-field sensor preferences: running_power_source,
        bike_power_source, hr_source, cadence_source. Each decides which source becomes primary
        by default the next time a matching stream is attached (see merge_activities' attach
        copy_mode/set_primary_source) - "any"/no preference means attach never auto-swaps for
        that field. Changing a preference never retroactively re-resolves an already-attached
        activity's primary stream."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)
        athlete = get_object_or_404(User, pk=athlete_id)
        return {
            "running_power_source": athlete.running_power_source,
            "bike_power_source": athlete.bike_power_source,
            "hr_source": athlete.hr_source,
            "cadence_source": athlete.cadence_source,
        }

    def get_import_settings(self) -> dict[str, Any]:
        """Get the authenticated athlete's duplicate-detection import settings:
        overlap_handling (off/flag/mark/merge) - the default applied whenever an upload or
        Garmin sync doesn't specify its own on_overlap override (issue #498). merge is accepted
        but not yet implemented (#501) - it currently behaves like flag."""
        self._require_scope(ACTIVITIES_READ)
        athlete_id = self._effective_athlete_id()
        self._require_read(athlete_id)
        athlete = get_object_or_404(User, pk=athlete_id)
        return {"overlap_handling": athlete.overlap_handling}

    def update_import_settings(self, overlap_handling: str) -> dict[str, Any]:
        """Set the athlete's overlap_handling default (off/flag/mark/merge) - the default
        applied whenever an upload or Garmin sync doesn't specify its own on_overlap override
        (issue #498). merge is accepted but not yet implemented (#501) - it currently behaves
        like flag."""
        self._require_scope(ACTIVITIES_WRITE)
        athlete_id = self._effective_athlete_id()
        sub = self._require_write(athlete_id)
        athlete = get_object_or_404(User, pk=athlete_id)
        set_by = athlete if sub == athlete_id else get_object_or_404(User, pk=sub)
        _apply_threshold_patch(athlete, {"overlap_handling": overlap_handling}, set_by)
        return {"overlap_handling": athlete.overlap_handling}
