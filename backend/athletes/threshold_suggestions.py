"""Assembles the Threshold suggestions feature's API-facing suggestion list - formatting the raw
detection functions in threshold_history.py (rejected_candidates/upcoming_drop) into the API's
JSON shape, filtering out dismissed occurrences, and caching the result per athlete for 24 hours
(the detection scan reads every record of every activity in the trailing window - too slow to
re-run on every Dashboard/Thresholds-screen load). Also owns accept/dismiss/undo, since those
need the new AcceptedThresholdCandidate/ThresholdSuggestionDismissal models threshold_history.py
deliberately stays unaware of (it's pure/read-only; these are writes).

Caching: Django's default (in-process LocMemCache - no CACHES setting overrides it, and none is
needed) is deliberately fine here, not a limitation to fix later. This app runs as a single
backend process per environment (see AWS_MIGRATION_PLAN.md's actual deployed architecture - one
EC2 instance, no horizontal scaling), so a per-process cache behaves exactly like a shared one
would. Invalidation is explicit (see invalidate() and its call sites), not just TTL-based - a
24-hour-stale "rejected" banner the athlete already fixed would be a real annoyance.
"""

from __future__ import annotations

from datetime import date

from django.core.cache import cache

from accounts.models import User

from .models import AcceptedThresholdCandidate, ThresholdHistory, ThresholdSuggestionDismissal
from .threshold_history import (
    FIELD_SPORT,
    Candidate,
    _athlete_reference_value,
    _latest_entry,
    _record_candidate,
    _seconds_to_mmss,
    refresh_field,
    rejected_candidates,
    upcoming_drop,
)

CACHE_TTL_SECONDS = 24 * 60 * 60


def _cache_key(athlete_id: str) -> str:
    return f"threshold-suggestions:{athlete_id}"


def invalidate(athlete_id: str) -> None:
    """Called wherever something could change the suggestion list: activity ingest/import, any
    ThresholdHistory write (manual edit, refresh, rebuild, accept), a change to
    threshold_window_days/threshold_sanity_pct/threshold_warning_days/ftp_calculation_method, a
    race create/update/delete, or a dismiss."""
    cache.delete(_cache_key(athlete_id))


def _format_value(field: str, raw: float) -> int | str:
    return _seconds_to_mmss(raw) if field == "threshold_pace" else round(raw)


def _delta_pct(field: str, current: float | None, proposed: float | None) -> float | None:
    if current is None or current == 0 or proposed is None:
        return None
    return round(abs(proposed - current) / current * 100, 1)


def _dismissed_keys(athlete: User, field: str, kind: str) -> set[str]:
    return set(
        ThresholdSuggestionDismissal.objects.filter(athlete=athlete, field=field, kind=kind).values_list(
            "key", flat=True
        )
    )


def _rejected_suggestion(athlete: User, field: str) -> dict | None:
    dismissed = _dismissed_keys(athlete, field, "rejected")
    reference = _athlete_reference_value(athlete, field)
    for candidate in rejected_candidates(athlete, field):
        if candidate.activity_id in dismissed:
            continue
        return {
            "id": f"{field}:rejected:{candidate.activity_id}",
            "field": field,
            "kind": "rejected",
            "current": getattr(athlete, field),
            "proposed": _format_value(field, candidate.implied_value),
            "delta_pct": _delta_pct(field, reference, candidate.implied_value),
            "activity_id": candidate.activity_id,
            "activity_date": candidate.date.isoformat(),
            "implied_from": {"window": candidate.window, "value": round(candidate.raw_value)},
        }
    return None


def _upcoming_suggestion(athlete: User, field: str) -> dict | None:
    result = upcoming_drop(athlete, field)
    if result is None:
        return None

    expiry = result["expiry"]
    days_left = result["days_left"]

    if result["kind"] == "race_will_refresh":
        race = result["race"]
        return {
            "id": f"{field}:race_will_refresh:{race.id}",
            "field": field,
            "kind": "race_will_refresh",
            "expiry_date": expiry.isoformat(),
            "days_left": days_left,
            "race": {"id": race.id, "name": race.name, "date": race.date.isoformat()},
        }

    current_entry: ThresholdHistory = result["current_entry"]
    successor: Candidate | None = result["successor"]
    key = f"{current_entry.source_activity_id or 'manual'}:{expiry.isoformat()}"
    if key in _dismissed_keys(athlete, field, "upcoming_drop"):
        return None

    reference = _athlete_reference_value(athlete, field)
    return {
        "id": f"{field}:upcoming_drop:{key}",
        "field": field,
        "kind": "upcoming_drop",
        "current": getattr(athlete, field),
        "proposed": _format_value(field, successor.implied_value) if successor else None,
        "delta_pct": _delta_pct(field, reference, successor.implied_value if successor else None),
        "activity_id": current_entry.source_activity_id,
        "activity_date": current_entry.effective_from.isoformat(),
        "expiry_date": expiry.isoformat(),
        "days_left": days_left,
    }


