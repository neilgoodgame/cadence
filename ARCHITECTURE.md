# Architecture

This documents how Cadence is actually built today — not the intended
target state (see the design `.dc.html` files and `README.md` for that),
and not where it's going in AWS (see `AWS_MIGRATION_PLAN.md` for that). It's
the reference for "how does this system actually work right now," grounded
directly in the code.

## 1. Shape of the system

```mermaid
flowchart TB
    subgraph Client
        FE["Frontend SPA<br/>React 19 + TS + Vite"]
    end

    subgraph "Backend (pick one — parity-maintained pair)"
        PY["Python backend<br/>Django 6 + DRF :8000"]
        JV["Java backend<br/>Spring Boot 4 :8080"]
        CW["Celery worker<br/>(Python only)"]
    end

    DB[("TimescaleDB<br/>(Postgres 16 + extension)<br/>one instance per backend, locally")]
    RD[("Redis<br/>(Python only — Celery broker)")]
    MEDIA[("Local filesystem volume<br/>uploaded FIT/GPX/TCX + JWT keypair")]
    WEBHOOK["Third-party webhook endpoints"]

    FE -->|"JSON over HTTPS<br/>OAuth2 + scoped RS256 JWT"| PY
    FE -->|"same contract, openapi.yaml"| JV
    PY --> DB
    JV --> DB
    PY -->|"broker"| RD
    CW -->|"consumes queue"| RD
    CW --> DB
    CW -->|"reads uploaded file"| MEDIA
    JV -->|"in-process Spring Batch<br/>(no queue, no Redis)"| DB
    PY --> MEDIA
    JV --> MEDIA
    CW -->|"signed HMAC POST, retried"| WEBHOOK
```

Two backend implementations are maintained in **strict feature/schema
parity** against a single shared contract (`openapi.yaml`, 71 operations;
drift tracked in `SCHEMA_COMPARISON.md`). They are not a primary/fallback
pair — either can serve the same frontend unmodified, pointed at by
`VITE_API_BASE_URL`. New features land Python-first in practice, then get
ported to Java (observable from git history), but both are expected to be
fully working REST APIs against the same data model.

## 2. Domain model

Both backends implement the same entities (Python: Django ORM models,
one `models.py` per app; Java: JPA `@Entity` classes under
`com.cadence.api.*`). 27 Django migrations / 31 Flyway migrations — the
counts differ because Flyway's migration granularity doesn't map 1:1 to
Django's, not because the schemas have diverged.

