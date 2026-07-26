# Changelog — workout schema + Workout Builder

Extends the planning data model (`workout_step`) to match Garmin FIT / Zwift `.zwo`
workout capabilities, and adds a full workout-building UI (previously
`Workout Designer.dc.html` only supported viewing planned-vs-actual analysis).
Source of truth: `openapi.yaml` (`WorkoutStep` schema), `Data Dictionary.dc.html`,
`Data Schema.dc.html`, `Workout Designer.dc.html`.

## 1. `workout_step` — schema changes

Old shape: flat rows, one scalar target (`target_pct`, always % of threshold),
`repeat` as an int on the leaf row itself.

New shape:
- `target_pct` (numeric) → **removed**, replaced by:
  - `target_type` enum: `power | hr | pace | cadence | open`
  - `target_low`, `target_high` (numeric, nullable) — zone floor/ceiling. Equal
    values = flat target; different values = a ramp (e.g. warmup 50%→70%).
    Unit follows `target_type`: %FTP, %max HR, %threshold pace, or rpm.
  - `target2_type` enum: `cadence | none` (default `none`) — optional secondary
    constraint alongside the primary target (e.g. cadence floor on a power step).
  - `target2_low`, `target2_high` (numeric, nullable, rpm)
- `kind` enum gains a `repeat` value (alongside `warmup | block | rec | cool`).
  A `repeat` row has no `end_type`/`duration`/`distance`/`target_type` — instead:
  - `repeat` (int, default 1) — number of reps
  - `parent_step_id` (uuid, nullable, self-FK → `workout_step.id`) — set on
    **child** steps to indicate which `repeat` row owns them. A repeat group's
    children are the rows carrying this FK, ordered by their own `idx` scoped
    to `parent_step_id` (top-level steps have `parent_step_id = null` and are
    ordered by `idx` scoped to `workout_id`).
  - Groups may nest: a child can itself be `kind=repeat`, enabling structures
    like *2 × [4 × (4min @280W, 4min recovery), 5min recovery]*.
- `note` (text, nullable) — coach cue shown on-device during the step.

New invariants (see Data Dictionary for full CHECK text):
- `kind='repeat'` ⇒ `target_type`/`end_type`/`duration`/`distance` all null,
  and ≥1 other step has `parent_step_id` = this row's id.
- `parent_step_id`, if set, must reference a step with `kind='repeat'` in the
  same `workout_id`.
- `UNIQUE (workout_id, parent_step_id, idx)`.

## 2. `openapi.yaml`

- `WorkoutStep` schema updated to the shape above (`target_type`/`target_low`/
  `target_high`/`target2_*`/`repeat`/`children` — note: the OpenAPI schema
  models nesting via a `children` array on `kind=repeat` steps rather than the
  flat `parent_step_id` self-FK; **pick one representation for the wire format**
  and translate to/from the flat DB rows in the API layer. `children` nested
  arrays are simpler for clients; flat rows keyed by `parent_step_id` are
  simpler for the DB and for arbitrary reordering. This handoff assumes the API
  returns/accepts the nested `children` shape and the persistence layer flattens
  it to `parent_step_id` rows on write.
- `createWorkout` / workout CRUD descriptions updated to describe ramps,
  secondary targets, and nested repeat groups.

## 3. New UI: Workout Builder (Build mode)

`Workout Designer.dc.html` gains a **Build / Analyze** toggle (previously
analysis-only). Both modes coexist in one screen; "Edit structure" /
"View analysis" switches between them. Recreate as two views/routes sharing
one workout-editing data model, or as a single view with a mode flag — the
prototype's own state shape (a `steps` tree + a `mode` flag) is a reasonable
model to copy.

**Build mode contains:**
- **Sport toggle** (Bike / Run) — changes default target type and the
  template list.
- **Structure list** — flat steps (warmup/block/rec/cool) and repeat groups,
  arbitrarily nested. Each row: reorder (↑/↓), duplicate, delete; groups add
  a repeat-count stepper and "+ step in group" / "+ nested repeat group".
- **Chart-first / List layout switch** — the same step tree rendered either
  as a clickable segmented bar chart (target % on the y-axis) or as the list;
  selecting a chart segment selects the same step as clicking its list row.
- **Target drawer** (right rail) — per-step editor: step kind, end condition
  (time — now **hh:mm:ss** — / distance / manual-lap), primary target type +
  low/high fields with a "Ramp" checkbox (single value vs from→to), secondary
  target (cadence only, low/high), and a free-text on-device note. Groups get
  a simpler drawer (repeat count + note only).
- **Templates** — a picker of canned structures (e.g. "VO2 Max 5×5",
  "Threshold 3×10", "Tempo", easy run, progressive long run) that seed the
  step tree.
- **Start from** — new blank workout, or duplicate an existing workout as a
  starting point.
- **Save draft** / **Publish & assign** (coach flow: pick athlete(s) + date)
  / **Export** (.zwo, .tcx — regenerated live from the current step tree;
  note both formats are power-based — non-power target types are approximated
  as %FTP on export, and TCX has no native repeat-of-repeat, so nested groups
  are inlined/flattened on TCX export only, not .zwo).
- Header shows total duration (**hh:mm:ss**), an estimated TSS, and step count,
  all computed by walking the tree recursively (must account for nested
  repeat multipliers — see the prototype's `totalDuration`/`totalTss`/
  `stepCount`/`flattenLeaves` tree-walk helpers as reference logic).

**Analyze mode** is unchanged from the prior handoff (planned-vs-actual power
chart, lap alignment, interval breakdown table).

## Suggested implementation steps for Claude Code
1. DB migration: alter `workout_step` — drop `target_pct`; add `target_type`,
   `target_low`, `target_high`, `target2_type`, `target2_low`, `target2_high`,
   `parent_step_id` (self-FK, nullable), `note`; extend the `kind` enum with
   `repeat`; add the invariants/constraints listed above.
2. Backfill: existing rows get `target_type='power'`, `target_low=target_high
   = old target_pct`, then drop the old column.
3. API layer: update request/response mapping for `WorkoutStep` per the
   `openapi.yaml` schema; implement nested-`children` ⇄ flat-`parent_step_id`
   translation (see note in section 2).
4. Frontend: build the Build-mode screen (step tree editor, drawer, chart,
   templates, export) per section 3; reuse existing Analyze-mode code as-is.
5. Export generation: implement recursive `.zwo`/`.tcx` writers that unroll or
   flatten nested repeat groups as described above.
6. Types/ORM: regenerate from the updated `openapi.yaml` / entity definitions.
