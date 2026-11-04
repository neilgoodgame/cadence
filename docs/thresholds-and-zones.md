# Thresholds and zones

How Cadence works out an athlete's **FTP**, **critical running power** and
**threshold pace**, how those values change over time, and how training zones
are built from them.

Both backends implement the same algorithm. Where they differ, it is called out
under [Backend differences](#backend-differences).

| | Django (`backend/`) | Spring Boot (`backend_java/`) |
|---|---|---|
| Threshold calculation | `athletes/threshold_history.py` | `athletes/ThresholdHistoryCalculator.java` (pure logic), `athletes/ThresholdHistoryService.java` (writes) |
| Threshold suggestions | `athletes/threshold_suggestions.py` | `athletes/ThresholdSuggestionService.java` |
| Zones | `athletes/zones.py` | `athletes/ZoneService.java` |
| Athlete settings | `accounts/models.py` (`User`) | `users/User.java` |
| Zone display ranges | `frontend/src/lib/zones.ts` (shared) | same |

---

## 1. Which thresholds are calculated

| Threshold | Profile field | Sport | Units | Better is |
|---|---|---|---|---|
| FTP | `ftp` | bike | watts | higher |
| Critical running power | `critical_run_power` | run | watts | higher |
| Threshold pace | `threshold_pace` | run | `M:SS` per km (seconds/km internally) | **lower** |
| Lactate threshold HR | `lthr` | n/a | bpm | n/a |

**LTHR is not calculated.** It is only ever set by hand in the athlete's profile
and has no history ledger. Everything below covers the other three.

## 2. The value a single activity implies

Each activity of the right sport is reduced to one **implied value** per
threshold, taken from its 1 Hz record stream.

| Threshold | Rule | Window |
|---|---|---|
| FTP, `ftp_calculation_method = twenty_min_test` (default) | `round(0.95 × best 20-minute average power)` | 1200 s |
| FTP, `ftp_calculation_method = sixty_min_direct` | `round(best 60-minute average power)` | 3600 s |
| Critical running power | `round(best 60-minute average power)` | 3600 s |
| Threshold pace | `round(fastest 60-minute average pace)`, in whole seconds/km | 3600 s |

If an activity is shorter than the window, it implies no value for that
threshold.

### Best average power

This is a sliding window over the per-second power samples, in sample-count
terms: a 1200-sample window is treated as 20 minutes. A missing sample counts
as **0 W**, so gaps pull the average down instead of being skipped.

### Critical running power: extra conditions

A run implies no critical running power when either of these is true:

- The activity's resolved `power_source` doesn't match the athlete's **current**
  `running_power_source` preference (`stryd`, the default, or `native`). An
  activity with no `power_source` recorded always matches. Because this is
  checked against the current preference, changing the preference later stops
  old activities counting without re-uploading them.
- Every power sample is missing. Without this check, the 0 W rule above would
  produce a meaningless near-zero value.

### Best 60-minute pace

This is a two-pointer scan over **elapsed time**, not sample count:

1. Distance is made cumulative, carrying the last known distance forward over
   gaps.
2. For each start sample, move the end pointer forward until at least 3600 s of
   elapsed time are covered.
3. Pace is `elapsed seconds ÷ km covered`. Spans with zero time or zero distance
   are skipped.
4. The minimum (fastest) pace wins.

The result is rounded to whole seconds. That's because the value is stored as
`M:SS`, and an unrounded value would never compare equal to the stored one,
which used to add duplicate history rows on every re-import.

## 3. From activity values to the athlete's threshold

### Rolling window, not a ratchet

The athlete's threshold is the **best qualifying implied value among their
activities in the last `threshold_window_days`** (default **112 days**, 16
weeks). When the activity behind the current best leaves the window, the next
best takes over, so **thresholds can go down as well as up**.

### Outlier filter (sanity band)

An implied value only counts if it is within `threshold_sanity_pct` (default
**30%**) of the reference value:

```
|candidate − reference| / reference ≤ threshold_sanity_pct / 100
```

- With no reference value yet (null or 0), everything passes. A first value has
  nothing to compare against.
- The filter applies in both directions: a suspiciously low value is excluded
  just like a suspiciously high one.
- An activity the athlete has explicitly **accepted** (an
  `AcceptedThresholdCandidate` row, created from a suggestion, see section 5)
  always passes, including during a full rebuild.

What counts as the "reference" depends on which process is running:

| Process | Reference value |
|---|---|
| Day-to-day calculation (section 3.1) | The athlete's current profile value |
| Full history rebuild (section 3.2) | The value at that point in the replay, which changes as the replay goes forward |

### 3.1 Day-to-day calculation

`current_window_value` / `currentWindowValue(athlete, field, as_of)`:

1. Load the athlete's activities of the right sport dated from
   `as_of − threshold_window_days` to `as_of`, both inclusive.
2. Work out each activity's implied value and drop the ones that fail the
   sanity band.
3. Return the best remaining value: highest power, or lowest pace.

This runs in two situations:

- **When an activity is ingested or imported** (`recompute_for_activity`).
  `as_of` is the activity's own date. A bike activity updates FTP; a run updates
  both critical running power and threshold pace. Other sports change nothing.
- **When the athlete presses refresh** (`refresh_field`). `as_of` is today. This
  is the dashboard's "value is stale, refresh now" action.

The result is written as a new history row only if all of these hold:

- There is a best value at all.
- It is **not dated before** the latest existing row's `effective_from`. A
  candidate older than the current entry could never become current, and
  writing it anyway produced a "dead" row that was re-inserted on every
  re-trigger.
- Its value differs from the latest row's value.

When a row is written, the athlete's cached profile field (`ftp`, etc.) is
updated to match.

### 3.2 Full history rebuild

`rebuild_history_stream` (Django) / `rebuildHistory` (Java) is the bulk
"recompute history from oldest" tool. It reports progress over SSE. It deletes
the existing ledger for one field and then replays **every** activity of that
sport, oldest first:

1. Drop candidates older than `activity_date − threshold_window_days` from the
   window.
2. Work out this activity's implied value. It qualifies if it was accepted or
   passes the sanity band against the **current replay value**.
3. If it qualifies, remove every candidate in the window that it beats, then add
   it. The window is a monotonic list (the standard sliding-window-maximum
   technique), so the front is always the best.
4. If the front's value differs from the previous value, record an entry with:
   - `effective_from` = the date of the winning activity
   - `current_from` = **this** activity's date, which is when the replay
     discovered the new winner

At the end, the athlete's profile field is set to the last entry's value. If
there is no entry at all, it is cleared.

### 3.3 Values entered by hand

When an athlete edits a threshold in Preferences, or a coach with write access
edits it for them (`record_manual_value` / `recordManualValue`):

- The value is trusted as given: no sanity band and no window search.
- `current_from` is always set equal to `effective_from`, and `set_by` records
  who made the change.
- Re-submitting a value that already matches what's in effect at
  `effective_from` does nothing.
- From then on it is an ordinary ledger entry. It can leave the window and can
  be beaten by a later qualifying activity.

**Backdating (`effective_from`).** The edit can optionally carry an
`effective_from` date instead of always applying from today - e.g. correcting
today an FTP that was actually wrong starting a few days ago. The row is
**always written**, since it's a legitimate historical correction either way,
but it only becomes the live value (the cached profile field, and everything
in 3.4 that reads "current") when **both**:

1. no existing row already has a later `effective_from`, and
2. it isn't already outside `threshold_window_days` as of today - i.e. it
   wouldn't be stale the moment it's saved.

If either fails, the row is **correction-only** (`is_correction_only = true`):
it still governs any activity-scoped lookup in its span (3.4's `current_from`
rule, unchanged), re-rating past activities if asked to, but it's excluded
from every "what's current" lookup below. A future `effective_from`, or one
before the athlete's first activity of the field's sport, is rejected; one
outside the window is allowed - that's exactly what makes it correction-only.

An edit can also ask to recompute (`rerate_activities`, default true)
intensity/TSS for every activity of the affected sport on/after
`effective_from` - reusing the same rating helper issue #481 introduced - or
to preview that outcome (`preview`) without saving anything.

### 3.4 The history ledger (`ThresholdHistory`)

| Column | Meaning |
|---|---|
| `value_numeric` / `value_pace` | Watts for power fields, `M:SS` for pace |
| `source_activity` | The activity the value came from. Null for a value entered by hand |
| `set_by` | Who entered it by hand, if anyone |
| `effective_from` | The date of the qualifying effort, or of the manual entry |
| `current_from` | The date this row actually became the current value |
| `is_correction_only` | True for a backdated manual row that never became current - see 3.3 |

The two dates differ when a lower, older value only becomes current because a
better, newer value left the window. For example, a 260 W FTP set on 1 March
might only become current on 10 June when a 270 W FTP from 15 Feb drops out.

**Which row is current:** the row with the latest `effective_from`, excluding
any `is_correction_only` row. Java breaks ties by highest id.

**Staleness:** a field is stale when
`(today − current row's effective_from) > threshold_window_days`, or when there
is no row at all. This is a cheap date check that runs on every dashboard read;
it doesn't recalculate anything.

## 4. Zones

Each athlete has four zone sets (`ZoneSet`). Each zone set is a list of
`{name, low_pct, high_pct}` bands, stored as percentages of a reference
threshold. A zone set is created lazily, with default bands, the first time it
is read. The bands can be edited in Preferences.

| Zone type | Reference threshold |
|---|---|
| `heart_rate` | `lthr` |
| `bike_power` | `ftp` |
| `run_power` | `critical_run_power` |
| `pace` | `threshold_pace` (seconds/km) |

### Default bands: heart rate, bike power, run power

| Zone | % of threshold |
|---|---|
| Z1 Recovery | 0–55 |
| Z2 Endurance | 56–75 |
| Z3 Tempo | 76–90 |
| Z4 Threshold | 91–105 |
| Z5 VO2max | 106–150 |

### Default bands: pace (Jack Daniels' training paces)

| Zone | % of threshold-pace effort |
|---|---|
| Easy | 0–83 |
| Marathon | 84–93 |
| Threshold | 94–101 |
| Interval | 102–109 |
| Repetition | 110–150 |

Pace percentages measure **effort**, so a higher percentage means faster, the
same as the other zone types. To turn a band into actual paces, divide the
threshold pace by the percentage; this happens in `frontend/src/lib/zones.ts`:

```
fast end = threshold_pace ÷ (high_pct / 100)
slow end = threshold_pace ÷ (low_pct / 100)    (no limit when low_pct = 0)
```

The power and heart-rate bands are a straight multiplication:
`threshold × pct / 100`.

Pace uses Daniels' bands instead of the generic table because inverting a
percentage is non-linear: the generic table stretches far too wide at the easy
end. The bands are calibrated from Daniels' VDOT tables so that the runner's own
threshold pace falls inside the Threshold band. Daniels defines threshold pace
as roughly a 60-minute effort, which matches this app's 60-minute window.

### Which threshold a zone calculation uses

| Context | Reference used |
|---|---|
| No specific activity (Zone Editor, HR-based TSS/TRIMP helpers) | The athlete's **current** profile value |
| A specific bike or run activity (time in zone, etc.) | The `ThresholdHistory` row with the latest `current_from` on or before the activity's date. **No row means no reference**: there's no fallback to today's value |
| `heart_rate`, in every context | Always the current `lthr` (it has no ledger) |

Filtering on `current_from` rather than `effective_from` keeps a past activity's
zones tied to the threshold that was actually in effect on that day.

The bands themselves are not versioned: editing a zone set changes the bands
used for every activity.

## 5. Threshold suggestions

These are shown on the Dashboard and the Thresholds screen. Results are cached
per athlete for 24 hours, and the cache is cleared explicitly when something
relevant changes. There are two kinds of suggestion.

### Rejected breakthrough (`rejected_candidates`)

An activity in the current window whose implied value **beats** the current
value but **failed the sanity band**. This could be a real breakthrough or a bad
sensor reading.

- Only upward outliers are shown; a suspiciously low reading is just treated as
  wrong.
- Results are sorted best first.
- **Accept** creates an `AcceptedThresholdCandidate`, so the activity bypasses
  the sanity band from then on, and records the new value.
- **Dismiss** hides that occurrence. Both actions can be undone.

### Upcoming drop (`upcoming_drop`)

A warning that the current value is about to leave the window, sent with enough
lead time for the athlete to test or race again.

**How long before the drop the warning appears:**

| `threshold_window_days` | Warning lead |
|---|---|
| ≥ 84 | `threshold_warning_days`: 14, 21 (default), 28, or 0 (off) |
| < 84 | `threshold_window_days ÷ 4` |

**When the value drops:** the expiry date is
`effective_from + threshold_window_days + 1`. A warning is considered only while
`0 < days_left ≤ lead`.

**What it would drop to:** the successor is `current_window_value` evaluated at
the expiry date.

**Rules, applied in order:**

1. **Small drop: no warning.** Skipped when the successor is within 2% **and**
   within 5 W (or 3 s/km for pace).
2. **Near match: no warning.** Skipped when an activity in the last 14 days
   already implies a value within 2% of the current one.
3. **Race booked: quiet note.** If a race in that sport is booked before the
   expiry date, a `race_will_refresh` note is shown instead of a warning.
4. **Otherwise: warn.** The warning says "drops to X", or "goes stale" if there
   is no successor.

## 6. Athlete settings

| Setting | Default | Effect |
|---|---|---|
| `threshold_window_days` | 112 | Length of the rolling window |
| `threshold_sanity_pct` | 30 | Width of the outlier filter |
| `threshold_warning_days` | 21 | Warning lead for upcoming drops (0 turns warnings off) |
| `ftp_calculation_method` | `twenty_min_test` | 20-minute × 0.95, or 60-minute direct |
| `running_power_source` | `stryd` | Which running-power source counts (`stryd` / `native`) |
| `max_running_power_watts` | 1000 | **Java only**, see below |

Changing a setting takes effect from the next calculation; existing history rows
aren't rewritten. To apply a change to past history, run the full rebuild
(section 3.2).

## Backend differences

- **Running-power spike filter (Java only).** Before calculating a run's
  implied critical running power, Java passes the power stream through
  `activities/calc/RunningPowerSanitizer.java`. It nulls out any run sample
  above the athlete's `max_running_power_watts` (default 1000 W, added in
  Flyway `V48__max_running_power_watts.sql`). Nulled samples then count as 0 W,
  like any other missing sample.
  - **Why it exists:** Stryd footpods occasionally emit isolated samples in the
    thousands of watts.
  - **What Django lacks:** Django has no equivalent setting or filter, and
    `openapi.yaml` doesn't document the setting.
  - **Effect:** for a run containing such spikes, the two backends can produce
    different critical running power values. Spikes may still be caught by
    Django's sanity band, but only if they push the 60-minute average more than
    30% away from the current value.
- **Hibernate memory clearing (Java only).** The Java calculator calls
  `entityManager.clear()` after each activity. This doesn't change any results;
  it stops a long rebuild running out of memory.
- **Dates (equivalent).** Java takes an activity's date from its start time in
  UTC. Django uses `start_date.date()` with `TIME_ZONE = "UTC"`. Both give the
  same calendar date.

Everything else in this document (constants, filtering, replay, ledger
semantics, suggestion rules and zone defaults) matches between the two backends.
If you change any of it, change both, as required by the parity rule in the
root `CLAUDE.md`.
