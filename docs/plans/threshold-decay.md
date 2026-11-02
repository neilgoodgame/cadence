# Plan: threshold decay for the rolling-window threshold algorithm

Status: **proposal for review**. No implementation yet.

Background reading: `docs/thresholds-and-zones.md` (current algorithm, referred to
below as "the doc", with its section numbers). The parity rule in the root
`CLAUDE.md` applies throughout: every behaviour change here lands in both
`backend/` (Django) and `backend_java/` (deployed).

---

## 0. Summary

| Area | Recommendation |
|---|---|
| Decay model | As proposed, but evaluated as a **daily recurrence** towards a moving target rather than a closed form anchored to `ftp_last`, and **never easing upwards** (see 2.2) |
| "Current best" | Comes from the **ledger** (latest measured or manual row, the "anchor"), not the window scan. The window scan can't see manual rows |
| Load term | Per-sport 42-day EWMA of **power TSS at a fixed reference** (the anchor value), using stored `norm_power` and `moving_time`; stored hrTSS for activities without power. Not circular, and needs no record-stream reads (see 2.3) |
| Pace | Decay arithmetic in effort space (speed = 1/pace), so decay always makes pace **slower** (see 2.4) |
| Trigger | **Daily scheduled job** plus the existing ingest and refresh hooks. Not compute-on-read (see 4.4) |
| Day-to-day vs rebuild | One pure **simulation** function used for both, so a rebuild reproduces day-to-day behaviour by construction (see 3, 4.2) |
| Ledger | New `kind` column: `measured` / `manual` / `decayed` (see 5) |
| TSS/IF | Recompute intensity **and** TSS together for activities affected by any ledger change, through one shared helper (see 4.6, 8) |
| Settings | `threshold_decay_enabled` (on for new athletes, **off for existing**), `threshold_decay_tau_days` = 25, `threshold_decay_max_pct` = 12. Edited in Preferences → Best efforts → Threshold history (see 6) |
| Separate bugs | TSS not recomputed by "Recompute stats" (8); window/sanity settings never saved (6.2); manual value replaced early (1.1); rebuild deletes manual rows (1.4) |

---

## 1. Corrections to the brief (confirmed against the code)

### 1.1 A manual value does not "age out after 112 days". It is usually replaced much sooner, sometimes by a lower value.

`current_window_value` / `currentWindowValue` scans **activities only**. Manual
rows are invisible to it. After the athlete sets 255 W manually, the next ingest
evaluates the window best among activities:

- A ride dated **before** the manual row is blocked by the "not dated before the
  latest row's `effective_from`" rule (`_recompute_and_record`,
  `ThresholdHistoryService.recomputeAndRecord`).
- But the first ride dated **after** the manual row that becomes the window's
  best activity is recorded, **even if it's below 255 W**. In the brief's
  scenario the 232 W ride is still in the window, so any later easy ride
  implying 233 W or more replaces 255 W straight away. 255 passes the 30% sanity
  band, so nothing stops it.
- If no qualifying activity exists, nothing is ever written. The value then
  sits unchanged indefinitely, flagged `stale`.

So the brief's predicted "cliff after 112 days" is really a much earlier, silent
drop. No existing test covers this (`backend/athletes/tests.py` and
`ThresholdHistoryServiceIntegrationTest` only test that a manual row is used and
goes stale). This plan fixes it in two places:

- With decay **on**: manual rows become anchors (2.1).
- With decay **off**: a minimal standalone fix. `current_window_value` treats
  the latest manual row inside the window as a candidate with its own value and
  date.

