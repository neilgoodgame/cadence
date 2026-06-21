# Cadence Backend (Java)

A second, fully independent implementation of the Cadence REST API — same contract
(`../openapi.yaml`, 63 operations) and logical data model as `../backend` (Django/DRF),
but written idiomatically for Java/Spring rather than transliterated from it. It ingests
`.fit` / `.gpx` / `.tcx` activity files, derives power/HR metrics (normalized power,
TSS, duration curves, best efforts), and exposes them over a JSON API with OAuth2/JWT
delegated auth. Own Postgres/TimescaleDB instance, own containers — the two backends
don't share a database and can run side by side. Browsable at `/schema/docs` once the
server is running.

## Architecture

**Stack:** Java 24, Spring Boot 4.1 (Spring Framework 7, Jakarta EE 11), Spring Data JPA
+ Hibernate 7, Spring Security 7 (Authorization Server is bundled, not a separate
dependency), Spring Batch 6, Gradle (Kotlin DSL) wrapper, Flyway, PostgreSQL +
TimescaleDB, MapStruct, springdoc-openapi. No Lombok — entities are plain mutable
classes (Hibernate requires that), DTOs are records throughout.

**One Gradle module, package-per-feature** (`src/main/java/com/cadence/api/`), each
following entity → repository → service → controller, with a `dto` sub-package of
Bean-Validated records:

| Package | Owns | Key endpoints |
|---|---|---|
| `users` | `User` (the athlete profile — no separate Athlete table) | `/v1/auth/register`, `/v1/me` |
| `security` | OAuth2 token issuance, scoped-JWT minting, JWKS, personal access tokens | `/oauth/token`, `/v1/auth/jwt`, `/.well-known/jwks.json` |
| `tokens` | `PersonalAccessToken` CRUD | `/v1/auth/tokens*` |
| `sharing` | `UserRelationship` (shares + coaching, same table for both) | `/v1/shares*`, `/v1/coach/athletes*`, `/v1/me/contexts` |
| `athletes` | `ZoneSet` (HR/power/pace zones), fitness (CTL/ATL/TSB) + compliance | `/v1/athletes/{id}*`, `/zones`, `/fitness`, `/best-efforts` |
| `gear` | `Bike`, `Component`, `ServiceRecord`, `Shoe`, `ShoeModel*` catalog | `/v1/gear/bikes*`, `/components*`, `/shoes*`, `/shoe-catalog` |
| `workouts` | `Workout`, `WorkoutStep` (structured designer), match-listing | `/v1/workouts*` |
| `scheduling` | `ScheduledWorkout` | `/v1/calendar`, `/v1/scheduled-workouts*` |
| `activities` | `Activity`, `Lap`, `Record` (1 Hz stream), `DurationCurve`, `Tag`, `BestEffort` | `/v1/activities*`, `/laps`, `/streams`, `/curves`, `/v1/tags` |
| `uploads` | `Upload`, `UploadBatch` + the Spring Batch ingestion pipeline | `POST /v1/activities`, `/v1/activities/batch`, `/v1/uploads/*` |
| `webhooks` | `Webhook`, `WebhookDelivery` (HMAC-signed event delivery + retry) | `/v1/webhooks*` |
| `cql` | No models — the `q=` query-language compiler | (used by `activities`, not its own endpoints) |
| `common` | No models — errors, cursor pagination, ids, config | `/healthz` |

`activities` is intentionally the largest package — it's the hub entity everything else
references. `uploads` is split out because it has a different lifecycle (async job +
polling) and its own DTOs, not because it's a different resource.

### Auth & delegation

Three bearer-credential shapes share one filter chain via a custom
`AuthenticationManagerResolver` (`security/BearerSchemeAuthenticationManagerResolver.java`)
that dispatches on token shape:

1. **OAuth2** — `cad_at_*`/`cad_rt_*` tokens, `authorization_code`/`refresh_token` grants
   at `/oauth/token`. Backed by a custom JPA `OAuth2AuthorizationService`
   (`security/oauth/`), *not* Spring Authorization Server's default JDBC schema.
2. **Scoped JWT** — RS256, minted at `/v1/auth/jwt` by an already-authenticated
   principal, carrying a custom `athlete_id` delegation claim — how a coach acts on an
   athlete's behalf with a narrower slice of access (`security/jwt/`).
3. **Personal access tokens** — `cad_pat_*`, created via `/v1/auth/tokens`, looked up by
   an indexed prefix and verified with a constant-time hash compare (`security/pat/`).

All three converge on one `AuthContext` (`AuthContextFilter`), so every
controller/service reads `AuthContextHolder.get()` regardless of credential type.
`PermissionService` authorizes every read/write by checking `sub == athleteId`, or an
active `UserRelationship` (any role for read, `coach` role for write) from `athleteId`
to `sub`. Delegation failures are **403**, never 404.

### Async upload pipeline