def _build(athlete: User) -> list[dict]:
    suggestions: list[dict] = []
    for field in FIELD_SPORT:
        rejected = _rejected_suggestion(athlete, field)
        if rejected:
            suggestions.append(rejected)
        upcoming = _upcoming_suggestion(athlete, field)
        if upcoming:
            suggestions.append(upcoming)
    # Rejected first, then upcoming_drop/race_will_refresh by days_left ascending - matches the
    # Dashboard banner's own eligibility order, and is a sensible default for the Thresholds &
    # zones screen's full list too.
    suggestions.sort(key=lambda s: (0 if s["kind"] == "rejected" else 1, s.get("days_left", 0)))
    return suggestions


def list_suggestions(athlete: User) -> list[dict]:
    key = _cache_key(athlete.id)
    cached = cache.get(key)
    if cached is None:
        cached = _build(athlete)
        cache.set(key, cached, CACHE_TTL_SECONDS)
    # days_left drifts daily even within the 24h cache window - recomputed from the cached
    # expiry_date at read time rather than caching a number that goes stale within the day.
    today = date.today()
    for suggestion in cached:
        if "expiry_date" in suggestion:
            suggestion["days_left"] = (date.fromisoformat(suggestion["expiry_date"]) - today).days
    return cached


def _find_suggestion(athlete: User, suggestion_id: str) -> dict | None:
    return next((s for s in list_suggestions(athlete) if s["id"] == suggestion_id), None)


def accept(athlete: User, suggestion_id: str) -> ThresholdHistory | None:
    """Accepts a "rejected" suggestion: records it as a real ledger entry (bypassing the sanity
    band this once and forever after, via AcceptedThresholdCandidate - see
    threshold_history.py's _accepted_activity_ids), and updates the athlete's cached profile
    value. Returns the new current ThresholdHistory entry, or None if the suggestion no longer
    exists (already handled, or the data moved on since it was last listed)."""
    suggestion = _find_suggestion(athlete, suggestion_id)
    if suggestion is None or suggestion["kind"] != "rejected":
        return None
    field = suggestion["field"]
    activity_id = suggestion["activity_id"]

    # Re-derive the candidate fresh rather than trusting the cached suggestion's own implied
    # value - the accept has to be correct even if the cache is momentarily stale.
    candidate = next((c for c in rejected_candidates(athlete, field) if c.activity_id == activity_id), None)
    if candidate is None:
        return None

    AcceptedThresholdCandidate.objects.get_or_create(athlete=athlete, field=field, activity_id=activity_id)
    _record_candidate(athlete, field, candidate, current_from=date.today())
    invalidate(athlete.id)
    return _latest_entry(athlete, field)


def undo_accept(athlete: User, field: str, activity_id: str) -> None:
    """Removes the AcceptedThresholdCandidate override and re-runs the normal windowed recompute
    - the candidate goes back to being sanity-filtered like any other activity, which may or may
    not still pick it (or something else) as current.

    Deletes the ThresholdHistory row accept() created and restores athlete.<field> to whatever
    the now-latest remaining entry says *before* recomputing - not an optional tidy-up. The
    sanity band checks the candidate against the athlete's *current live profile value*
    (threshold_history.py's _athlete_reference_value), and accept() leaves that live value set to
    the very candidate being undone; recomputing without restoring it first would compare the
    rejected candidate against itself (0% deviation, trivially "within band"), silently
    re-accepting it."""
    AcceptedThresholdCandidate.objects.filter(athlete=athlete, field=field, activity_id=activity_id).delete()
    ThresholdHistory.objects.filter(athlete=athlete, field=field, source_activity_id=activity_id).delete()
    latest = _latest_entry(athlete, field)
    if latest is None:
        setattr(athlete, field, "" if field == "threshold_pace" else None)
    else:
        setattr(athlete, field, latest.value_pace if field == "threshold_pace" else latest.value_numeric)
    athlete.save(update_fields=[field])
    refresh_field(athlete, field)
    invalidate(athlete.id)


def dismiss(athlete: User, suggestion_id: str) -> bool:
    suggestion = _find_suggestion(athlete, suggestion_id)
    if suggestion is None:
        return False
    field = suggestion["field"]
    kind = suggestion["kind"]
    if kind == "race_will_refresh":
        return False  # not a dismissable suggestion - it's a quiet note, not a card
    key = suggestion_id.split(f"{field}:{kind}:", 1)[1]
    ThresholdSuggestionDismissal.objects.get_or_create(athlete=athlete, field=field, kind=kind, key=key)
    invalidate(athlete.id)
    return True


def undo_dismiss(athlete: User, field: str, kind: str, key: str) -> None:
    ThresholdSuggestionDismissal.objects.filter(athlete=athlete, field=field, kind=kind, key=key).delete()
    invalidate(athlete.id)