`openapi.yaml` already documents the intended behaviour ("ages out after
threshold_window_days like any other entry"), so this is a bug fix, not a
contract change.

### 1.2 `ftp_last` cannot come from the window scan

This follows from 1.1. The value being decayed **from** is the latest non-decayed
**ledger** row (`measured` or `manual`), which this plan calls the **anchor**.
Expiry is `anchor.effective_from + threshold_window_days + 1`, the same rule
`is_stale` and `upcoming_drop` already use.

### 1.3 The closed form recreates a cliff when a successor expires

`value(t) = target + (ftp_last − target)·e^(−t/τ)`, with `t` measured from the
anchor's expiry, is only smooth while `target` is stable. The successor is itself
an activity in the window. When it ages out (say at t = 150 days), `target` jumps
to the next successor or the floor. Because `e^(−150/τ) ≈ 0`, the value jumps
straight to the new target: the same cliff, one step later.

**Departure:** evaluate decay as a first-order daily recurrence, the same shape
as CTL:

```
v(d) = target(d) + (v(d−1) − target(d)) · e^(−1/τ)
```

This is identical to the closed form while `target` is constant, and stays
continuous when `target` moves. It is still re-derivable from inputs without a
stored trajectory: replay the days from the anchor, as in section 3.

### 1.4 Rebuild deletes manual rows

`rebuild_history_stream` / `rebuildHistory` delete **every** row for the field
(`ThresholdHistory.objects.filter(athlete=..., field=...).delete()`,
`deleteByAthleteIdAndField`) and replay activities only, so manual entries are
lost on every rebuild. With manual rows as anchors this is no longer acceptable:
rebuild keeps `manual` rows and replays around them (4.2).

### 1.5 The TSS bug isn't a write-back failure (details in section 8)

"↺ Recompute stats" (Activity Analysis → Stats tab) only ever rewrote
`intensity`, never `tss`. The numbers fit exactly:

- NP ≈ 0.798 × 232 ≈ 185 W.
- 185 / 255 = 0.726, so the reference used at the ride's date was the manual
  255 W.
- TSS 211 = 3.31 h × 0.798² × 100, the untouched ingest-time value.

### 1.6 Smaller corrections

- `ThresholdHistoryRepository`'s comment says `TssRecomputeService` reads the
  `effective_from`-based finder. It doesn't: both TSS and intensity go through
  `ZoneService.referenceFor(athlete, type, activity)`, which filters on
  `current_from`. Fix the comment. No behaviour change.
- `POST /v1/activities/{id}/recompute-tss`, `/recompute-stats` and the
  athlete-level equivalents exist in both backends and the frontend, but are
  **not in `openapi.yaml`**. Add them, since section 8 changes what they return.
- Django's `backfill_extended_stats` doesn't recompute `avg_power`,
  `norm_power` or `intensity`. Java's `DerivedStatsRecomputeService` does. This
  is a pre-existing parity gap, fixed in section 8.

---

## 2. Algorithm

### 2.1 Terms

- **Anchor**: the latest `measured` or `manual` ledger row for the field. Its
  value is `A` and its `effective_from` is `a`. An anchor is created by:
  - a qualifying activity whose implied value is ≥ the current value (an
    "upward reset"), or
  - a manual entry, or an accepted suggestion.
- **Expiry**: `E = a + threshold_window_days + 1`.
- **Successor at day `d`**: the best qualifying implied value among activities
  in `(d − window, d]`. Same scan and sanity band as today.
- **Current value `v`**: the field's value at day `d`. It is what gets written
  to the ledger and the profile.

### 2.2 One day's step (decay enabled)

All decay arithmetic is in **effort units**, where higher is better (section
2.4). For each day `d`, in order:

1. **Add the day's activities.** For each activity on `d`, in start-time order,
   with implied value `x`:
   - If `x` fails the sanity band against `v` and wasn't accepted, skip it
     (unchanged rule).
   - Otherwise add it to the window. If `x ≥ round(v)` it's an **upward
     reset**: the activity becomes the anchor (`A = x`, `a = d`), `v = x`, and
     a `measured` row is emitted.
2. **Drop expired candidates** from the window (unchanged).
3. **Before expiry** (`d < E`), `v` stays at `A`. Lower evidence never replaces
   the anchor while it's in the window. This fixes 1.1 for manual anchors and
   is a no-op for measured ones, since the anchor activity is the window best.
4. **On or after expiry:**

   ```
   ratio  = clamp(L(d) / L(a), 0, 1)         # 1 if L(a) < 1 (see Q3)
   floor  = A · (1 − max_pct · (1 − ratio))
   target = max(successor, floor)   if successor exists
          = A · (1 − max_pct)       otherwise
   v      = min(v, target + (v − target) · e^(−1/τ))
   ```

   If `round(v)` differs from the last emitted value, emit a `decayed` row with
   `effective_from = current_from = d`.

**Departure: `min(v, …)`, "never ease upwards".** If the load recovers, `floor`
can rise above `v`. Without the clamp, `v` would then climb with no fitness
evidence. With it, the value only goes up through an upward reset, which matches
the brief's "upward evidence wins immediately; don't ease upwards".

**Decay disabled.** Keep today's behaviour: after expiry the successor takes
over at once, as a `measured` row with `effective_from` = the successor's date
and `current_from = d`. The only change is the 1.1 fix.

**Long-term behaviour to be aware of.** Under sustained easy riding with reduced
load, `v` decays until an easy ride implies `x ≥ round(v)`, which becomes a new
(submaximal) anchor. 112 days later that decays too. The ratchet is slow and
bounded by `max_pct` per cycle, and any real test resets it upwards. See Q4.

### 2.3 Load term: recommendation

**Recommended: power TSS at a fixed reference, per sport.**

For each activity of the field's sport, the daily "decay load" is:

- **Activities with trusted power** (`norm_power` set, and for runs
  `matches_running_power_preference`): `moving_time · NP² / R² / 36`, where `R`
  is the anchor's value for that sport's **power** field. That's the bike
  anchor for FTP, and the critical-running-power anchor for both run fields.
- **Everything else:** the stored `tss`. For an activity without power this is
  hrTSS, which depends on LTHR. LTHR is manual and has no ledger, so this isn't
  circular.

`L(d)` is a 42-day EWMA of daily decay load, walked from the athlete's first
activity of that sport, the same loop as `FitnessService` / `core.derived`.

**Why this one:**

- **Not circular.** It never reads the decaying ledger.
- **`R` doesn't change the ratio.** `R` is the same at both ends of
  `L(d) / L(a)`, so it cancels for an all-power history; it only sets the scale
  relative to hrTSS activities. "TSS recomputed against `ftp_last`" is therefore
  exactly an FTP-independent load measure, which is what we want.
- **Cheap.** One query of `(start_date, sport, norm_power, moving_time, tss,
  power_source)` per athlete, with no record streams.
- **Intensity-weighted.** It keeps TSS's IF² weighting, which the brief's
  Hickson reasoning relies on.

**Why not TRIMP / hrTSS for everything:**

- It's missing whenever there's no HR strap. Rides with power but no HR would
  count as zero load, and the athlete would look like they'd stopped.
- Edwards' TRIMP weights intensity linearly by zone number, not quadratically,
  so it under-credits the intensity that Hickson says preserves threshold.
- Its scale differs from TSS, so mixing it with power-based load needs
  calibration we don't have.

**Pace field.** It uses the same run load as critical running power. A run with
no trusted power uses its stored hrTSS.

### 2.4 Pace direction

Decay arithmetic works in effort units, `e = 1 / pace` (a speed). Values are
converted back to seconds per km for comparison and storage, rounded to whole
seconds as today. Decay therefore always makes pace **slower**.

For example, a 4:00/km anchor (240 s) with the full 12% decay has a floor of
240 / 0.88 = 272.7 s, about 4:33/km. That's +13.6% in seconds, consistent with
how pace zones already treat percentages as effort (doc section 4). Upward reset
and successor comparisons keep using the existing `_is_better` / `isBetter` on
native rounded values. A unit test asserts that decayed pace values are strictly
non-decreasing in seconds.

---

## 3. Shared pure logic

A new pure module on each side, with no ORM or JPA, so it can be unit-tested
without a database:

| | File |
|---|---|
| Django | `backend/athletes/threshold_decay.py` |
| Java | `backend_java/.../athletes/ThresholdDecay.java` (static and final, like `TssCalculator`) |

```
DecaySettings(enabled, window_days, sanity_pct, tau_days, max_pct)
Anchor(value, effective_from, kind, activity_id)
Candidate(date, implied_value, activity_id, accepted)     # reuse the existing dataclass/record
Step(value, kind, activity_id, effective_from, current_from)

simulate(field, settings, seed_anchor, seed_value, seed_window,
         candidates_by_day, manual_by_day, load_by_day,
         start, end) -> list[Step]

decay_load_series(activities, reference_power) -> dict[date, float]   # the 42-day EWMA
```

- `simulate` holds the day loop from 2.2 for both the enabled and disabled
  modes. Manual rows enter on their own date as anchors.
- `ThresholdHistoryCalculator` / `threshold_history.py` keep their existing
  jobs: loading activities, implied values (`_implied_value_detail`), the
  sanity band and the accepted-ID set. They pass plain data in and persist the
  steps that come back.
- `replay_full_history` / `replayFullHistory` are reimplemented as one
  `simulate` call from the athlete's first activity, rather than a second
  algorithm. Their monotonic-window logic moves into `simulate` unchanged.

---

## 4. Integration points (each confirmed against the code)

### 4.1 `current_window_value` / `currentWindowValue`

Confirmed: a pure function of `as_of` that scans activities only. **Keep it**
as the successor and window primitive, and use it unchanged for:

- `rejected_candidates` (suggestions),
- `upcoming_drop`'s successor lookup,
- the disabled-decay path (plus the 1.1 manual-row fix).

It doesn't learn about the anchor or expiry. The new entry point does, from the
ledger (1.2). `_recompute_and_record` / `recomputeAndRecord` become "re-simulate
the suffix" (4.2).

### 4.2 Replay, rebuild and day-to-day: one mechanism

Decay is driven by the calendar and the replay is driven by activities, so the
simulation steps **day by day**. That's a few thousand iterations for a
multi-year history, which is trivial next to loading implied values. Each
caller re-simulates a suffix of the ledger:

| Caller | From | Rows replaced |
|---|---|---|
| Daily job | Day after the latest row's `current_from` | None (append only); seeded from the ledger |
| Ingest of an activity dated `D` | `D` | `measured` and `decayed` rows with `current_from ≥ D` |
| Manual entry or accepted suggestion | Its date | Same as ingest |
| Refresh button | Today | Append only, same as the job |
| Rebuild | Athlete's first activity | All `measured` and `decayed` rows. **`manual` rows are kept** (1.4) |

**Seeding.** The suffix starts from the ledger as of `D − 1`:

- `v` = the latest row with `current_from < D`,
- the anchor = the latest non-decayed row before `D`,
- the window = activities in `(D − 1 − window, D − 1]`, re-read.

**Why this design:**

- A rebuild and a day-by-day run produce the same ledger by construction. The
  parity tests in 9.2 assert it.
- It fixes late uploads. Today a ride dated before the latest row's
  `effective_from` is silently dropped. Under decay, decayed rows would be
  dated every day, so without suffix re-simulation every late upload would be
  dropped.

**Bulk imports.** Re-simulating to today on each of 700 activities is
O(n × history) in record reads. Batch upload (`ThresholdHistoryTasklet` /
`processing._ingest_activity`) and `ImportReader` / `import_reader` should:

1. collect the earliest affected date per field,
2. skip the per-activity call while the batch runs,
3. re-simulate once at the end.

Single uploads keep the per-activity call.

*Optional optimisation, not v1:* read 1200 s and 3600 s best power from the
persisted duration curves instead of record streams. First confirm both
backends persist those exact points per activity.

### 4.3 Writing ledger rows

- Write only when the rounded value changes. This is the existing
  "differs from latest" check, applied inside `simulate`.
- Decayed rows have `kind = decayed`, `source_activity = null`, `set_by = null`,
  and `effective_from = current_from = the step's date`.
- **Remove the "not dated before the latest row's `effective_from`" rule.** It
  was only there to stop dead rows from repeated re-evaluation, and suffix
  re-simulation already prevents those (re-running the same suffix yields the
  same rows, which are replaced rather than appended). Keep a regression test
  for the original dead-row bug, which still has to pass.
- **"Current row" lookups are unchanged** (latest `effective_from`, then id).
  Decayed rows are dated on their step day, so they correctly become current.
- **Concurrency.** The daily job and an ingest could re-simulate the same field
  at once. Take a row lock on the user for the duration:
  - Django: `select_for_update()` on `User`.
  - Java: a `PESSIMISTIC_WRITE` lookup on `UserRepository` inside the
    transaction.

### 4.4 What triggers decay: daily job, not compute-on-read

**Compute-on-read doesn't fit.** Intensity, TSS, zones-per-activity and CTL
are all **stored** or read from ledger rows by `current_from`. A value that only
exists at read time would never reach stored TSS, and CTL would still be built
from the stale value. That's the exact failure in the brief.

**Daily job.** At 02:17 UTC, re-simulate append-only to today for each athlete
and field where decay is on and the anchor has expired (a cheap filter: latest
non-decayed `effective_from < today − window`). Then recompute stats for any
affected activities (4.6).

| | Where |
|---|---|
| Django | New `athletes.tasks.apply_threshold_decay`, added to `CELERY_BEAT_SCHEDULE` in `config/settings.py`. Beat already runs embedded in `celery-worker -B`. Update the settings comment that says "only periodic task" |
| Java | `@Scheduled(cron = "0 17 2 * * *")` on a new `ThresholdDecayJob`. `@EnableScheduling` is already on. One transaction per athlete with `entityManager.clear()`, given the 600 MB cap noted in `backend_java/CLAUDE.md`. Staging runs one instance, so no distributed lock is needed |

**Missed days are harmless.** Ingest and refresh also re-simulate up to the
activity's date, or today, first. So a ride ingested before the job has run
still gets the correct decayed reference.

### 4.5 Staleness

Today stale means `(today − latest row's effective_from) > window`. Decayed rows
are dated daily, so they would wrongly reset this.

- **Change** `is_stale`, `isStale`, `summaryFor` and the Django summary view to
  use the latest **non-decayed** row. This adds one finder:
  `findFirstByAthleteIdAndFieldAndKindNotOrderByEffectiveFromDescIdDesc` /
  `.exclude(kind="decayed")`.
- **`upcoming_drop`** (`_latest_entry`, and the expiry computation in both
  calculators) uses the same anchor lookup.
- **While decaying,** the summary returns `stale: true`, `kind: "decayed"` and a
  `decay` object (7.1). The UI shows the value as modelled.

### 4.6 Zones and TSS for past activities

Decayed rows carry correct `current_from` dates, so
`reference_for(…, activity)` / `ZoneService.referenceFor(…, activity)` need no
change. They already pick the latest row with `current_from ≤` the activity
date.

**Recompute derived stats on every ledger change.** After any re-simulation
that changes rows, recompute **intensity and TSS** for that sport's activities
with `start_date ≥` the earliest changed `current_from`. This uses the shared
helper from section 8. What it usually covers:

| Trigger | Activities recomputed |
|---|---|
| Daily job | Usually none (today's step, today's activities) |
| Late upload | Days of activities |
| Rebuild | Everything after the first change |

Rebuild currently leaves TSS stale until the athlete runs "Recompute TSS"
separately. Make TSS recompute the final phase of the rebuild SSE stream.

**The activity page's "revealed your FTP" indicator** (`ActivityService.thresholdHistoryFor`
and the Django `activities/serializers.py` around line 143) lists ledger rows
whose `current_from` equals the activity's date. **Exclude `kind = decayed`
there**, or every ride on a decay-step day will claim to have revealed a new
value.

