# Changelog — environmental & physiological stream data

These changes extend the activity data model. Apply as an incremental migration +
API/type update. Source of truth: `openapi.yaml` and `Data Dictionary.dc.html`.

## 1. `record` (1 Hz stream) — new optional channels
Add nullable columns to the per-second record/stream table:
- `air_temp` — numeric, °C — from a **Stryd** footpod (runs)
- `humidity` — int, % RH — from a **Stryd** footpod (runs)
- `skin_temp` — numeric, °C — from a **CORE** sensor
(`core_temp` and `heat_strain` already existed; they are also CORE-sourced.)

All nullable; present only when the corresponding device supplied data.

## 2. `activity` — new computed fields (read-mostly)
- `avg_air_temp` — numeric(4,1), °C, nullable
- `avg_humidity` — int, % RH, nullable

Semantics:
- For **runs with Stryd stream data**: computed as the mean of the stream channels,
  **read-only** (recompute when streams change; ignore client writes).
- For **all other activities** (rides, or runs without Stryd): may be **set manually**
  via `PATCH /v1/activities/{id}`.
- Null when neither computed nor set.

## 3. API (`openapi.yaml`)
- `GET /v1/activities/{id}/streams` — response may include `air_temp`, `humidity`,
  `core_temp`, `skin_temp` channels (see `Streams` schema example).
- `Activity` schema — adds read fields `avg_air_temp`, `avg_humidity`.
- `PATCH /v1/activities/{id}` — adds writable `avg_air_temp`, `avg_humidity`
  (ignored for runs that already have computed Stryd values).

## Suggested implementation steps for Claude Code
1. DB migration: add the five nullable columns above (`record`: air_temp, humidity,
   skin_temp; `activity`: avg_air_temp, avg_humidity).
2. Ingest: when parsing a `.fit`, map Stryd env channels → record.air_temp/humidity,
   CORE channels → record.core_temp/skin_temp/heat_strain. Stryd/CORE data is
   FIT-only — Garmin Connect's TCX export doesn't carry these third-party developer
   fields (no documented extension schema for them), and GPX has no equivalent at
   all, so `.gpx`/`.tcx` uploads simply never populate these channels.
3. Derivation: on activity finalize, if run + Stryd channels present, compute
   activity.avg_air_temp / avg_humidity from the stream; else leave for manual entry.
4. API: expose the read fields; allow manual write on PATCH only when not computed.
5. Types/ORM: regenerate from the updated `openapi.yaml` / entity definitions.

## Status
Implemented in both `backend/` and `backend_java/` (FIT ingestion, derivation, API,
and PATCH semantics). GPX/TCX intentionally do not populate these fields — see step 2.

---

# Changelog — Activity Analysis: fields with no backing data

Found building the frontend's Activity Analysis screen (the design prototype's hero screen):
several fields it shows have no real source anywhere in `openapi.yaml` or either backend. Apply
as an incremental migration + API/type update, same as the change above. Source of truth:
`openapi.yaml` and `Data Dictionary.dc.html`.

## 1. L/R power balance
- `record` (1 Hz stream) — new nullable `left_right_balance` column, numeric (% left), same
  shape as the existing CORE/Stryd channels. FIT files commonly carry this
  (`left_right_balance` field on `record` messages); neither parser currently reads it.
- `activity` — new nullable `avg_lr_balance` computed field, same derive-on-finalize pattern as
  `avg_air_temp`/`avg_humidity`.

## 2. TRIMP
- `activity` — new nullable `trimp` computed field, same derivation-on-finalize pattern as `tss`/
  `norm_power`. Needs `resting_hr` (see #4) for a proper HR-reserve-based formula; without it,
  fall back to a simpler avg-HR-duration formula and revisit once #4 lands.

## 3. Training Effect (aerobic / anaerobic)
~~Flagged, not scoped: assumed this was Garmin's proprietary EPOC-based model, needing real
design work to approximate.~~ Turned out not to need any modeling: Garmin's watch (via
Firstbeat) already computes it and writes the result directly into the FIT session message
(`total_training_effect`/`total_anaerobic_training_effect`) — a precomputed field read, same
shape as #1, not a derivation like #2. **Implemented** — see the "Garmin training effect"
changelog below.

## 4. `resting_hr` on `User`
New nullable integer field, identical shape to the existing `lthr`/`max_hr` fields
(`openapi.yaml` `User` schema, ~line 1983). Needed for HR Reserve % display and a better #2.

## Suggested implementation steps for Claude Code
1. DB migration: add `record.left_right_balance` (nullable numeric), `activity.avg_lr_balance`
   (nullable numeric), `activity.trimp` (nullable numeric), `user.resting_hr` (nullable integer).
