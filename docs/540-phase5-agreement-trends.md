# Phase 5: agreement trends over time

Design for issue #540's phase 5 (the other half, phase 6, is `540-phase6-contact-loss-repair.md`).
Builds on #503/#538 phases 1-4 (multi-source storage, primary switching, `GET .../streams/compare`).
This is a design + implementation plan for a coding agent to follow, not just rationale - it
makes the calls the tracking issue deliberately left open.

## 1. What phase 4 gives us, and the one thing it doesn't

`compare_streams(activity_id, field, source_a, source_b, ...)` (`backend/activities/activity_source_service.py:551`,
mirrored in `backend_java/.../ActivitySourceService.java`) already computes every statistic phase 5
needs to aggregate (bias/MAE/RMSE/Pearson r/LoA, lag, power fit, dropout counts), for one activity.
`source_a`/`source_b` there are either the sentinel `"primary"` or a real `ActivitySource.id` -
**ids are per-activity and don't carry across activities**, so phase 5 cannot reuse that parameter
shape directly for a multi-activity trend query. This is the one real design gap the issue leaves
open, and the rest of this doc is mostly about closing it.

## 2. Device-tag vocabulary (the key decision)

Every field already has a *device-class* vocabulary, independent of any specific `ActivitySource`
row, used today only to decide `is_primary` (`_is_primary_for_field`,
`activity_source_service.py:463`):

| field            | vocabulary            | how a given activity resolves to one          |
|------------------|------------------------|------------------------------------------------|
| `power` (bike)   | `trainer`, `pedals`    | `classify(device)` on whichever source (primary's own `Activity.device`/`bike_power_source`, or an `ActivitySource.device`) |
| `power` (run)    | `native`, `stryd`      | `Activity.power_source` / `ActivitySource.source_kind` |
| `heartrate`      | `strap`, `wrist`       | `Activity.hr_source` / `ActivitySource.source_kind` |
| `cadence`        | `pedals`, `trainer`, `watch` | `Activity.cadence_source` / `ActivitySource.source_kind` |

Phase 5's `deviceA`/`deviceB` (renamed `device_a`/`device_b` below, matching `source_a`/`source_b`'s
snake_case) are **these device-class tags, not source ids**. This is what makes a trend query
meaningful: "pedal vs trainer power ratio over time" means exactly this vocabulary; a raw
`ActivitySource.id` would be a different, nonexistent id on every other activity.

