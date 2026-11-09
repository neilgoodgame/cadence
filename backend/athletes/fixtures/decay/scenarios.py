"""The fixed input scenarios regenerate.py runs through the Python `simulate()` to produce
expected.json - see regenerate.py's own docstring. Kept in its own module (rather than inline in
regenerate.py) so a future hand-written unit test can import and reuse a scenario's raw inputs
without re-running the whole regeneration pass.

Covers plan section 9.2's scenario list (slice 1's trim - see the plan doc): the brief's 260 -> 232
case under three load conditions, no successor, pace, an upward reset mid-decay, a successor chain
expiring (the 1.3 continuity case), a manual anchor, a late activity inserted out of chronological
order in the input list (exercising day-grouping, not insertion order), and decay disabled.
"""

from __future__ import annotations

from datetime import date, timedelta

from athletes.threshold_decay import DecayCandidate, DecaySettings, ManualEntry

DEFAULT_SETTINGS = DecaySettings(window_days=112, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=True)
ANCHOR_DATE = date(2026, 1, 1)
EXPIRY = ANCHOR_DATE + timedelta(days=DEFAULT_SETTINGS.window_days + 1)


def _recurring_candidate(value: float, activity_id: str, through: date, every_days: int = 10) -> list[DecayCandidate]:
    """A candidate re-appearing every `every_days`, so it never ages out of the trailing window -
    the brief's worked example (docs/plans/threshold-decay.md section 10) treats "successor 232 W"
    as a standing ability, not a single ride, so a single fixed-date candidate would spuriously
    age out partway through the scenario's own date range (see this slice's own verification of
    the worked example for why that matters)."""
    out = []
    d = ANCHOR_DATE + timedelta(days=10)
    i = 0
    while d <= through:
        out.append(DecayCandidate(date=d, implied_value=value, activity_id=f"{activity_id}_{i}", accepted=True))
        d += timedelta(days=every_days)
        i += 1
    return out


def _flat_load(ratio: float, through: date) -> dict[date, float]:
    """`ratio` is `L(d)/L(a))` as the doc defines it: the anchor's OWN day stays fixed at a
    baseline load of 100 (the level it was validly set against), while every other day - what
    `L(d)` actually looks up for any `d != a` - is scaled by `ratio`. Scaling every day
    (including the anchor's) by the same factor would make the ratio cancel out to 1 regardless
    of the chosen `ratio` - caught by this slice's own worked-example verification."""
    return {
        ANCHOR_DATE + timedelta(days=i): (100.0 if i == 0 else 100.0 * ratio)
        for i in range(-5, (through - ANCHOR_DATE).days + 5)
    }