### 4.7 Upcoming-drop suggestion

When decay is on:

- **Wording.** "FTP starts easing from 260 W on 12 Dec, towards about 245 W"
  replaces "drops to X". The no-successor case ("goes stale") becomes "towards
  X" as well, since `target = A·(1 − max_pct)` always exists.
- **`proposed`** = the projected target at expiry: the same formula with
  `L(E)` ≈ today's load ratio.
- **Add `decays: boolean`** to the suggestion so the frontend picks the verb.
  This avoids a new `kind`.

The skip rules still hold, compared against the target:

- **Small drop** (≤ 2% and < 5 W / 3 s): unchanged.
- **Near match** (a ride in the last 14 days within 2% of current): unchanged.
  Under decay, that ride would become an upward reset or close to one anyway.
- **Race booked**: unchanged.

When decay is off, nothing changes.

### 4.8 API and MCP

- `openapi.yaml` changes are listed in 7.1.
- **MCP.** Both backends expose `get_athlete_thresholds` and
  `set_athlete_thresholds`:
  - Django: `backend/athletes/mcp.py`.
  - Java: `mcp/tools/athletes/AthleteProfileTools.java` and
    `AthleteProfileWriteTools.java`.

  Both return the threshold summary, so they pick up `kind` and `decay` from
  the shared summary builder. Update both docstrings: "still ages out" becomes
  "decays gradually after the window when threshold decay is on". There's no
  MCP tool for threshold *settings* today, and none is proposed.