`POST /v1/activities` hashes the uploaded file; a repeat upload for the same athlete
resolves to the existing activity (`409`, `status: duplicate`) without doing any work.
Otherwise it creates an `Upload(status=queued)`, returns `202` + `Location`/
`Retry-After`, and launches a Spring Batch job (`uploads/batch/`) off the request thread.
The job is a Tasklet per pipeline stage — parse (`uploads/parsing/`: the official Garmin
FIT SDK for binary files, namespace-agnostic DOM parsing for GPX/TCX) → bulk-insert
`Record` rows (the one genuinely chunk-oriented step) → derived stats (normalized power,
TSS) → duration curves → best-effort upserts → workout-matching against that day's
scheduled workout → finalize, firing an `activity.created` webhook. Clients poll
`GET /v1/uploads/{id}` until `status` leaves `queued`/`processing`.
`POST /v1/activities/batch` does the same per file inside a `.zip` under one
`UploadBatch`.

Job launches are serialized through a single dedicated virtual-thread worker
(`UploadJobLauncher`) rather than left to Spring Batch's own `TaskExecutor` concurrency —
`@JobScope`/`@StepScope` beans turned out not to be safely isolated across truly
concurrent `Job.execute()` calls on this Spring Batch version (verified directly:
concurrent uploads produced cross-contaminated `Record` rows before this fix). Batch
`.zip` uploads reuse the same per-file Job definition rather than Spring Batch
partitioning, since each file is independently retryable/failable — not what
partitioning is designed for.

### TimescaleDB

`Record` (the 1 Hz stream) is a Timescale **hypertable**, partitioned on a `ts` column
by 1-day chunks (`V10`/`V11` migrations). It uses a composite `(activity_id, ts)`
primary key instead of a surrogate id (`activities/RecordId.java`) — that pair is
already the natural unique key the schema needs, already includes the partitioning
column, and nothing ever fetches a `Record` by its own id anyway.

### CQL (the `q` query language)

`GET /v1/activities?q=...` accepts a small natural-ish query language (`cql/`): field
comparisons, units (`140bpm`, `10km`), AND/OR, `orderby` — e.g.
`runs tagged race and distance > 10km orderby tss`. Tokenizer → recursive-descent parser
→ a sealed `CqlNode` AST (`Cmp`/`And`/`Or` records) → a record-pattern `switch` that
compiles directly to a Spring Data `Specification<T>` (`cql/spec/`). Each resource
implements its own `FieldMap` (e.g. `activities/ActivityFieldMap`) to resolve CQL field
names to JPA properties and handle unit/enum coercion. Malformed queries raise a uniform
`400` via `common/error/ApiExceptionHandler`.

### Webhooks

The upload/scheduling pipelines publish lightweight domain events
(`webhooks/ActivityCreatedEvent`, etc.) via `ApplicationEventPublisher`; a
`@TransactionalEventListener(phase = AFTER_COMMIT)` bridges these into actual deliveries
— kept off the firing call sites entirely, and deferred to after-commit so a delivery
never races a not-yet-committed row. `WebhookDeliveryService` signs each payload
(HMAC-SHA256) and delivers via `@Async` + Spring Retry (6 attempts, backoff capped at
600s).

## Prerequisites

- Docker + Docker Compose
- Java 24 (only needed for native, non-Docker development — the Gradle wrapper will
  otherwise provision its own toolchain)

## Installation

From `backend_java/`:

```bash
cp .env.example .env        # defaults work out of the box for local dev
docker compose up -d
```