| Domain | Entities | Notes |
|---|---|---|
| **Identity & access** | `User`, `UserRelationship`, `PersonalAccessToken` | `UserRelationship` (owner → grantee, `role: viewer\|coach`, `status: pending\|active`) is the single mechanism behind both "share my data with a viewer" and "let this coach manage me" — same table, no separate coaching-specific schema. |
| **Activities** | `Activity`, `Lap`, `Record` (1Hz stream, TimescaleDB hypertable), `DurationCurve`, `BestEffort`, `Tag`/`ActivityTag` | The one place a Timescale-specific feature (hypertable partitioning on `ts`) is actually used — see `AWS_MIGRATION_PLAN.md` §4. |
| **Athletes** | `ZoneSet` | HR/power/pace zone definitions, computed from profile thresholds (FTP, LTHR, threshold pace, etc.). |
| **Workouts** | `Workout`, `WorkoutFolder`, `WorkoutStep` | `WorkoutStep` is a tree: leaf steps carry `target_low`/`target_high` as ramp *start*/*end* values (not min/max), plus `repeat` groups that nest. |
| **Scheduling** | `ScheduledWorkout` | Links a `Workout` to a calendar date/assignee; resolves to `activity_id` once the planned session is actually completed (auto-matched via lap-structure inference, or manually). |
| **Gear** | `Bike`, `Component`, `ServiceRecord`, `Shoe`, `ShoeModel`, `ShoeModelVersion` | Shoe catalog is two-tier (`ShoeModel` → `ShoeModelVersion`) so mileage tracking can be per-specific-version while the catalog picker browses by model. |
| **Uploads** | `Upload`, `UploadBatch` | Tracks async ingestion job status (`queued\|processing\|ready\|failed\|duplicate\|skipped`); `stored_path` points at the raw file on `default_storage`. |
| **Webhooks** | `Webhook`, `WebhookDelivery` | Outbound-only today — no inbound webhook receivers from Garmin/Strava; import is user-initiated file upload, not a push integration. |
| **Races** | `Race` | Goal races with target time, surfaced on the dashboard's "next race" card. |

**ID scheme**: every API-addressable resource uses a Stripe-style prefixed
random ID (`act_`, `wkt_`, `usr_`, `bik_`, etc. — `PrefixedIDModel` in
Python's `core/models.py`, the equivalent `common/id` package in Java), a
14-character random suffix over `[a-z0-9]`. Entities that are never fetched
directly by ID (`Record`, `Lap`, `BestEffort`, `DurationCurve`,
`WorkoutStep`, zone rows) use a plain auto-increment integer PK instead —
deliberately not every table pays for a prefixed ID.

## 3. Authentication & authorization

Three credential types are accepted on the same API surface, distinguished
by prefix/shape rather than a separate endpoint per type
(`core/authentication.py` in Python; the equivalent chain in Java's
`security/SecurityConfig.java`):

1. **OAuth2 access tokens** (`django-oauth-toolkit` / Spring's OAuth2
   Authorization Server) — opaque `cad_at_...` strings, minted via
   `POST /oauth/token`. Used by the first-party frontend.
2. **Cadence-issued delegated JWTs** — RS256, signed with a locally-managed
   RSA keypair (`JWT_PRIVATE_KEY_PATH`/`JWT_PUBLIC_KEY_PATH`), minted via
   `POST /v1/auth/jwt`. These carry `sub` (the signed-in principal) and,
   separately, `athlete_id` — letting a coach's token act *as* an athlete
   they've been granted access to without re-authenticating as that
   athlete. `core/auth_context.py`'s `get_effective_athlete_id()` is the
   single place that resolves "who is this request actually acting on
   behalf of," and every view that touches athlete-scoped data calls
   through it rather than trusting `request.user` directly.
3. **Personal access tokens** — opaque `cad_pat_...` strings (own prefix,
   own `PersonalAccessTokenAuthentication` class), created/rotated/revoked
   from Preferences → API tokens, hashed at rest (`hmac.compare_digest`
   against a stored hash, not the raw secret), scoped and optionally
   expiring, `last_used` updated at most once/day per token (avoids a
   write on every single API call).

**Authorization** is a flat two-function model on top of that
(`core/permissions.py`): `user_may_read(sub, athlete_id)` — true if
`sub == athlete_id` or an **active** `UserRelationship` grants it — and
`user_may_write`, which additionally requires `role == coach` (a `viewer`
relationship is read-only). Every athlete-scoped view checks one of these
explicitly; there's no ORM-level row-security layer doing this implicitly,
so a missing check is a real, visible bug class rather than something a
framework silently prevents — worth keeping in mind when adding new
endpoints.

JWKS is served for the RS256 public key so token verification doesn't
require sharing the private key; `JWT_KID` exists specifically to support
overlapping keys during a future rotation without a schema change (see
`AWS_MIGRATION_PLAN.md` §6).

## 4. Query language (CQL)

`/v1/activities` supports a JQL/CQL-style search string (`runs tagged race
and distance > 10km ordered by tss`) — this is a **hand-rolled parser
implemented independently in both backends**, not a third-party library:
Python's `core/cql/` (tokenizer → parser → compiler-to-ORM-queryset, ~700
lines) and Java's `com.cadence.api.cql` (tokenizer → parser → grammar →
field registry, compiling to a JPA `Specification`). Because it's
hand-written twice, it's one of the highest-risk places for the two
backends to silently diverge in accepted grammar or field semantics —
worth prioritizing in any future parity audit, and a natural candidate for
a shared cross-backend test fixture (a list of query strings + expected
matches, run against both).

## 5. Upload / ingestion pipeline

Both backends accept the same inputs (single file or bulk `.zip`, `.fit` /
`.gpx` / `.tcx`) through the same async job shape (`Upload`/`UploadBatch`
rows, client polls with `Retry-After` hints), but process them completely
differently under the hood:

- **Python**: `POST` writes the file to `default_storage` and creates an
  `Upload` row with `status=queued`, then hands off to Celery
  (`uploads/tasks.py::process_upload`, `@shared_task(bind=True,
  max_retries=3)`). The worker calls `ingest_upload()`
  (`uploads/processing.py`), which dispatches to a format-specific parser
  (`fitparse`, `gpxpy`, or `lxml`-based TCX parsing under
  `uploads/parsers/`), then computes laps/records/duration-curves/best-
  efforts and fires an `activity.created` webhook event on success. A
  batch's individual failures don't fail the batch — Garmin account
  exports mix in metadata-only stub FITs with no real activity data, so
  those are marked `skipped` rather than `failed` (see the comment in
  `process_upload`), letting a large export finish instead of erroring out
  on the first stub file.
- **Java**: no queue, no worker process. `UploadIngestService` runs the
  equivalent parse-and-compute pipeline **in-process**, dispatched via
  Spring Batch (`UploadJobLauncher`/`BatchConfig`) and Java 24's virtual
  threads for concurrency, using the official `com.garmin:fit` SDK instead
  of a third-party FIT parser. This is a real architectural difference,
  not just an implementation detail — it means the Java backend has no
  Redis/broker dependency at all, at the cost of ingestion work competing
  for the same task's CPU/heap as live API traffic (relevant at the batch
  sizes this app already supports — up to 50,000 files/200MB per `.zip`,
  `MAX_BATCH_FILES`/`MAX_BATCH_BYTES` in `uploads/services.py`, mirrored in
  Java's `max-batch-files: 50000`).

**Best-effort storage is a top-N leaderboard, not full history**: `BestEffort`
rows are trimmed at write time to the athlete's global all-time top N
(default 10) per `kind`+`window_label` — anything outside the top N is
**deleted**, not hidden (`_trim_kind_window` in Python's
`uploads/processing.py`, `trimToTop` in Java's `BestEffortComputeService`).
Both have a detailed comment explaining the consequence: a "period" filter
on the Best Efforts screen can only ever show entries that are *both*
within the period *and* still an all-time top-N record — this was the root
cause of more than one "missing data" bug found during manual testing, and
is a deliberate, documented, revisit-later trade-off rather than an
oversight.

## 6. Workout subsystem

`WorkoutStep` is a tree — leaf steps (duration + a target: power/HR/pace/
open, `target_low`/`target_high` as ramp *start*/*end*, not min/max) and
`repeat` groups that can nest. Both backends consume/produce this same
tree shape for:

