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
| Decay model | As proposed, but evaluated as a **daily recurrence** towards a moving target rather than a closed form anchored to `ftp_last`, and **never easing upwards**. Amplitude has a **minimum** (`A_min` 3%), so an unverified value always drifts a little, even with load maintained (see 2.2) |
| "Current best" | Comes from the **ledger** (latest measured or manual row, the "anchor"), not the window scan. The window scan can't see manual rows |
| Load term | Per-sport 42-day EWMA of **intensity-filtered load**: TSS-rate summed over only the 30 s-smoothed samples above Z2 (≥ 75% of the anchor value). The cutoff is fixed at the anchor, so it isn't circular. Read from a new per-activity **intensity histogram**, so no record streams are re-read each day (see 2.3) |
| Calibration | Run on the brief's athlete's real rides (section 13): decaying from 260 W at expiry on 19 Aug would give **≈ 248 W today**, with the above-Z2 ratio at 0.80. Their HR-capped "Z2" rides at ~205 W sit above the 195 W cutoff, so they still count. Their current manual 255 W holds until 22 Feb 2027, then settles at 243–248 W if training continues as now |
| Pace | Decay arithmetic in effort space (speed = 1/pace), so decay always makes pace **slower** (see 2.4) |
| Trigger | **Daily scheduled job** plus the existing ingest and refresh hooks. Not compute-on-read (see 4.4) |
| Day-to-day vs rebuild | One pure **simulation** function used for both, so a rebuild reproduces day-to-day behaviour by construction (see 3, 4.2) |
| Ledger | New `kind` column: `measured` / `manual` / `decayed` (see 5) |
| TSS/IF | Recompute intensity **and** TSS together for activities affected by any ledger change, through one shared helper (see 4.6, 8) |
| Settings | `threshold_decay_enabled` (on for new athletes, **off for existing**), `threshold_decay_tau_days` = 25, `threshold_decay_min_pct` = 3, `threshold_decay_max_pct` = 12. Edited in Preferences → Best efforts → Threshold history (see 6) |
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
   ratio     = clamp(L(d) / L(a), 0, 1)      # 1 if L(a) < 1 (see Q3)
   amplitude = min_pct + (max_pct − min_pct) · (1 − ratio)
   floor     = A · (1 − amplitude)
   target    = max(successor, floor)   if successor exists
             = A · (1 − max_pct)       otherwise
   v         = min(v, target + (v − target) · e^(−1/τ))
   ```

   `min_pct` (default 3%) means a value that hasn't been re-verified always
   drifts a little, even with load fully maintained. A 260 W anchor goes to
   about 255 W at day 28, 253 W at day 56 and 252 W at day 90. This picks up a
   real decline that happens despite training (ageing, illness, a lighter mix),
   which `ratio = 1 → no decay` never could.

   If `round(v)` differs from the last emitted value, emit a `decayed` row with
   `effective_from = current_from = d`.

**Departure: `min(v, …)`, "never ease upwards".** If the load recovers, `floor`
can rise above `v`. Without the clamp, `v` would then climb with no fitness
evidence. With it, the value only goes up through an upward reset, which matches
the brief's "upward evidence wins immediately; don't ease upwards".

**Decay disabled.** Keep today's behaviour: after expiry the successor takes
over at once, as a `measured` row with `effective_from` = the successor's date
and `current_from = d`. The only change is the 1.1 fix.

**Long-term behaviour to be aware of.** `v` decays until some ride implies
`x ≥ round(v)`, which becomes a new anchor. 112 days later that one decays too,
by at least `min_pct`. So without a real test, the value ratchets down by
roughly `min_pct` to `max_pct` per window cycle: about 3% per ~4 months with
training maintained. Each re-anchor does need a ride whose 20-minute best
supports the current value, so this is the intended "test due" pressure rather
than unbounded drift, and any real test resets it upwards. See Q4.

### 2.3 Load term: recommendation

**Agreed: intensity-filtered load, per sport, measured against the anchor.**

Plain TSS still adds up hours. An hour at IF 0.70 is about 49 TSS, so two hours
of Z2 count about the same as one hour at threshold. Hickson found that dropping
intensity loses fitness even when volume is kept up. So the load only counts
work above Z2.

For each activity of the field's sport, the daily "decay load" is a TSS rate
summed over 30 s-smoothed power samples `p` (the same rolling mean NP already
computes):

```
load = Σ_samples  w(p / R) · (p / R)² · 100 / 3600