### 4.9 Docs

See section 10.

---

## 5. Schema, migrations, backfill

### `threshold_history.kind`

`varchar(10) NOT NULL DEFAULT 'measured'`, with values `measured`, `manual`,
`decayed`.

| | Change |
|---|---|
| Django | `athletes/migrations/0009_thresholdhistory_kind.py`: `AddField` with `choices`, then `RunPython` setting `kind='manual'` where `source_activity IS NULL`. Add an index on `(athlete, field, kind, effective_from)` for the anchor finder |
| Flyway | `V90__threshold_history_kind.sql`: `ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'measured'`, then `UPDATE … SET kind='manual' WHERE source_activity_id IS NULL`, plus the same index. Entity: a `ThresholdHistoryKind` enum with a converter, following the existing `ThresholdFieldConverter` pattern |

**Backfill rule.** No decayed rows exist yet, so `source_activity IS NULL`
identifies manual rows unambiguously. That includes manual rows with
`set_by = null` from before `set_by` existed, and imported rows.

### User settings

| Field | Type | Default (new athletes) | Existing athletes |
|---|---|---|---|
| `threshold_decay_enabled` | bool | `true` | `false` |
| `threshold_decay_tau_days` | small int, 7–90 | 25 | 25 |
| `threshold_decay_max_pct` | small int, 0–30 | 12 | 12 |