def build_scenarios() -> list[dict]:
    scenarios = []
    end = ANCHOR_DATE + timedelta(days=DEFAULT_SETTINGS.window_days + 1 + 60)

    for name, ratio in [("maintained", 1.0), ("halved", 0.5), ("dropped", 0.0)]:
        scenarios.append(
            {
                "name": f"brief_260_to_232_{name}",
                "field": "ftp",
                "settings": DEFAULT_SETTINGS,
                "seed_anchor_value": 260.0,
                "seed_anchor_date": ANCHOR_DATE,
                "seed_anchor_kind": "measured",
                "candidates": _recurring_candidate(232.0, "succ", end),
                "manual_entries": [],
                "load_by_day": _flat_load(ratio, end),
                "start": ANCHOR_DATE,
                "end": end,
            }
        )

    scenarios.append(
        {
            "name": "no_successor",
            "field": "ftp",
            "settings": DEFAULT_SETTINGS,
            "seed_anchor_value": 260.0,
            "seed_anchor_date": ANCHOR_DATE,
            "seed_anchor_kind": "measured",
            "candidates": [],
            "manual_entries": [],
            "load_by_day": _flat_load(0.0, end),
            "start": ANCHOR_DATE,
            "end": end,
        }
    )

    scenarios.append(
        {
            "name": "pace",
            "field": "threshold_pace",
            "settings": DEFAULT_SETTINGS,
            "seed_anchor_value": 240.0,
            "seed_anchor_date": ANCHOR_DATE,
            "seed_anchor_kind": "measured",
            "candidates": [],
            "manual_entries": [],
            "load_by_day": _flat_load(0.0, end),
            "start": ANCHOR_DATE,
            "end": ANCHOR_DATE + timedelta(days=DEFAULT_SETTINGS.window_days + 1 + 400),
        }
    )

    mid_decay = ANCHOR_DATE + timedelta(days=DEFAULT_SETTINGS.window_days + 1 + 50)
    scenarios.append(
        {
            "name": "upward_reset_mid_decay",
            "field": "ftp",
            "settings": DEFAULT_SETTINGS,
            "seed_anchor_value": 260.0,
            "seed_anchor_date": ANCHOR_DATE,
            "seed_anchor_kind": "measured",
            "candidates": [DecayCandidate(date=mid_decay, implied_value=245.0, activity_id="act_reset")],
            "manual_entries": [],
            "load_by_day": _flat_load(0.0, end),
            "start": ANCHOR_DATE,
            "end": mid_decay + timedelta(days=60),
        }
    )

    # The 1.3 case: a successor chain that itself expires partway through, forcing the target to
    # move (first toward the successor's value, then away from it again once it too ages out) -
    # continuity (no cliff) across that transition is exactly what section 1.3 fixed.
    successor_date = ANCHOR_DATE + timedelta(days=DEFAULT_SETTINGS.window_days + 1 + 10)
    scenarios.append(
        {
            "name": "successor_chain_expiring",
            "field": "ftp",
            "settings": DEFAULT_SETTINGS,
            "seed_anchor_value": 260.0,
            "seed_anchor_date": ANCHOR_DATE,
            "seed_anchor_kind": "measured",
            "candidates": [
                DecayCandidate(date=successor_date, implied_value=240.0, activity_id="act_chain", accepted=True)
            ],
            "manual_entries": [],
            "load_by_day": _flat_load(0.0, end),
            "start": ANCHOR_DATE,
            "end": successor_date + timedelta(days=DEFAULT_SETTINGS.window_days + 30),
        }
    )

    scenarios.append(
        {
            "name": "manual_anchor",
            "field": "ftp",
            "settings": DEFAULT_SETTINGS,
            "seed_anchor_value": None,
            "seed_anchor_date": None,
            "seed_anchor_kind": None,
            "candidates": [
                DecayCandidate(date=ANCHOR_DATE + timedelta(days=5), implied_value=230.0, activity_id="act_lower")
            ],
            "manual_entries": [ManualEntry(date=ANCHOR_DATE, value=250.0)],
            "load_by_day": _flat_load(0.0, end),
            "start": ANCHOR_DATE,
            "end": ANCHOR_DATE + timedelta(days=30),
        }
    )

    # A "late" activity: dated well before the others but appended to the input list after them -
    # exercises that candidates are grouped/ordered by date, not by input-list position.
    late_end = ANCHOR_DATE + timedelta(days=200)
    scenarios.append(
        {
            "name": "late_activity_out_of_list_order",
            "field": "ftp",
            "settings": DEFAULT_SETTINGS,
            "seed_anchor_value": 260.0,
            "seed_anchor_date": ANCHOR_DATE,
            "seed_anchor_kind": "measured",
            "candidates": [
                DecayCandidate(date=ANCHOR_DATE + timedelta(days=150), implied_value=270.0, activity_id="act_later"),
                DecayCandidate(date=ANCHOR_DATE + timedelta(days=20), implied_value=265.0, activity_id="act_late"),
            ],
            "manual_entries": [],
            "load_by_day": _flat_load(0.0, late_end),
            "start": ANCHOR_DATE,
            "end": late_end,
        }
    )

    disabled_settings = DecaySettings(window_days=112, sanity_pct=50, tau_days=25, min_pct=3, max_pct=12, enabled=False)
    scenarios.append(
        {
            "name": "decay_disabled",
            "field": "ftp",
            "settings": disabled_settings,
            "seed_anchor_value": 260.0,
            "seed_anchor_date": ANCHOR_DATE,
            "seed_anchor_kind": "measured",
            "candidates": [
                DecayCandidate(date=ANCHOR_DATE + timedelta(days=30), implied_value=255.0, activity_id="act_a"),
                DecayCandidate(date=ANCHOR_DATE + timedelta(days=140), implied_value=240.0, activity_id="act_b"),
            ],
            "manual_entries": [],
            "load_by_day": {},
            # Long enough to see both transitions: the seed ages out in favor of act_a (around
            # day 112), then act_a itself ages out in favor of act_b (around day 142) - a chain
            # that exercises the sliding-window maximum across more than one hand-off.
            "start": ANCHOR_DATE,
            "end": ANCHOR_DATE + timedelta(days=150),
        }
    )

    return scenarios
