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

### 2.1 Full schema

Introspected directly from a running instance (26 domain tables, every
column and foreign key), not reconstructed from the ORM model files —
this is what's actually on disk today. Entity names below are the logical/
domain names used throughout this doc; **physical table names differ per
backend** (Django prefixes with the app label — `activities_activity`,
`accounts_user`, `gear_shoemodelversion`, etc. — while the Java schema
uses bare singular snake_case — `activity`, `users`, `shoe_model_version`).
Same columns, same relationships, same constraints; different names on
disk. Each backend also owns its own **framework-managed auth-storage
tables**, intentionally excluded below since they're not app domain data:
Django's `oauth2_provider_*` (access/refresh tokens, grants, applications)
plus Django's own `auth_*`/`django_*` tables, versus Java's single
`oauth_authorization` table (Spring's OAuth2 Authorization Server schema)
plus Spring Batch's `batch_*` job-tracking tables and Flyway's
`flyway_schema_history`.

```mermaid
erDiagram
    User {
        string id PK
        string email UK
        string password
        string name
        string handle
        int age
        float weight_kg
        int ftp
        int critical_run_power
        string threshold_pace
        int lthr
        int max_hr
        bool is_coach
        bool is_active
        datetime date_joined
        int best_effort_top_n
    }
    UserRelationship {
        string id PK
        string owner_id FK
        string grantee_id FK
        string role
        string status
        datetime created
    }
    PersonalAccessToken {
        string id PK
        string user_id FK
        string name
        string prefix
        string hashed_secret
        jsonb scopes
        datetime created
        date expires_at
        date last_used
    }
    Activity {
        string id PK
        string athlete_id FK
        string sport
        string environment
        bool has_gps
        string name
        datetime start_date
        string source
        int moving_time
        float distance_km
        string distance_source
        int avg_power
        int norm_power
        float intensity
        int tss
        int avg_hr
        int max_hr
        int ascent
        float start_weight_kg
        float end_weight_kg
        int fluids_ml
        string workout_id FK
        string bike_id FK
        string shoe_id FK
        float avg_air_temp
        int avg_humidity
        float aerobic_training_effect
        float anaerobic_training_effect
        string training_effect_label
        string parent_activity_id FK
        string primary_activity_id FK
        string device
    }
    Lap {
        bigint id PK
        string activity_id FK
        int lap_index
        int duration
        float distance_km
        int avg_hr
        int avg_power
    }
    Record {
        string activity_id "PK, FK"
        datetime ts PK
        int t
        int power
        int heartrate
        int cadence
        float altitude
        float lat
        float lng
        float speed
        float distance_km
        float air_temp
        int humidity
        float core_temp
        float skin_temp
        float heat_strain
    }
    DurationCurve {
        bigint id PK
        string activity_id FK
        string metric
        int extends_to
        jsonb points
    }
    BestEffort {
        bigint id PK
        string athlete_id FK
        string kind
        string window_label
        float value
        string unit
        date date
        string activity_id FK
    }
    Tag {
        string id PK
        string athlete_id FK
        string name
        string origin
        string color
    }
    ActivityTag {
        bigint id PK
        string activity_id FK
        string tag_id FK
    }
    ZoneSet {
        bigint id PK
        string athlete_id FK
        string type
        jsonb zones
    }
    Workout {
        string id PK
        string created_by FK
        string name
        string sport
        string type
        int duration
        int tss
        string folder_id FK
        jsonb tags
        jsonb chart_preview
        datetime created_at
        datetime updated_at
    }
    WorkoutFolder {
        string id PK
        string created_by FK
        string name
    }
    WorkoutStep {
        bigint id PK
        string workout_id FK
        int step_order
        string kind
        string end_type
        int duration
        int distance
        int repeat
        string target_type
        float target_low
        float target_high
        string target2_type
        float target2_low
        float target2_high
        text note
        bigint parent_step_id FK
    }
    ScheduledWorkout {
        string id PK
        string workout_id FK
        string athlete_id FK
        string assigned_by FK
        date date
        string time_of_day
        string status
        string activity_id FK
    }
    Bike {
        string id PK
        string athlete_id FK
        string name
        string kind
        string groupset
        int distance_km
        float hours
        int rides
    }
    Component {
        string id PK
        string bike_id FK
        string name
        int km
        int limit_km
        string model
    }
    ServiceRecord {
        string id PK
        string component_id FK
        string action
        bool reset
        string note
        date date
    }
    Shoe {
        string id PK
        string athlete_id FK
        string shoe_model_version_id FK
        string colourway
        string name
        string image
        string role
        int km
        int limit_km
        date since
        bool retired
    }
    ShoeModel {
        string id PK
        string manufacturer
        string model
        string created_by FK
    }
    ShoeModelVersion {
        string id PK
        string shoe_model_id FK
        string version
    }
    Upload {
        string id PK
        string athlete_id FK
        string batch_id FK
        string filename
        string file_hash
        string stored_path
        string status
        float progress
        string activity_id FK
        string error_code
        string error_message
        float weight_before_kg
        float weight_after_kg
        int fluids_ml
        string shoe_id FK
        datetime received_at
        datetime completed_at
    }
    UploadBatch {
        string id PK
        string athlete_id FK
        string filename
        string on_duplicate
        string status
        datetime received_at
        datetime completed_at
        string error_code
        string error_message
    }
    Webhook {
        string id PK
        string owner_id FK
        string url
        string status
        jsonb events
        string secret
        datetime created
    }
    WebhookDelivery {
        bigint id PK
        string webhook_id FK
        string event
        jsonb payload
        string status
        int attempts
        text last_error
        datetime created
    }
    Race {
        string id PK
        string athlete_id FK
        string name
        date date
        string sport
        float distance_km
        int goal_time
        int result_time
        string activity_id FK
        string url
        string results_url
        string notes
    }

    User ||--o{ Activity : "athlete_id"
    Activity ||--o{ Activity : "parent_activity_id"
    Activity ||--o{ Activity : "primary_activity_id"
    Workout ||--o{ Activity : "workout_id"
    Bike ||--o{ Activity : "bike_id"
    Shoe ||--o{ Activity : "shoe_id"
    Activity ||--o{ ActivityTag : "activity_id"
    Tag ||--o{ ActivityTag : "tag_id"
    Activity ||--o{ BestEffort : "activity_id"
    User ||--o{ BestEffort : "athlete_id"
    User ||--o{ Bike : "athlete_id"
    Bike ||--o{ Component : "bike_id"
    Component ||--o{ ServiceRecord : "component_id"
    Activity ||--o{ DurationCurve : "activity_id"
    Activity ||--o{ Lap : "activity_id"
    Activity ||--o{ Record : "activity_id"
    User ||--o{ PersonalAccessToken : "user_id"
    Activity ||--o{ Race : "activity_id"
    User ||--o{ Race : "athlete_id"
    Workout ||--o{ ScheduledWorkout : "workout_id"
    User ||--o{ ScheduledWorkout : "athlete_id"
    User ||--o{ ScheduledWorkout : "assigned_by"
    Activity ||--o{ ScheduledWorkout : "activity_id"
    User ||--o{ Shoe : "athlete_id"
    ShoeModelVersion ||--o{ Shoe : "shoe_model_version_id"
    User ||--o{ ShoeModel : "created_by"
    ShoeModel ||--o{ ShoeModelVersion : "shoe_model_id"
    User ||--o{ Tag : "athlete_id"
    User ||--o{ Upload : "athlete_id"
    UploadBatch ||--o{ Upload : "batch_id"
    Activity ||--o{ Upload : "activity_id"
    Shoe ||--o{ Upload : "shoe_id"
    User ||--o{ UploadBatch : "athlete_id"
    User ||--o{ UserRelationship : "owner_id"
    User ||--o{ UserRelationship : "grantee_id"
    User ||--o{ Webhook : "owner_id"
    Webhook ||--o{ WebhookDelivery : "webhook_id"
    User ||--o{ Workout : "created_by"
    WorkoutFolder ||--o{ Workout : "folder_id"
    User ||--o{ WorkoutFolder : "created_by"
    Workout ||--o{ WorkoutStep : "workout_id"
    WorkoutStep ||--o{ WorkoutStep : "parent_step_id"
    User ||--o{ ZoneSet : "athlete_id"
```

A few things in this schema that aren't self-explanatory from the diagram
alone:

- **`Record` has no surrogate `id`** — its primary key is the composite
  `(activity_id, ts)`, and it's the one hypertable in the system
  (partitioned on `ts`, 1-day chunks — 1,444 chunk tables in the local dev
  DB alone). Every other table is a normal (non-partitioned) Postgres
  table. See `AWS_MIGRATION_PLAN.md` §4 for why this table specifically
  drove the TimescaleDB choice.
- **`Activity` has two different self-referential FKs, and they're
  mutually exclusive by application-level validation** (not a DB
  constraint): `parent_activity_id` links a multisport/transition leg to
  its parent session (`sport in ("multisport", "transition")`);
  `primary_activity_id` links a duplicate upload to the activity it's a
  duplicate of. The API explicitly rejects linking a multisport session as
  a duplicate, and rejects a duplicate-of-a-duplicate chain (`views.py`'s
  merge-duplicate validation) — the schema itself would happily allow
  either, so this is enforced in application code, not by a check
  constraint.
- **`WorkoutStep.parent_step_id`** is how `repeat` groups nest — a step
  with `kind="repeat"` is a container whose children point back at it via
  this column; the tree is walked in application code (§6), not via a
  recursive SQL query anywhere in either backend.
- **`Shoe` is two hops from its catalog data**: `Shoe` (an athlete's owned
  pair) → `ShoeModelVersion` (a specific version/colourway) →
  `ShoeModel` (manufacturer + model name) — mileage/retirement tracking
  lives on `Shoe` itself, the catalog tables are just reference data
  shared across athletes.
- **Nullable FKs are the norm, not the exception**, here — `workout_id`,
  `bike_id`, `shoe_id` on `Activity`; `assigned_by`, `activity_id` on
  `ScheduledWorkout`; `batch_id`, `activity_id`, `shoe_id` on `Upload`; etc.
  are all optional links, reflecting that most of this data starts
  disconnected (an uploaded activity has no matched workout until
  auto-matching or a manual link happens) rather than being created
  pre-linked.

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
