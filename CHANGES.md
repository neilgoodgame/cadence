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