This builds and starts two containers: `db` (TimescaleDB, host port **5433** — offset
from the Python stack's 5432 so both can run at once) and `backend` (the Spring Boot API
on `http://localhost:8080` — offset from the Python stack's 8000). There's no separate
worker container: Spring Batch jobs and webhook retries run in-process on virtual
threads. On first boot the container generates an RSA keypair for JWT signing
(persisted in the `jwt_keys` named volume) and Flyway migrates automatically before
anything else starts.

Confirm it's up:

```bash
curl http://localhost:8080/healthz
# {"status":"ok"}
```

Browse the API docs at `http://localhost:8080/schema/docs` (Swagger UI) or the raw
OpenAPI 3.1 schema at `http://localhost:8080/schema`.

### Native (non-Docker) development

With the `db` service running (so `localhost:5433` is reachable):

```bash
./gradlew bootRun
```

Spring Boot devtools live-reload is enabled — recompiling (e.g. via your IDE) restarts
the context automatically.

## Running the tests

Pure-function calculators and the CQL tokenizer/parser/compiler get plain JUnit 5 unit
tests with no Spring context at all:

```bash
./gradlew test
```

## Starting / stopping the services

Tail logs:

```bash
docker compose logs -f backend
```

Stop containers but keep them (and all data) around for a quick restart:

```bash
docker compose stop
```

Stop and remove containers — data is **not** lost; `db` data, JWT keys, and uploaded
media live in named volumes independent of the containers:

```bash
docker compose down
```

Wipe persisted data too (Postgres data, JWT keys, uploaded media) — destructive, rarely
what you want:

```bash
docker compose down -v
```

## Walkthrough: register, authenticate, upload a `.fit` file

There's no separate login endpoint — registering an account returns an access/refresh
token pair immediately. All examples assume the stack is up at `http://localhost:8080`.

**1. Register** (this *is* the auth step — no separate login call needed):

```bash
curl -s -X POST http://localhost:8080/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name": "Ada Athlete", "email": "ada@example.com", "password": "correct-horse-battery"}'
```

```json
{
  "athlete": {"id": "usr_umzu1lkpmaujtu", "name": "Ada Athlete", "email": "ada@example.com", ...},
  "tokens": {
    "access_token": "cad_at_s8NTPzc5WHd42F96VdnLlfdaNBP0y7uLm5nC6h8E",
    "refresh_token": "cad_rt_v8clXNxb5kFHzSSRQjzUgqGPnsNnCLK9VCahKZC1",
    "token_type": "Bearer",
    "expires_in": 21600,
    "scope": "activities:read activities:write workouts:write calendar:write coach gear:write"
  }
}
```

Save the access token:

```bash
TOKEN="cad_at_s8NTPzc5WHd42F96VdnLlfdaNBP0y7uLm5nC6h8E"
```

**2. Upload an activity file.** `POST /v1/activities` takes `multipart/form-data` with
a `file` field. The Python backend's real device `.fit` fixtures work here too
(`../backend/uploads/tests_fixtures/`):

```bash
curl -s -X POST http://localhost:8080/v1/activities \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@../backend/uploads/tests_fixtures/running_treadmill.fit" -i
```

```
HTTP/1.1 202 Accepted
Location: /v1/uploads/upl_rhxr9aoxvvm2xs
Retry-After: 2

{"id":"upl_rhxr9aoxvvm2xs","status":"queued","progress":null,
 "filename":"running_treadmill.fit","activity_id":null,"error":null, ...}
```

**3. Poll until processing finishes** (the upload job parses the file, computes derived
metrics, and creates the `Activity` in the background — typically tens of milliseconds
for a file this size, but always poll rather than assume):

```bash
curl -s http://localhost:8080/v1/uploads/upl_rhxr9aoxvvm2xs -H "Authorization: Bearer $TOKEN"
```

```json
{"id":"upl_rhxr9aoxvvm2xs","status":"ready","progress":1.0,
 "filename":"running_treadmill.fit","activity_id":"act_2co80vn27ajfg8","error":null, ...}
```

**4. Fetch the resulting activity:**

```bash
curl -s http://localhost:8080/v1/activities/act_2co80vn27ajfg8 -H "Authorization: Bearer $TOKEN"
```

```json
{
  "id": "act_2co80vn27ajfg8",
  "sport": "run",
  "environment": "indoor",
  "has_gps": false,
  "name": "Run on 2026-04-05",
  "moving_time": 5299,
  "distance_km": 18.003,
  "distance_source": "trainer",
  "avg_hr": 140,
  "max_hr": 185,
  "ascent": null,
  ...
}
```

From here, `GET /v1/activities/{id}/streams` returns the raw 1 Hz samples, `/laps` the
lap splits, and `/curves` the power/HR duration curves — see
[`../openapi.yaml`](../openapi.yaml) or `/schema/docs` for the full set of fields and
endpoints (laps, tags, best efforts, gear, workouts, scheduling, webhooks,
sharing/coaching).

**Minting a long-lived API token.** The access token above expires in 6 hours. For
scripts, create a personal access token instead (doesn't expire unless you ask it to,
shown once at creation):

```bash
curl -s -X POST http://localhost:8080/v1/auth/tokens \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "my-script", "scopes": ["activities:read", "activities:write"]}'
```

## Backing up Postgres data

Data persists in the `postgres_data` named volume across restarts and rebuilds. For a
portable dump (e.g. before a risky migration, or to move to another machine):

```bash
docker compose exec db pg_dump -U cadence cadence_java > backup.sql
```

Restore:

```bash
docker compose exec -T db psql -U cadence cadence_java < backup.sql
```

## Troubleshooting

- **Uploads stay `queued` forever** — check `docker compose logs backend` for a job
  launch failure; there's no separate worker container to check (jobs run in-process on
  virtual threads), so any failure surfaces in the main backend log.
- **401s on every request** — confirm the `Authorization: Bearer <token>` header is
  present and the token hasn't expired (`expires_in` from registration is in seconds).
- **403 instead of 404** — this is intentional: delegation failures (acting on an
  athlete you have no relationship with) return `403`, not `404`, to avoid leaking
  whether the resource exists.
- **413 on a large upload** — `cadence.uploads.max-upload-bytes` (200 MB, in
  `application.yml`) and `spring.servlet.multipart.max-file-size`/`max-request-size`
  must stay in sync; the multipart layer rejects oversized requests before the
  application-level check ever runs, so raising one without the other has no effect.