2. Ingest: map the FIT `left_right_balance` record field the same way `power`/`heart_rate` are
   already mapped; existing GPX/TCX files won't have it (leave null).
3. Derivation: on activity finalize, compute `avg_lr_balance` from the stream when present;
   compute `trimp` from avg HR + duration (+ `resting_hr` when set).
4. API: expose `avg_lr_balance`/`trimp` as read-only `Activity` fields; expose `resting_hr` as a
   normal writable `User`/`PATCH /v1/athletes/{id}` field, mirroring `lthr`.
5. Types/ORM: regenerate from the updated `openapi.yaml` / entity definitions.
6. ~~Training Effect: design first, implement later - not part of this migration.~~ Done
   separately — see the "Garmin training effect" changelog below; turned out to be a
   precomputed-field read, not a design task.

---

# Changelog — Comments on an activity

A new resource, not a field addition - flagging separately since it needs its own design pass
(permissions: who can comment - athlete + their coaches only? visibility to viewers?), not just
a migration.

- New `Comment` entity: `id`, `activity_id`, `author_id`, `author_role` (athlete | coach),
  `body`, `created`.
- New endpoints: `GET /v1/activities/{id}/comments`, `POST /v1/activities/{id}/comments`.
- Permission model to decide before implementing: presumably anyone who can read the activity
  (owner, or a Viewer/Coach share) can read comments, but only the owner and their Coaches can
  post - confirm against how `PermissionService`/`mayRead`/`mayWrite` already model
  owner/Viewer/Coach access elsewhere before inventing a new rule.

---

# Changelog — Gear: retired shoes can't be listed, Shoe.role can't be set

Found building the frontend's Gear screen. Two small, unrelated gaps in the same area -
listed together since both are quick API additions, not new resources.

## 1. No way to list retired shoes
`GET /v1/gear/shoes` filters to `retired=False` server-side (confirmed in the Python
view), and there's no separate endpoint or query param for the retired ones. Retiring a
pair (`PATCH /v1/gear/shoes/{id}` with `retired: true`) makes it permanently unlistable
through the documented API even though the row still exists - the design prototype's
"Retired shoes →" link has nothing to call.

Suggested fix: add an optional `retired` query param to `GET /v1/gear/shoes`
(`true`/`false`/omitted-for-active-only, matching today's default) on both backends.

## 2. `Shoe.role` has no field to set it
`Shoe.role` is in the response schema (e.g. "Race & threshold") but neither
`ShoeCreate` (`POST /v1/gear/shoes`) nor `ShoeUpdate` (`PATCH /v1/gear/shoes/{id}`)
accepts a `role` field - confirmed against `openapi.yaml`. Every shoe gets `role: ""`
forever via the documented API; there's no way to populate the value the response
schema already promises.

Suggested fix: add `role` (optional string) to both `ShoeCreate` and `ShoeUpdate` on
both backends.

## Suggested implementation steps for Claude Code
1. API (`openapi.yaml`): add the `retired` query param to `GET /v1/gear/shoes`; add
   `role` to `ShoeCreate`/`ShoeUpdate`.
2. Python/Java: thread the `retired` filter through the shoes list view/controller; add
   `role` to the create/update serializers/DTOs (it's already a plain stored field on the
   model on both backends, no migration needed - just wiring up the existing column).
3. Frontend: once available, wire a "Retired shoes" view into the existing UI link, and
   add a `role` input to the add/edit shoe form.

---

# Changelog — Garmin training effect

New `activity` fields, sourced from a FIT file's `session` message (standard FIT
fields, not developer fields — FIT-only, no GPX/TCX equivalent):
- `aerobic_training_effect` — numeric(2,1), 0.0–5.0, nullable
- `anaerobic_training_effect` — numeric(2,1), 0.0–5.0, nullable
- `training_effect_label` — text, derived from `aerobic_training_effect` per
  Garmin's documented benefit scale (0.0–0.9 No Benefit, 1.0–1.9 Minor Benefit,
  2.0–2.9 Maintaining, 3.0–3.9 Improving, 4.0–4.9 Highly Improving, 5.0
  Overreaching). Empty string when `aerobic_training_effect` is null.

All three are device-computed and read-only — never writable via
`PATCH /v1/activities/{id}`, unlike `avg_air_temp`/`avg_humidity` above.

## Status
Implemented in both `backend/` and `backend_java/` (FIT session-message extraction,
label derivation, API). Modeled after the equivalent extraction in
[fit-analyser](https://github.com/neilgoodgame/fit-analyser)'s `get_session_meta()` —
that project's reverse-engineered "primary benefit" enum (undocumented FIT field 188)
was deliberately left out here since it has no accessor in Garmin's own FIT SDK and
no verifiable spec; `training_effect_label`'s value-based fallback covers the same
need without relying on an unofficial field.