- **The Builder UI** (frontend `WorkoutDesignerScreen`) — hand-authored
  workouts, templates, nested repeat groups.
- **Export** — Zwift `.zwo` and Garmin `.tcx` generation from the tree.
- **Inference from a completed activity's laps** (`workouts/inference.py`
  in Python, `WorkoutInferenceService` in Java) — converts `Lap` rows into
  a leaf-step tree (target inferred from `avg_power`/`avg_hr` against the
  athlete's live zone reference) plus a greedy contiguous-repeat-block
  detector with fuzzy tolerance (duration ±~15%/10s, %FTP ±~5pts) that
  collapses repeated patterns into `repeat` groups, re-run to a fixed point
  so nested patterns (e.g. `2×[4×(work,rest), rest]`) fall out without a
  separate nesting code path. Read-only/idempotent (`GET
  /v1/activities/{id}/infer-workout`) — nothing is persisted until the
  athlete explicitly saves the inferred draft from the Builder.

## 7. Webhooks

Outbound only: an athlete/coach registers a `Webhook` (URL + subscribed
event list + secret), and `fire_event()` (`webhooks/events.py`) fans out a
`WebhookDelivery` to every active subscription whose owner can read the
affected athlete's data, then enqueues delivery. Delivery
(`webhooks/tasks.py::deliver_webhook`) POSTs a signed payload
(`X-Cadence-Signature`: HMAC-SHA256 over the raw body) with automatic
retry/backoff (`autoretry_for`, exponential, capped at 600s, 6 total
attempts). `fire_event()` deliberately swallows any exception from
enqueueing — a broken webhook subscriber must never be able to fail the
upload or scheduling action that triggered it.

## 8. Frontend

React 19 + TypeScript SPA (Vite, no server-side component — `npm run
build` produces a static `dist/`). Key structural choices:

- **Server state**: `@tanstack/react-query` throughout — no separate
  client-side store; cache keys double as the invalidation mechanism
  (e.g. `["activity", id]` shared between the activity detail screen and
  every `ActivityName` lookup elsewhere).
- **API layer** (`api/client.ts`): a single `apiFetch<T>()` wraps
  `fetch()`, injects the bearer token, and — on a `401` — calls a
  `refreshHandler` (registered by `AuthContext`) and retries once with the
  new token. `AuthContext` dedupes concurrent refresh calls behind an
  in-flight `Promise` (`useRef`), since two callers racing the same
  single-use/rotating refresh token would otherwise have the loser
  `logout()` the session — a real bug this pattern was added to fix, not
  speculative hardening.
- **Charts**: D3 (`d3-scale`/`d3-shape`/`d3-array`) for scale/shape math
  only, rendered as JSX `<path>`/`<rect>`/SVG — never `d3.select()`
  mutating DOM nodes React owns. Workout ramp visualization
  (`WorkoutChart.tsx`) renders `<polygon>` trapezoids per leaf step so
  ramps show actual slope, not flat blocks.
- **Maps**: `maplibre-gl` (vector tiles, Carto's free basemap styles, no
  API key) for the route map on the Activity Analysis screen; indoor
  activities (`has_gps=false`) get a distance-source panel instead.
- **Routing**: `react-router-dom`, one route per top-level screen
  (`/`, `/activities`, `/activities/:id`, `/best-efforts`, `/calendar`,
  `/gear`, `/import`, `/workouts`, `/preferences`), gated behind
  `RequireAuth`.
- **Theming**: three token-driven CSS-custom-property themes (Teal /
  Violet / Day), switched via a `data-theme` attribute, no per-component
  styling forks.

## 9. Testing

- **Python**: `pytest` with a `unit`/`integration` marker split
  (`pytest -m unit` needs no external services; `-m integration` needs
  Postgres) — mirrors how CI splits the two jobs. `factory-boy` for model
  fixtures. `workouts/test_roundtrip.py` round-trips both generated random
  workouts and real fixture files (`test_fixtures/roundtrip/*.json`)
  through the inference engine to catch heuristic regressions.
- **Java**: JUnit 5, with the same `unit`/`integration` split expressed as
  Gradle tasks (`unitTest` excludes `@Tag("integration")`; `integrationTest`
  boots a real Postgres via Testcontainers against the same
  `docker-compose`-provisioned Docker daemon used locally). Spring Batch's
  own test harness (`spring-batch-test`) exercises the ingestion job
  directly.
- **Frontend**: `vitest` (unit), `eslint` (including React-Compiler-aware
  rules — `react-hooks/set-state-in-effect`, `react-hooks/immutability` —
  that have caught real bugs during development, not just style nits),
  `tsc -b`, all required as separate CI steps, not bundled into one script.

## 10. CI

GitHub Actions, one workflow per concern (`.github/workflows/`):
`tests.yml` (Python unit + integration), `java-tests.yml` (Java unit +
integration), `lint.yml` (ruff check + format), `frontend.yml` (tsc, eslint,
vitest, build), `codeql.yml` (Python/Java/TS static analysis, scheduled
weekly plus every push/PR). Backend-specific workflows are **required
status checks on every PR regardless of whether that PR touches the
backend** — a step-level `dorny/paths-filter` no-ops the actual work on
unrelated PRs instead of using a workflow-level `paths:` trigger filter,
because GitHub leaves a `paths:`-gated required check permanently
"Expected — waiting for status to be reported" on PRs that don't touch the
matching files.

## 11. Local development

Two fully independent Docker Compose stacks (deliberately offset ports so
both can run simultaneously — Python on 8000/5432/6379, Java on
8080/5433):

- **Python** (`docker-compose.yml` + `docker-compose.dev.yml`): `db`
  (TimescaleDB), `redis`, `backend` (gunicorn in prod-shape / `runserver`
  in dev), `celery-worker`. `entrypoint.sh` generates the JWT keypair on
  first boot (idempotent — skips if the key file already exists), runs
  migrations, then writes a `.ready` sentinel file that `celery-worker`'s
  entrypoint blocks on before starting, so the worker never runs against
  an unmigrated schema.
- **Java** (`backend_java/docker-compose.yml`): `db` (TimescaleDB, own
  instance — `cadence_java`, not shared with Python), `backend` only — no
  worker service, consistent with §5's in-process ingestion model.

Both mount `jwt_keys` and `media` as named volumes, not bind mounts, in the
production-shape compose file; the dev override adds source bind-mounts
plus an anonymous volume over `.venv`/build output so the host's
(e.g. macOS) dependency install doesn't shadow the container's Linux one.

## 12. Cross-cutting things worth knowing before changing either backend

- **Parity is enforced by discipline, not tooling** — there's no automated
  contract test running the same request against both backends and diffing
  responses. `openapi.yaml` and `SCHEMA_COMPARISON.md` are the source of
  truth a human (or an agent) reconciles against; a schema or behavior
  change in one backend without the matching change in the other won't be
  caught by CI.
- **The CQL parser (§4) and the best-effort trimming logic (§5) are the
  two most likely places for silent behavioral drift** between backends —
  both are non-trivial hand-written logic implemented twice, not thin
  wrappers around a shared library.
- **`Record` (1Hz stream data) dominates storage** by a wide margin over
  every other table combined — see `AWS_MIGRATION_PLAN.md` §11.1 for
  measured numbers from real data (~1MB of stream data per activity on
  average).
- **Migrations run at container boot** in the current local setup
  (`entrypoint.sh` / Flyway-on-startup), which is fine for a single dev
  container but becomes a race condition the moment more than one task
  starts concurrently — already flagged as a pre-AWS-migration fix in
  `AWS_MIGRATION_PLAN.md` §8.3, not yet changed in the code.