v1:          w(x) = 1 if x ≥ cutoff (0.75), else 0      # Z3+ only
alternative: w(x) = clamp((x − 0.75) / 0.25, 0, 1)      # smooth ramp, no hard edge
```

Each part of the formula:

- **`R`** is the **anchor value** (`ftp_last`), never the decaying `v`. If the
  cutoff followed `v`, the Z2/Z3 boundary would drop with FTP and more riding
  would count: the circularity problem again. For both run fields `R` is the
  critical-running-power anchor.
- **Z3+, not Z4+.** Tempo and sweet-spot work help hold threshold. A Z4-only
  cutoff would treat a solid sweet-spot block as detraining.
- **`(p/R)²`** keeps TSS's IF² weighting above the cutoff, so a minute at
  threshold still outweighs a minute at tempo.
- **`L(d)`** is a 42-day EWMA of daily load, the same loop as `FitnessService`
  / `core.derived`. Both `L(d)` and `L(a)` are computed with the same `R` and
  cutoff, so the ratio compares like with like.
- **When the anchor changes** (an upward reset or a manual entry), `R` changes,
  so `L` is recomputed over the whole history. That's why the next point
  matters.

**Storage: a per-activity intensity histogram.** The cutoff depends on the
anchor, so load can't be precomputed as one number. Re-reading record streams
for every activity on every evaluation would be far too expensive: one
athlete's history is millions of `Record` rows, against a 600 MB container on
staging. Instead, store a compact histogram once per activity:

- seconds per whole watt of 30 s-smoothed power,
- seconds per bpm of heart rate, for the fallback below.

That's a few hundred keys per activity. Any `R`, cutoff or weighting can then be
evaluated exactly from it, with no streams. Switching v1's hard cutoff to the
smooth ramp later needs no reprocessing. Schema and backfill are in section 5.

**HR fallback for activities without trusted power** (HR-only rides, runs
without power, runs whose power source the athlete excludes): the same formula
on the HR histogram, with `R = LTHR`. LTHR is manual and has no ledger, so this
isn't circular. Proposed cutoff: 0.85 × LTHR. HR is compressed relative to
power, so its Z2/Z3 boundary sits higher as a fraction of threshold; this needs
calibrating (Q7). An activity with neither power nor HR contributes no load.

**Why not TRIMP / hrTSS for everything:**

- It's missing whenever there's no HR strap. Rides with power but no HR would
  count as zero load, and the athlete would look like they'd stopped.
- Edwards' TRIMP weights intensity linearly by zone number, not quadratically,
  so it under-credits the intensity that Hickson says preserves threshold.

**Pace field.** It uses the same run load as critical running power: run power
against the critical-running-power anchor, with the HR fallback for runs
without trusted power.

**Known sensitivity at the boundary.** For the brief's athlete, the cutoff is
0.75 × 260 = 195 W, and their HR-capped "Z2" rides sit at 182–215 W. So the
same ride type falls either side of the cutoff depending on the day.
Section 13 measures this on real data: the 30 s smoothing and the 42-day
average absorb most of it, and the smooth ramp lands within 1 W of the hard
cutoff.

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
DecaySettings(enabled, window_days, sanity_pct, tau_days, min_pct, max_pct)
Anchor(value, effective_from, kind, activity_id)
Candidate(date, implied_value, activity_id, accepted)     # reuse the existing dataclass/record
Step(value, kind, activity_id, effective_from, current_from)

simulate(field, settings, seed_anchor, seed_value, seed_window,
         candidates_by_day, manual_by_day, load_by_day,
         start, end) -> list[Step]

intensity_histogram(power_series, hr_series) -> (power_30s_hist, hr_hist)
                                                     # at ingest and recompute-stats
activity_load(power_30s_hist, hr_hist, R, lthr, cutoff=0.75, hr_cutoff=0.85) -> float
decay_load_series(daily_loads) -> dict[date, float]                    # the 42-day EWMA
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

**Status: deferred, tracked as #548, split out of #485.** The other four
triggers below (ingest, manual edit, refresh, rebuild - all wired in #546)
already self-correct a stale ledger the next time any of them fires for a
given field, per "missed days are harmless" below. The daily job only matters
for an athlete who goes quiet on a *specific sport* for longer than its
trailing window - e.g. a cyclist who stops riding but keeps running would
never see `ftp` decay without it. Given `threshold_decay_enabled` defaults to
`false` for everyone right now (#546 - flipping the new-athlete default was
itself deferred, since no athlete is actually decaying yet), there's no
urgency to build this before the rest of the feature (staleness display,
settings API, frontend) exists. Revisit before flipping that default, or
sooner if a specific athlete's usage pattern needs it.

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
  `decay` object (7.1). The UI shows the value as "Modelled · test due".
- **The stale / test-due flag doesn't depend on load.** With `min_pct` above 0,
  even a fully maintained athlete decays slightly. So a decayed value is always
  flagged, and can never be mistaken for a measured one, however much the
  athlete is still training.

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

### `activity_intensity_histogram` (new table, for the load term in 2.3)

| Column | Type | Meaning |
|---|---|---|
| `activity_id` | PK, FK → activity, `ON DELETE CASCADE` | One row per activity |
| `power_30s` | jsonb, nullable | `{watts: seconds}` of 30 s-smoothed power, whole watts. Running power goes through the same sanitiser as every other running-power consumer |
| `heartrate` | jsonb, nullable | `{bpm: seconds}` |

| | Change |
|---|---|
| Django | `activities/migrations/0024_activityintensityhistogram.py`. A model `ActivityIntensityHistogram` with a `OneToOneField(primary_key=True)` |
| Flyway | `V92__activity_intensity_histogram.sql`, plus a matching JPA entity and repository |

A separate table rather than columns on `Activity`, because Java's `Activity`
entity is loaded on nearly every request and these maps are only needed by the
decay job.

**When it's written:**

- At ingest, next to NP. Java: `ComputeDerivedStatsTasklet`. Django:
  `_ingest_activity`. Both already compute the 30 s rolling mean.
- By `recompute-stats`, single and bulk.

**Backfill: lazy.** The decay loader computes a missing histogram from records
the first time it needs one, and persists it. Enabling decay prompts a rebuild
anyway, so that's where most of the backfill happens; the bulk "Recompute
stats" also fills them.

**Not exported.** It's derived from records, so an import recomputes it lazily.

**Parity note.** Django has no running-power spike filter (`max_running_power_watts`
is Java-only; see "Backend differences" in `docs/thresholds-and-zones.md`), so
run histograms can differ for spiky Stryd files. The parity fixtures avoid
spikes, and closing that gap is separate work.

### User settings

| Field | Type | Default (new athletes) | Existing athletes |
|---|---|---|---|
| `threshold_decay_enabled` | bool | `true` | `false` |
| `threshold_decay_tau_days` | small int, 7–90 | 25 | 25 |
| `threshold_decay_min_pct` | small int, 0–10, ≤ max | 3 | 3 |
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
- **Minimum decay (%)**: number input, 0–10, disabled when decay is off. Help:
  "How far an unverified value drifts even if you keep training as hard as
  before. Set to 0 to hold it while training is maintained."
- **Maximum decay (%)**: number input, 0–30, disabled when decay is off. Help:
  "How far it can fall with no riding above Z2 at all." Client-side validation
  keeps minimum ≤ maximum, and the backends enforce it too.
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
| Java | Append `thresholdWindowDays`, `thresholdSanityPct` and the four decay fields to the **end** of `AthleteUpdateRequest` (see the positional-record gotcha in `backend_java/CLAUDE.md`). Handle them in `AthleteService.updateProfile` with clamping, and set `suggestionsAffected` |
| Django | Add all six to the update serializer `fields` and `extra_kwargs`, with `min_value` / `max_value`, plus a `validate()` for min ≤ max. Add the decay settings to `_SUGGESTION_AFFECTING_SETTINGS` in `athletes/views.py` |
| Ranges | Window 1–365 and sanity 1–100, matching the UI; tau 7–90; min 0–10; max 0–30; min ≤ max (400 if violated after merging with stored values) |

Changing a decay setting isn't retroactive, matching the window/sanity
convention. The daily job applies new settings from the next day, seeded from
the existing ledger.

---

## 7. API, `openapi.yaml`, MCP, frontend

### 7.1 `openapi.yaml`

- **`User`**: the four decay fields, with defaults and descriptions.
- **PATCH `/v1/athletes/{id}`**: the four decay fields, plus clamping notes
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
- **Load-ratio clamp and amplitude:** ratio > 1 is clamped to 1, which gives
  exactly `min_pct` (a 260 W anchor targets 252.2 W, **not** 260); ratio 0
  gives `max_pct`; `L(a) < 1` gives a ratio of 1. `min_pct = 0` reproduces
  "no decay with maintained load".
- **Minimum drift with maintained load:** 260 W, ratio held at 1 → 257 / 255 /
  253 W at t = 14 / 28 / 56.
- **Pace:** a decayed `threshold_pace` is non-decreasing in seconds/km, and the
  floor for a 240 s anchor is 273 s.
- **Disabled mode** reproduces the existing replay's ledger on the current
  replay fixtures, so existing behaviour is pinned.
- **Manual anchor before expiry** isn't replaced by a lower later ride (the 1.1
  regression).
- **Load filter:**
  - a sample at exactly 0.75 R counts and one at 0.749 R doesn't (hard
    cutoff);
  - a steady 2 h ride at 0.70 R contributes 0, and a 1 h ride at 0.80 R
    contributes 64;
  - the smooth-ramp variant gives 0 at 0.75 R and full weight at 1.0 R.
- **No circularity:** the load series is unchanged when the FTP ledger (and
  so stored TSS and IF) changes, and changes only when the anchor `R` changes.
- **Histogram:** `intensity_histogram` of a known series yields the expected
  30 s-smoothed bins, and `activity_load` from the histogram equals the load
  computed directly from the series, to 1e-9.

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
  - the real-data-shaped case from section 13 (the steady and interval
    templates, cutoff 75%),
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
- **Settings PATCH** persists window, sanity and the four decay fields (the
  6.2 regression), with clamping.
- **Export/import** round-trips `kind`, and an old-format file infers it.
- **Section 8** regression tests.

### 9.4 Scenario regression: the brief's case

Two fixtures.

**Synthetic.** A 260 W 20-minute-test ride, then 16 weeks of rides implying
225–235 W with the above-Z2 load halved. Assert:

- no single-day drop greater than 1%,
- FTP is 252 W at 14 days after expiry and 247 W at 28 days (the "halved" row
  of the worked example in section 10),
- the 3 h, 185 W NP ride rated 28 days after expiry has IF ≈ 0.75 and
  TSS ≈ 186, not 0.80 / 211.

**Real-data-shaped.** Section 13's rides, from 1 Dec 2025 to 1 Nov 2026, with
the 260 W anchor on 28 Apr and the successor at 232 W. Assert:

- the expiry is 19 Aug,
- FTP is 255 / 252 / 250 W on 2 Sep / 16 Sep / 14 Oct, and 248 W on 2 Nov,
- the 1 Nov ride rates at about IF 0.75 / TSS 184.

These numbers come from the section 13 model; regenerate them from real
histograms once the feature exists.

### 9.5 Frontend (vitest)

- The suggestion verb depends on `decays`.
- The summary card shows "Modelled" for a decayed entry and uses the API's
  `stale`.
- The BestEffortsTab Save sends all six settings, enables only on change, and
  blocks min > max.

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
- **Section 6:** the four settings, and note that window/sanity now save.
- **Backend differences:** nothing new expected, since the parity fixtures
  enforce it. The existing running-power-spike note stays.

### Worked example

260 W anchor expires on day 0; successor 232 W; τ = 25; `min_pct` = 3%;
`max_pct` = 12%. "Load" means the above-Z2 load from 2.3.

| Above-Z2 load vs the anchor date | Amplitude | Target | t = 0 | 14 | 28 | 56 |
|---|---|---|---|---|---|---|
| Maintained (ratio 1) | 3% | 252.2 | 260 | 257 | 255 | 253 |
| Halved (ratio 0.5) | 7.5% | 240.5 | 260 | 252 | 247 | 243 |
| None (ratio 0; floor 228.8 is below the successor) | 12% | 232 | 260 | 248 | 241 | 235 |
| No qualifying ride at all | 12% | 228.8 | 260 | 247 | 239 | 232 |
| **The brief's athlete, real rides** (section 13; the ratio falls 0.99 → 0.80) | 3–4.8% | 252 → 248 | 260 | 255 | 252 | 250 |

What this means for the brief's 3 h endurance ride (NP 185 W, 3.31 h):

| | IF | TSS |
|---|---|---|
| As it happened, rated against 232 W | 0.80 | 211 |
| Maintained, rated at day 28 (255 W) | 0.73 | 174 |
| Halved, rated at day 28 (247 W) | 0.75 | 186 |
| Real rides, rated on 1 Nov (≈ 248 W) | 0.75 | 184 |

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
5. **Decay core** - shipped as four separate slices instead of one PR (each
   turned out independently reviewable; see the issue links):
   - the pure module and parity fixtures (#543),
   - the `activity_intensity_histogram` table, written at ingest and recompute-stats, with lazy backfill (#544),
   - suffix re-simulation into ingest/manual-edit/refresh/rebuild, rebuild keeping manual rows, the per-athlete row lock (#546),
   - the daily job - **split out to #548, deferred** (4.4),
   - the staleness change,
   - the suggestion changes,
   - the settings fields and openapi,
   - doc section 3.5.
6. **Frontend**: settings UI, summary and ledger display, suggestion wording.

---

## 12. Open questions

1. ~~**Maintained load never decays.**~~ **Resolved:** `min_pct` (3%) gives a
   small decay even with load fully maintained, so the model can pick up a
   decline that happens despite training. The stale / test-due flag applies to
   every decayed value regardless (4.5).
2. ~~**Volume can stand in for intensity.**~~ **Resolved:** the load counts only
   30 s power above 75% of the anchor (2.3). On the brief's athlete's real
   rides, plain TSS load *rose* 7% across the "Z2 block" (ratio 1.0, no extra
   decay), while the filtered load fell to 0.80 (section 13).
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
8. **HR fallback cutoff.** The proposal is 0.85 × LTHR with `(HR/LTHR)²`
   weighting, for activities without trusted power. It's uncalibrated.
   *Recommendation:* check it against a few rides that have both power and HR
   before shipping. For the brief's athlete, nearly every ride has power, so it
   barely matters for them.
9. **Hard cutoff vs smooth ramp.** On real data the two land within 1 W of each
   other (section 13). *Recommendation:* ship the hard cutoff as agreed. The
   histogram means switching later needs no reprocessing.
10. **Check before calibrating: the 20-minute window doesn't match the
    duration curve.** The 28 Apr ride's duration-curve 20-minute best is
    268.3 W, which implies 255 W, but the ledger recorded 260 W (that's
    273.7 W × 0.95). The successor shows the same ~2% gap: no ride's curve
    implies 232 W. The threshold calculation's sample-count window and the
    duration curve evidently measure "20 minutes" differently (gaps or pauses
    in the record stream are the likely cause). This doesn't change the decay
    design, but it means the anchor itself may be about 2% high. Worth its own
    small investigation.
11. **Stored IF isn't always consistent with the ledger.** The 23 Oct ride's
    stored IF of 0.799 with 205 W average power needs an FTP of about 257 W or
    more, but the ledger said 232 W on that date. So some activities were rated
    against a different value than `reference_for` would give now. 4.6's
    "re-rate on every ledger change" fixes this going forward. A one-off bulk
    re-rate after PR 1 would clean up history.

---

## 13. Calibration on the brief's athlete's real rides

This section runs the proposed model (2.2 and 2.3; `min_pct` 3%, `max_pct` 12%,
τ 25, cutoff 0.75 × anchor) on the athlete's real bike history, read through
the Cadence MCP: every ride from 1 Dec 2025 to 1 Nov 2026, duplicate
recordings removed.

### Inputs

| Input | Value | How it was established |
|---|---|---|
| Anchor | 260 W, 28 Apr 2026 | The ledger's previous value was 232 W, so 260 W came before it. Stored IFs bracket the drop: the 7 Aug SST ride (avg 211 W, IF 0.863) is only consistent with 260 W, and the 20 Aug ride (IF 1.004, 60 min best 228.5 W) only with 232 W. The drop therefore fell between 17 and 20 Aug, which puts the source 113 days earlier, on 28 Apr. That ride's duration-curve 20 min best is 268.3 W (see Q10) |
| Expiry | 19 Aug 2026 | |
| Successor | 232 W | The value the ledger dropped to, unchanged until the manual edit |
| Current value | 255 W manual, 1 Nov 2026 | `get_athlete_thresholds` |
| Ride intensity distribution | Approximated from real templates | The MCP doesn't expose per-second power. Each steady ride uses the 23 Oct "Zone 2 Workout 90" 15 s stream, shifted to that ride's average power. Each interval ride (Gorby, threshold, SST, over/unders, tempo, climbs, the test) uses the 24 Sep Gorby stream, scaled to its average. Both are smoothed to 30 s |

A normal approximation fitted from stored IF was tried first and dropped: some
stored IFs aren't consistent with the ledger (Q11), and interval sessions are
bimodal (~295 W work, ~150 W recovery), which a normal curve can't represent.

### What decay would have done since 19 Aug

| Load definition | Ratio on 2 Nov | Target | 2 Sep | 16 Sep | 14 Oct | 2 Nov |
|---|---|---|---|---|---|---|
| **Above-Z2, hard cutoff at 75%** (as agreed) | 0.80 | 248 | 255 | 252 | 250 | **248** |
| Above-Z2, hard cutoff at 80% | 0.69 | 245 | 254 | 251 | 248 | 246 |
| Above-Z2, smooth ramp from 75% | 0.82 | 248 | 255 | 253 | 252 | 249 |
| Plain TSS, unfiltered (for comparison) | 1.00 | 252 | 256 | 255 | 253 | 252 |

### Findings

- **Plain TSS load went *up* during the "Z2 block".** It rose from 31.5 at the
  anchor to 33.6 on 1 Nov, so on plain load, training looks fully maintained.
  This confirms the agreed concern: volume masks the loss of intensity.
- **The filter moves the ratio, but only to 0.80, because the "Z2" isn't
  power-Z2.** The HR-capped rides (≤ 125 bpm) average 183–215 W against a
  cutoff of 195 W, so most of the 205 W rides still count as tempo. Only the
  ~182–186 W rides, and the 1 Nov Watopia ride, fall mostly below the cutoff.
- **The result lands where the athlete estimated.** A modelled 248 W today
  sits a few watts under the athlete's own 255 W estimate, and well above the
  232 W cliff. TSS for the 1 Nov ride would have been about 184, not 211.
- **The cutoff choice matters less than feared.** Moving it from 75% to 80%
  shifts today's value by 2 W, and the smooth ramp by 1 W.

### Projection for the current 255 W (manual, 1 Nov)

Under the new rules, the manual value is the anchor. Lower rides can't replace
it before it expires, which fixes 1.1. Expiry is 22 Feb 2027; after that it
decays against the above-Z2 load measured relative to 1 Nov, with `R` = 255 W
and a cutoff of 191 W.

| Bike training from now on | Ratio | Target | 22 Mar 2027 | 19 Apr | 23 May |
|---|---|---|---|---|---|
| Same mix as the six weeks to 1 Nov | ≈ 1.0 | 247 | 250 | 248 | 248 |
| Less tempo / sweet spot, like the drop seen since April | ≈ 0.8 | 243 | 247 | 244 | 243 |
| Half the above-Z2 riding | 0.5 | 236 | 242 | 238 | 236 |
| No riding above Z2, or no bike | 0 | 224 | 234 | 228 | 225 |

Any ride with an implied FTP of at least the current value before then resets
the anchor (an upward reset), as does a new manual entry after a test. Without
that, the value is flagged "Modelled · test due" from 22 Feb onwards.

### Caveats

- **The ride shapes are approximations.** They come from two real templates,
  not each ride's own stream. The production feature computes from real
  per-activity histograms (5), so expect the numbers to move by a watt or two.
- **The successor's exact value isn't confirmed.** No ride's duration curve
  implies exactly 232 W (Q10). In this case it doesn't matter, because the
  floor (≥ 245 W in every variant) is above it throughout.
- **The anchor may be about 2% high** (Q10).