| | Change |
|---|---|
| Django | `accounts/migrations/0022_user_threshold_decay.py`. Add `threshold_decay_enabled` with `default=False` so existing rows backfill to false, then `AlterField` to `default=True` |
| Flyway | `V91__user_threshold_decay.sql`: `ADD COLUMN … DEFAULT false`, then `ALTER COLUMN … SET DEFAULT true`. Java field initialiser `true` |

### Existing ledgers

They are **not rewritten** by the migration. Existing athletes keep today's
behaviour until they turn decay on. Turning it on should prompt a rebuild
(6.1). New athletes start with decay on and an empty ledger.

### Export and import

- Add `kind` to the export entry (`ThresholdHistoryExportEntry`,
  `dataexport/export_writer.py`).
- On import (`ImportReader`, `import_reader._import_threshold_history`), a
  missing `kind` is inferred as `manual` if `source_activity_id` is null,
  otherwise `measured`.

---

## 6. Settings and Preferences

### 6.1 UI

The settings go in **Preferences → Best efforts → Threshold history**
(`frontend/src/screens/preferences/BestEffortsTab.tsx`), next to Window and
Sanity limit, and share their Save button:

- **Gradual decay** (checkbox). Help text: "After a threshold's best effort
  leaves the window, ease it down towards your recent efforts instead of
  dropping straight to them. How far it can fall depends on how much you've
  kept training that sport."
- **Decay time constant (days)**: number input, 7–90, disabled when decay is
  off. Help: "About 63% of the way to the target after this many days."
- **Maximum decay (%)**: number input, 0–30, disabled when decay is off.
- **When decay is switched on**, show: "Rebuild each field below to apply decay
  to past history." This reuses the existing per-field Recompute buttons and
  follows the existing note that window and sanity changes aren't retroactive.

### 6.2 Existing bug: window and sanity never saved