Consequence worth calling out explicitly: which tag is *primary* can itself change activity to
activity (that's the point of #503 phases 1-3 existing at all) - the trend's bias series is
expected to be computed from whichever side was live that day, not a fixed primary/non-primary
role. A sudden bias jump on the date a primary swap happened is a real finding, not a bug.

### Resolving a tag to a series, per activity

New helper, next to `_load_field_series` in `activity_source_service.py` (and the mirrored spot in
`ActivitySourceService.java`):

```python
def _resolve_tagged_series(activity: Activity, field: str, tag: str) -> dict[int, int] | None:
    """None if this activity has no stream matching tag for field at all (neither primary nor a
    stored ActivitySource) - the caller skips the activity rather than erroring, since "this
    device wasn't used that day" is the expected common case across a date range, not a fault."""
```

Logic: if the tag matches the activity's *current* primary classification for `field`
(reuse `_current_primary_source`/`classify` for bike power, `Activity.power_source`/`hr_source`/
`cadence_source` otherwise - exactly the comparisons `_is_primary_for_field` already makes, just
read the other direction), return the primary series (`_load_field_series(activity, field,
PRIMARY_SOURCE_REF)`). Otherwise look for an `ActivitySource` on this activity with matching
`sensor_kind`/classification and return its stream. **Decision**: if more than one stored source
matches the same tag on one activity (schema allows it; practice shouldn't produce it), use the
most-recently-created one and don't treat it as an error - this mirrors no existing precedent
exactly, so flag it in the PR description as a judgment call rather than silently asserting
uniqueness.

### Validation

`field` reuses `VALID_FIELDS`. `device_a`/`device_b` are validated against the field's vocabulary
above - for `field="power"` the vocabulary is sport-dependent (bike vs run), so **`sport` becomes a
required parameter when `field="power"`** (not optional as the issue's sketch implied) - there's no
sensible single vocabulary otherwise, and mixing bike/run power into one trend is meaningless
anyway. For `heartrate`/`cadence`, `sport` stays optional and just filters which activities are
included.

## 3. API

Corrected path: the issue's sketch (`GET /v1/streams/agreement`) has no athlete id, which doesn't
match the rest of the API - every cross-activity athlete-scoped resource lives under
`/v1/athletes/{id}/...` (e.g. `v1/athletes/<id>/fitness`, `backend/athletes/urls.py`). Use:

```
GET /v1/athletes/{id}/streams/agreement
    ?field=power|heartrate|cadence
    &device_a=<tag>&device_b=<tag>
    &sport=<sport>              (required when field=power, else optional)
    &from_date=YYYY-MM-DD&to_date=YYYY-MM-DD   (same naming/validation as get_fitness_trend, backend/athletes/mcp.py:238 - to_date defaults to today, from_date defaults to to_date minus a window default, both parsed with django.utils.dateparse.parse_date)
    &exclude_dropouts=true|false  (power only, default true, same meaning as compare_streams)
```

Response:

```json
{
  "field": "power", "device_a": "pedals", "device_b": "trainer", "sport": "bike",
  "from_date": "2026-06-01", "to_date": "2026-11-21",
  "points": [
    {
      "activity_id": "act_...", "name": "...", "date": "2026-06-03",
      "overlap_seconds": 1830, "bias": -8.2, "mae": 11.4, "rmse": 14.9,
      "pearson_r": 0.97, "within_tolerance_pct": 71.2, "lag_seconds": 1,
      "dropout_seconds_a": 4, "dropout_seconds_b": 0
    }
  ],
  "skipped": { "no_device_a": 3, "no_device_b": 12, "insufficient_overlap": 1 }
}
```

One point per activity where **both** tags resolve to a series with enough overlap
(`stream_comparison.MIN_OVERLAP_SAMPLES`, reused as-is), and the two tags differ - `device_a ==
device_b` is a 400, same as `compare_streams` rejecting `source_a == source_b`
(`activity_source_service.py:577`, `{"device_b": "device_a and device_b must be two different
device tags."}`). `power_fit` is **not** in v1 (matches the `AgreementTrendPoint` dataclass in
section 4 and open question #3 below - this is the one field phase 4's response has that this
trend point deliberately omits for now). `zone_breakdown` is **not** included at trend level
either (too heavy per-activity for a list endpoint spanning months - out of scope, not an
oversight). `skipped` exists because "why is my date range mostly empty" needs an answer that
isn't silence - reuse this shape rather than raising on zero points.

No pagination in v1. The per-activity cost is non-trivial even though the activity *count* is
small - each activity's `_load_field_series` call loads its full `Record` set (e.g. ~7200 rows for
a 2h ride at 1Hz) or an `ActivityStream`'s full `values` array, then runs comparison + lag (+
dropout) math, same O(samples) cost `compare_streams` already pays once per request - a trend
query just pays it once per activity in range instead. This is the same shape of acceptable cost
`core/derived.py`'s `_daily_above_z2_load` already accepts for a similar per-activity aggregation
("acceptable for a calibration tool queried occasionally over a bounded window, not a
high-frequency dashboard path - revisit if that ever changes") - cite that precedent rather than
re-arguing it, and inherit its caveat rather than hand-waving this away as "small." Note also that
`_load_field_series`'s non-primary branch is a `get_object_or_404(ActivityStream, ...)` per source
(`activity_source_service.py:528`) - a plain `prefetch_related` on `Activity.sources`/
`Activity.streams` only avoids the N+1 on the Django ORM side if `_resolve_tagged_series` is
written to look the prefetched querysets up in memory instead of calling `_load_field_series` (and
therefore `get_object_or_404`) directly; do that, don't just slap `prefetch_related` on the outer
query and call it solved.

## 4. Service layer

New module `backend/activities/agreement_trend_service.py` (mirrored
`backend_java/src/main/java/com/cadence/api/activities/AgreementTrendService.java`), not folded
into `activity_source_service.py` - it's a distinct orchestration (iterate activities, skip/
aggregate) over that module's existing per-activity building blocks, not an extension of them.

```python
@dataclass(frozen=True)
class AgreementTrendPoint:
    activity_id: str
    name: str
    date: date
    overlap_seconds: int
    bias: float
    mae: float
    rmse: float
    pearson_r: float
    within_tolerance_pct: float
    lag_seconds: int | None
    dropout_seconds_a: int = 0
    dropout_seconds_b: int = 0

def compute_agreement_trend(
    athlete_id: str, field: str, device_a: str, device_b: str,
    sport: str | None, from_date: date, to_date: date, exclude_dropouts: bool = True,
) -> tuple[list[AgreementTrendPoint], dict[str, int]]:
    """Issue #540 (phase 5). Iterates the athlete's activities with field's required stream kind
    present (Activity.sport filter applied if given) in [from_date, to_date], resolves device_a/
    device_b each to a series via _resolve_tagged_series, and reuses stream_comparison.compare
    (same tolerance rule compare_streams uses) plus find_lag and - for field="power" - the same
    dropout detection compare_streams runs, per activity. Returns the points plus a skip-reason
    tally. Never raises for "not enough data on this one activity" - that activity is just
    skipped and counted; only a bad field/device_a/device_b/sport combination is a 400."""
```

`Activity` has no `date` field - its date is `start_date` (a `DateTimeField`,
`backend/activities/models.py:75`); the existing precedent for a date-range filter on it is
`start_date__date__range=(...)` (`core/derived.py:119`), and a point's `date` comes from
`activity.start_date.date()`, not a bare `activity.date`.

Duplicate/multisport-child exclusion is also not optional here, not an oversight to leave silent:
every other multi-activity aggregation in this codebase excludes both
(`core/derived.py:119-122`'s `_daily_tss`/`_daily_above_z2_load`) - a ride uploaded twice (#498)
would otherwise contribute two points for one real ride, possibly with different agreement stats
between the duplicate and its primary. Match that precedent by default:

```python
qs = Activity.objects.filter(
    athlete_id=athlete_id, start_date__date__range=(from_date, to_date), parent_activity__isnull=True,
)
if sport:
    qs = qs.filter(sport=sport)
if not include_duplicates:  # default True->excluded, mirroring _daily_above_z2_load's own flag
    qs = qs.filter(primary_activity__isnull=True)
qs = qs.order_by("start_date")
```

Decide whether to expose `include_duplicates` as a query/MCP param (mirroring
`_daily_above_z2_load`'s own flag) or just hardcode the exclusion - either is reasonable; don't
leave it unfiltered by default either way.

`select_related`/`prefetch_related` sources as needed to avoid N+1 (a
`.prefetch_related("sources", "streams")`-equivalent per the existing query patterns in
`activity_source_service.py`) - but note the performance caveat two sections up:
`_resolve_tagged_series` must consult the prefetched `activity.sources`/`activity.streams` querysets
directly rather than calling `_load_field_series` (which does its own `get_object_or_404` per
source) for this to actually avoid the N+1.

## 5. REST view + MCP tool

View: `AgreementTrendView` in `backend/activities/views.py` (or `backend/athletes/views.py` -
put it wherever `FitnessListView`/`AthleteDurabilityView` live, since the URL is athlete-scoped
like those, not activity-scoped like `StreamComparisonView`). Same `user_may_read`/
`get_effective_athlete_id` guard those views use. Route it in `backend/athletes/urls.py` as
`v1/athletes/<str:id>/streams/agreement`.

MCP: add `stream_agreement_trend(field, device_a, device_b, sport=None, from_date=None,
to_date=None, exclude_dropouts=True)` to `AthleteMCPTools` in `backend/athletes/mcp.py` (not
`ActivityMCPTools` - it's athlete-scoped, same home as `get_fitness_trend`), `_require_scope
(ACTIVITIES_READ)`, delegating straight to `agreement_trend_service.compute_agreement_trend`.
Docstring should spell out the device-tag vocabulary per field exactly as the table in section 2 -
an MCP caller has no other way to discover it.

`openapi.yaml`: new `/v1/athletes/{id}/streams/agreement` path entry, modeled directly on the
existing `/v1/activities/{id}/streams/compare` entry (`openapi.yaml:1249`) - same style of
`description:` prose, a `parameters:` block for `field`/`device_a`/`device_b`/`sport`/`from_date`/
`to_date`/`exclude_dropouts`, and a response schema under `components/schemas` mirroring the
existing `comparison`/`lag` nesting shape at `openapi.yaml:6084` but flattened per-point as shown
in section 3.

## 6. Frontend

New chart component `frontend/src/screens/DeviceAgreementTrendChart.tsx`, explicitly reusing
`frontend/src/screens/workouts/WorkoutComparisonChart.tsx`'s pattern (d3-array/d3-scale/d3-shape,
padded linear y-axis, hover crosshair via `x.invert()`, click-through `Link` to the activity) per
the issue's own note that it's the closest existing precedent - don't redesign the charting
approach, copy the structure and swap the point shape (`activityId/name/date/value` stays the
same; `value` is whichever stat the page is currently showing - start with `bias`, offer a
dropdown for `mae`/`pearson_r`/`within_tolerance_pct` later if wanted, don't build that toggle in
v1 unless it's cheap). Placed top-level next to its screen, not under `activity-analysis/` - that
directory is specifically the per-activity analysis tabs/cards, and this chart is athlete-level,
cross-activity; `ThresholdHistoryChart.tsx`/`ThresholdHistoryScreen.tsx` living together at
top-level (not `WorkoutComparisonChart.tsx`'s own nested `workouts/` placement, which is
`WorkoutComparisonScreen`-specific) is the closer precedent for *where* this one lives, even
though `WorkoutComparisonChart.tsx` is the one being copied for *how* it's built.

New screen `frontend/src/screens/DeviceAgreementTrendScreen.tsx`, modeled on
`ThresholdHistoryScreen.tsx`'s shape (per-field page, date-range aware) - field/device_a/device_b/
sport pickers (device options populated from the vocabulary table in section 2, hardcoded per
field client-side, not fetched), the trend chart, and the `skipped` counts rendered as a small
caption (e.g. "12 activities in range had no wrist HR source"). New route
`/devices/:field/agreement-trend` in `App.tsx`, linked from `PreferencesScreen.tsx`'s existing
sensor-source-preference section (`hr_source`/`cadence_source`/`bike_power_source` pickers already
live there - add a "View agreement trend" link next to each, don't invent a new nav location).

API client: `getDeviceAgreementTrend(athleteId, field, deviceA, deviceB, sport, fromDate, toDate,
excludeDropouts)` belongs in `frontend/src/api/athletes.ts` (not `activities.ts`) - that's where
`getFitness` (the `get_fitness_trend`-equivalent client call) already lives,
`frontend/src/api/athletes.ts:40` - plus the matching response type in `frontend/src/api/types.ts`
next to `StreamComparisonResult`.

## 7. Tests

- `backend/activities/tests/test_agreement_trend_service.py` (unit, mirrors
  `test_stream_comparison.py`'s style): tag resolution (primary match, stored-source match, no
  match -> skip), sport-required-for-power validation, skip-reason tallying, dropout exclusion
  passthrough.
- `backend_java/.../AgreementTrendServiceIntegrationTest.java`, mirroring
  `ActivitySourceServiceIntegrationTest.java`'s fixture style (watch the "unique fixture literal"
  gotcha in `backend_java/CLAUDE.md`).
- A frontend component test for `DeviceAgreementTrendChart` only if `WorkoutComparisonChart` has
  one to mirror - check first; don't add test infra that precedent doesn't have.

## 8. Explicit open questions (flag these in the PR, don't silently resolve them)

1. Ambiguous multiple-source-per-tag resolution (section 2) - "most recent" is this doc's call,
   not a pre-existing rule.
2. Whether `sport` should be *optional-but-defaulted* for power (e.g. infer from whichever sport
   has activities in range) instead of required - this doc chose required-and-explicit for
   simplicity; revisit if it's annoying in practice.
3. Whether the trend should expose `power_fit.scale` over time as its own line (drivetrain-loss-
   over-time, the issue's literal example) in v1, or whether `bias`/`ratio_of_means` is enough to
   start - this doc scoped v1 to the `AgreementTrendPoint` fields listed in section 4; add
   `power_fit` fields to the point shape in a follow-up if the first cut proves too thin.
4. Whether duplicate-activity exclusion (section 4) should be a caller-facing
   `include_duplicates` param (mirroring `_daily_above_z2_load`'s own flag) or just a hardcoded
   default - this doc states the exclusion must happen by default either way; which form it takes
   is still open.
