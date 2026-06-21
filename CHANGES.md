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
2. Ingest: when parsing a `.fit`/`.tcx`, map Stryd env channels → record.air_temp/humidity,
   CORE channels → record.core_temp/skin_temp/heat_strain.
3. Derivation: on activity finalize, if run + Stryd channels present, compute
   activity.avg_air_temp / avg_humidity from the stream; else leave for manual entry.
4. API: expose the read fields; allow manual write on PATCH only when not computed.
5. Types/ORM: regenerate from the updated `openapi.yaml` / entity definitions.

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
Flagged, not scoped: this is Garmin's proprietary EPOC-based model, not a small formula or a
missing field. Needs real design work (what model to approximate, what data it needs) before
it's an implementation task. Lower priority than #1/#2/#4.

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
6. Training Effect: design first, implement later - not part of this migration.

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