`BestEffortsTab` sends `threshold_window_days` and `threshold_sanity_pct`, and
`openapi.yaml` documents both on `PATCH /v1/athletes/{id}`. But:

- Java's `AthleteUpdateRequest` has no such fields, and Jackson ignores unknown
  properties.
- Django's update serializer (`athletes/serializers.py`) doesn't list them.

Both backends silently drop them, so the Save button there has never worked.
**Fix in the same change.**

| | Fix |
|---|---|
| Java | Append `thresholdWindowDays`, `thresholdSanityPct` and the three decay fields to the **end** of `AthleteUpdateRequest` (see the positional-record gotcha in `backend_java/CLAUDE.md`). Handle them in `AthleteService.updateProfile` with clamping, and set `suggestionsAffected` |
| Django | Add all five to the update serializer `fields` and `extra_kwargs`, with `min_value` / `max_value`. Add the decay settings to `_SUGGESTION_AFFECTING_SETTINGS` in `athletes/views.py` |
| Ranges | Window 1–365 and sanity 1–100, matching the UI; tau 7–90; max 0–30 |

Changing a decay setting isn't retroactive, matching the window/sanity
convention. The daily job applies new settings from the next day, seeded from
the existing ledger.

---

## 7. API, `openapi.yaml`, MCP, frontend

### 7.1 `openapi.yaml`

- **`User`**: the three decay fields, with defaults and descriptions.
- **PATCH `/v1/athletes/{id}`**: the three decay fields, plus clamping notes
  for window and sanity.
- **`ThresholdSummaryEntry`**:
  - add `kind` (`measured` / `manual` / `decayed`),
  - add `decay` (object or null; only when `kind = decayed`):
    `{ anchor_value, anchor_effective_from, decaying_since, target }`,
  - redefine `stale` as "the anchor has aged out of the window". This is true
    while decaying.
  - `effective_from` keeps meaning the current row's date.
- **`ThresholdHistoryEntry`**: add `kind`, and note that `effective_from` equals
  `current_from` for decayed rows.
- **`ThresholdSuggestion`**: add `decays`, and update the `proposed` wording.
- **New paths**: document the existing `recompute-tss` and `recompute-stats`
  endpoints (1.6), including that `recompute-stats` now also rewrites TSS.

### 7.2 Frontend

| File | Change |
|---|---|
| `src/api/types.ts` | New fields on `User`, `AthleteUpdate`, `ThresholdSummaryEntry`, `ThresholdHistoryEntry`, `ThresholdSuggestion` |
| `screens/preferences/BestEffortsTab.tsx` | Decay controls (6.1) |
| `screens/dashboard/ThresholdSummaryCard.tsx` | Line 36 mirrors the backend's stale rule from `effective_from`. Use the API's `stale` instead. Show a "Modelled" badge and "easing from 260 W since 12 Dec" when `kind = decayed` |
| `screens/ThresholdHistoryScreen.tsx` | A `kind` badge per ledger row. Decayed rows render as a dashed or lighter segment on the chart. Collapse runs of consecutive decayed rows into one expandable line |
| `screens/thresholdSuggestions.ts` | Pick the verb from `decays` (4.7) |
| `screens/activity-analysis/ThresholdHistoryIndicator.tsx` | No change. Decayed rows are filtered out server-side (4.6) |

---

## 8. Separate bug: "Recompute stats" updates IF but not TSS

**Cause** (both backends, confirmed):

| | `recompute-stats` path | What it writes |
|---|---|---|
| Java | `ActivityController` → `DerivedStatsRecomputeService.applyBackfill` | `normPower` and `intensity` against `referenceForOrLive(…, activity)`. **Never `tss`** |
| Django | `RecomputeActivityStatsView` → `uploads.processing.backfill_extended_stats` | **Neither `intensity` nor `tss`**, and not `avg_power` or `norm_power` either. So on Django, IF wouldn't have changed: a parity gap |

TSS is only rewritten by `recompute-tss` (`TssRecomputeService` /
`RecomputeActivityTssView`), which is the ↺ on the TSS stat in `StatRow.tsx`
and the dashboard's batch "Recompute TSS". The "↺ Recompute stats" button in
`StatsTab.tsx` uses the other endpoint.

**Contributing gap.** A manual threshold edit doesn't re-rate activities on or
after its date (usually today's ride). So IF and TSS stay against the old value
until one of the two buttons is pressed, and pressing the "wrong" one updates
only IF.

**Fix: one helper that always writes intensity and TSS together.**

- **Java:** add `TssRecomputeService.rate(Activity, User)`. It computes
  `intensity` (the same rule as `ComputeDerivedStatsTasklet`) and `tss` from
  the same reference and the same sanitised NP.
  - `recomputeForActivity` / `recomputeForAthlete` call it.
  - `DerivedStatsRecomputeService.applyBackfill` drops its own intensity block
    and calls it.
  - `ComputeDerivedStatsTasklet` (ingest) has a third copy of the intensity and
    TSS logic (`setIntensity` around line 98, `setTss` at line 63, the
    `TssCalculator` calls around lines 184–229). It calls `rate` too, so
    ingest, recompute-tss and recompute-stats can't drift apart again.
- **Django:** extract `rate_activity(activity, athlete, norm_power, hr_series)`
  from `_ingest_activity` (the intensity block, plus `compute_tss`).
  - Use it from `_ingest_activity`, `RecomputeActivityTssView`,
    `RecomputeAthleteTssView` and `backfill_extended_stats`.
  - `backfill_extended_stats` also gains `avg_power` and `norm_power`, matching
    Java.
- **Manual edits re-rate affected activities.** `record_manual_value` /
  `recordManualValue` trigger the 4.6 recompute for activities on or after the
  entry's date. Under decay, this is the same "ledger changed" hook.
- **Regression tests (both backends):**
  1. Ride with NP 185 W and 3.31 h moving time, ingested with FTP 232. Then a
     manual 255 dated that day, then `recompute-stats`. Assert intensity 0.726
     **and** TSS 174 (±1).
  2. The same sequence with no recompute call: assert the manual edit alone
     re-rates the ride.
  3. Django only: `recompute-stats` now sets `norm_power` / `intensity` (a
     parity test against Java's existing behaviour).

This is independent of decay and should ship first (section 11).

---

## 9. Tests

### 9.1 Unit (pure module, both backends, no database)

- **Decay curve:** constant target, τ = 25. Values at t = 14, 28 and 56 match
  the closed form to 1e-9, and rounded emissions occur only on change.
- **Moving target:** the successor expires mid-decay. Assert no
  discontinuity: the day-over-day change is ≤ `(v − target)·(1 − e^(−1/τ))`
  plus rounding. This is the 1.3 case.
- **Upward reset:**
  - a ride with `x ≥ round(v)` mid-decay becomes a `measured` anchor and resets
    `t`;
  - `x = round(v) − 1` doesn't;
  - a manual entry resets the same way.
- **Never eases upwards:** load recovers so the floor exceeds `v`; `v` doesn't
  increase.
- **No successor:** the target is `A·(1 − max_pct)`.
- **Load-ratio clamp:** ratio > 1 is clamped to 1 (no decay with maintained
  load); 0 gives the full `max_pct`; `L(a) < 1` gives a ratio of 1.
- **Pace:** a decayed `threshold_pace` is non-decreasing in seconds/km, and the
  floor for a 240 s anchor is 273 s.
- **Disabled mode** reproduces the existing replay's ledger on the current
  replay fixtures, so existing behaviour is pinned.
- **Manual anchor before expiry** isn't replaced by a lower later ride (the 1.1
  regression).
- **Load series** is independent of `R` for an all-power history, and doesn't
  change when the FTP ledger changes (no circularity).

### 9.2 Parity: golden fixtures, as for course sections

Follow `backend/courses/fixtures/sections/regenerate.py` and
`CourseSectionsTest`:

- `backend/athletes/fixtures/threshold_decay/` holds input scenarios: per-day
  implied values, manual entries, decay loads and settings. `regenerate.py`
  runs the Python `simulate` and writes `expected.json`.
- Java's `ThresholdDecayParityTest` reads its copy under
  `src/test/resources/threshold-decay-fixtures/` and asserts:
  - identical steps (value, kind, dates),
  - that the copy matches the Python directory byte for byte.
- **Scenarios:**
  - the brief's 260 → 232 (load maintained, halved and dropped),
  - no successor,
  - pace,
  - an upward reset mid-decay,
  - a successor chain expiring (1.3),
  - a manual anchor,
  - a late activity inserted into the past, simulated as a suffix, which must
    equal a full replay,
  - decay disabled.

### 9.3 Integration (database, both backends)

- **Rebuild equals day by day:** ingest a fixture day by day with the daily job
  run between days, rebuild, and assert identical ledgers. This is the key
  invariant from 4.2.
- **Rebuild keeps manual rows.**
- **Daily job:**
  - writes a decayed row only when the rounded value changes;
  - is idempotent when run twice on the same day;
  - skips athletes with decay off.
- **Staleness** stays true across decayed rows, and the summary returns `kind`
  and `decay`.
- **Activity "revealed" indicator** ignores decayed rows.
- **Late upload** inside a decay period: decayed rows after it are regenerated,
  and TSS for later activities is recomputed.
- **Settings PATCH** persists window, sanity and the three decay fields (the
  6.2 regression), with clamping.
- **Export/import** round-trips `kind`, and an old-format file infers it.
- **Section 8** regression tests.

### 9.4 Scenario regression: the brief's case

Fixture: a 260 W 20-minute-test ride, then 16 weeks of Z2 rides implying
225–235 W with bike load halved. Assert:

- no single-day drop greater than 1%,
- FTP is 253 W at 14 days after expiry and 249 W at 28 days (the "halved" row
  of the worked example in section 10),
- the 3 h, 185 W NP ride rated 28 days after expiry has IF ≈ 0.74 and
  TSS ≈ 182, not 0.80 / 211.

### 9.5 Frontend (vitest)

- The suggestion verb depends on `decays`.
- The summary card shows "Modelled" for a decayed entry and uses the API's
  `stale`.
- The BestEffortsTab Save sends all five settings and enables only on change.

---

## 10. Doc changes (`docs/thresholds-and-zones.md`)

- **Section 3:** a new "3.5 Gradual decay" with the step rule (2.2), the load
  term (2.3), pace direction (2.4) and the worked example below.
- **Section 3.1:** replace the dead-row rule with suffix re-simulation.
  **Section 3.2:** rebuild keeps manual rows.
- **Section 3.3:** a manual value is an anchor; describe the 1.1 fix.
- **Section 3.4:** add `kind` to the ledger table, and staleness from the
  anchor.
- **Section 5:** the decay wording for upcoming drops.
- **Section 6:** the three settings, and note that window/sanity now save.
- **Backend differences:** nothing new expected, since the parity fixtures
  enforce it. The existing running-power-spike note stays.

### Worked example

260 W anchor expires on day 0; successor 232 W; τ = 25; `max_pct` = 12%.

| Bike load vs the anchor date | Target | t = 0 | 14 | 28 | 56 |
|---|---|---|---|---|---|
| Maintained (ratio 1) | max(232, 260) = 260 | 260 | 260 | 260 | 260 |
| Halved (ratio 0.5, floor 244.4) | 244.4 | 260 | 253 | 249 | 246 |
| Stopped (ratio 0, floor 228.8) | max(232, 228.8) = 232 | 260 | 248 | 241 | 235 |
| No qualifying ride at all | 260 × 0.88 = 228.8 | 260 | 247 | 239 | 232 |

What this means for the brief's 3 h endurance ride (NP 185 W, 3.31 h):

| | IF | TSS |
|---|---|---|
| Today, rated against 232 W | 0.80 | 211 |
| Halved-load case, rated at day 28 (249 W) | 0.74 | 182 |
| Stopped case, rated at day 28 (241 W) | 0.77 | 195 |

---

## 11. Suggested PR sequence

Each PR is a parity pair: both backends together.

1. **IF/TSS write-back fix and manual-edit re-rating** (section 8), plus
   documenting the recompute endpoints. Independent, and the user-visible fix
   ships fastest.
2. **Settings persistence fix** for window and sanity (6.2). Small.
3. **`kind` column**: migrations, entity and model, API and export exposure,
   and the indicator filter. No behaviour change.
4. **Manual value replaced early** (1.1), decay-off path only, with its
   regression test.
5. **Decay core**:
   - the pure module and parity fixtures,
   - suffix re-simulation,
   - rebuild keeping manual rows,
   - the daily job,
   - the staleness change,
   - the suggestion changes,
   - the settings fields and openapi,
   - doc section 3.5.
6. **Frontend**: settings UI, summary and ledger display, suggestion wording.

---

## 12. Open questions

1. **Maintained load never decays.** With ratio 1 the floor equals the anchor,
   so FTP holds indefinitely until a better effort arrives. That's consistent
   with Hickson, and it's the brief's model. Accept it?
2. **Volume can stand in for intensity.** TSS weights intensity by IF², but a
   high-volume Z2 block can still reach the same CTL as a structured block, and
   so hold the ratio near 1. Hickson suggests intensity is what preserves
   threshold. *Recommendation:* ship v1 as designed. If the brief's athlete
   still sees no decay, consider counting only time above Z2 in the load term.
3. **No baseline load** (`L(a) < 1`), for example a new athlete's manual FTP.
   *Recommendation:* ratio 1, so the successor and the no-successor fallback
   drive the target. The alternative is the full `max_pct`.
4. **Submaximal re-anchoring.** An easy ride with `x ≥ round(v)` re-anchors at
   a submaximal value and restarts `t`. *Recommendation:* accept this. It's
   bounded, slow, and real tests override it. Optionally require
   `x ≥ round(v) + 1` to cut churn.
5. **Defaults.** On for new athletes, off for existing ones, with a prompt to
   rebuild. Or switch it on for everyone but start decay from the date it's
   enabled? The latter needs an extra `threshold_decay_since` column.
   *Recommendation:* the simpler off-for-existing.
6. **Should a decay step re-rate past activities automatically?** It rewrites
   CTL history quietly. *Recommendation:* yes, since that's the point of the
   fix, but say so in the Preferences help text.
7. **No-successor target.** `A·(1 − max_pct)` per the brief, or the same
   load-based floor? The load falls naturally with no rides, so they converge
   anyway, though more slowly with the load-based floor. *Recommendation:* keep
   the brief's rule.
