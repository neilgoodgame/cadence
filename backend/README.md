# Cadence Backend

Django + DRF REST API for Cadence, a multi-sport training analytics platform. It
ingests `.fit` / `.gpx` / `.tcx` activity files, derives power/HR metrics (normalized
power, TSS, duration curves, best efforts), and exposes them over a JSON API with
OAuth2/JWT-based delegated auth (coaches acting on behalf of athletes). The full
contract is documented in [`../openapi.yaml`](../openapi.yaml) (OpenAPI 3.1) and
browsable at `/schema/docs/` once the server is running.

## Architecture

**One Django project (`config`), ten apps**, each owning a vertical slice of the
domain (models + serializers + views — there's no separate "MVC" split across apps):

| App | Owns | Key endpoints |
|---|---|---|
| `accounts` | `User`, `PersonalAccessToken`, `UserRelationship` (shares/coaching) | `/v1/auth/register`, `/v1/me`, `/v1/shares*`, `/v1/coach/athletes*`, `/v1/auth/tokens*` |
| `authn` | OAuth2 token generators, scoped-JWT minting, JWKS | `/oauth/token`, `/v1/auth/jwt`, `/.well-known/jwks.json` |
| `athletes` | `ZoneSet` (HR/power/pace zones) | `/v1/athletes/{id}*` |
| `activities` | `Activity`, `Lap`, `Record` (1Hz stream), `DurationCurve`, `Tag`, `BestEffort` | `/v1/activities*`, `/laps`, `/streams`, `/curves`, `/v1/tags` |
| `uploads` | `Upload`, `UploadBatch` + Celery ingestion pipeline | `POST /v1/activities`, `/v1/activities/batch`, `/v1/uploads/*` |
| `workouts` | `Workout`, `WorkoutStep` (structured workout designer) | `/v1/workouts*` |
| `scheduling` | `ScheduledWorkout` | `/v1/calendar`, `/v1/scheduled-workouts*` |
| `gear` | `Bike`, `Component`, `ServiceRecord`, `Shoe`, `ShoeModel*` | `/v1/gear/*` |
| `webhooks` | `Webhook`, `WebhookDelivery` (HMAC-signed event delivery + retry) | `/v1/webhooks*` |
| `core` | No models — shared scaffolding | CQL query parser, cursor pagination, error envelope, delegation permissions |

`activities` is intentionally the largest app — it's the hub entity everything else
references. `uploads` is split out because it has a different lifecycle (async job +
polling) and its own serializers, not because it's a different resource.

### Auth & delegation

Three credential types, tried in order by DRF (`config/settings.py:DEFAULT_AUTHENTICATION_CLASSES`):

1. **OAuth2** (`django-oauth-toolkit`) — `cad_at_*` / `cad_rt_*` tokens. Standard
   `authorization_code`/`refresh_token` grants at `/oauth/token`.
2. **Scoped JWT** (`PyJWT`, RS256) — short-lived tokens minted at `/v1/auth/jwt` by an
   already-authenticated principal, used to *delegate* a narrower slice of access
   (e.g. a coach minting a token scoped to one athlete).
3. **Personal access tokens** — `cad_pat_*`, created via `/v1/auth/tokens`, looked up by
   an indexed prefix and verified with SHA-256 (not bcrypt — these are high-entropy
   random tokens, not passwords).

Every request resolves to a `(sub, athlete_id)` pair (`core/auth_context.py`): `sub` is
who's actually authenticated; `athlete_id` is whose data is being acted on. For
OAuth2/PATs they're always equal. For JWTs they can differ — that's how a coach acts on
an athlete's behalf. `core/permissions.py` authorizes every read/write by checking
`sub == athlete_id`, or an active `UserRelationship` (any role for read, `coach` role
for write) from `athlete_id` to `sub`. Delegation failures are **403**, never 404.

### Async upload pipeline

`POST /v1/activities` hashes the uploaded file; a repeat upload for the same athlete
returns the existing activity (`409`) without doing any work. Otherwise it creates an
`Upload(status=queued)`, returns `202` + `Location`/`Retry-After`, and enqueues a Celery
task that: parses by extension (`uploads/parsers/{fit,gpx,tcx}.py`) → normalizes to
per-second samples + laps → computes normalized power / TSS / time-in-zone / duration
curves / best-effort upserts → bulk-inserts `Record`/`Lap` rows → creates the `Activity`
→ attempts workout-matching against that day's scheduled workout → fires an
`activity.created` webhook. Clients poll `GET /v1/uploads/{id}` until `status` leaves
`queued`/`processing`. `POST /v1/activities/batch` does the same per-file inside a `.zip`,
fanning out as a Celery `group` under one `UploadBatch`.

### TimescaleDB

`Record` (the 1Hz time-series table) is a Timescale **hypertable**, partitioned on a
`ts` column by 1-day chunks (`activities/migrations/0003_record_hypertable.py`). It's
otherwise a normal Django model — ingestion always uses
`Record.objects.bulk_create(batch_size=...)`, never row-at-a-time `.save()`.

### CQL (the `q` query language)

`GET /v1/activities?q=...` accepts a small natural-ish query language (`core/cql/`):
field comparisons, units (`140bpm`, `10km`), AND/OR, `ORDER BY` — e.g.
`runs tagged race and distance > 10km ordered by tss`. Hand-rolled
tokenizer → recursive-descent parser → AST → compiled to a Django `Q` object. Malformed
queries raise a uniform `400` via `core/exceptions.py`'s DRF exception handler.

## Requirements

- **Docker + Docker Compose** — the only hard requirement; everything runs in containers.
- **[uv](https://docs.astral.sh/uv/)** — only needed if you want to run Python natively
  on the host (faster iteration, IDE integration) instead of exclusively through Docker.
- Native dev additionally needs **Python 3.12** (pinned in `pyproject.toml`,
  `requires-python = ">=3.12,<3.13"`) — `uv` will fetch it for you if it's not already
  on your machine.

## Installation

From the repo root:

```bash
cp .env.example .env        # defaults work out of the box for local dev
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

This builds and starts four containers: `db` (TimescaleDB), `redis`, `backend` (Django
on `http://localhost:8000`), and `celery-worker`. On first boot the `backend` container
generates an RSA keypair for JWT signing (`keys/jwt_private.pem` / `jwt_public.pem`,
persisted in a named volume) and runs `manage.py migrate` before anything else starts —
`celery-worker` waits on that before it starts consuming tasks.

Confirm it's up:

```bash
curl http://localhost:8000/healthz
# {"status": "ok"}
```

Browse the API docs at `http://localhost:8000/schema/docs/` (Swagger UI) or the raw
OpenAPI 3.1 schema at `http://localhost:8000/schema/`.

### Native (non-Docker) development

Keep `db`/`redis` running via the dev compose file (so `localhost:5432`/`6379` are
reachable), then:

```bash
uv sync                              # installs deps into backend/.venv
uv run python manage.py migrate
uv run python manage.py runserver
```

## Running the tests

Every test is auto-tagged `unit` or `integration` (see `conftest.py`): a
`django.test.TestCase`/`TransactionTestCase` subclass (or anything using the `django_db`
fixture) needs a real Postgres connection and is `integration`; a `SimpleTestCase`
subclass or plain function with no DB access is `unit`.

Unit tests need no external services at all:

```bash
uv run pytest -m unit -q
```

Integration tests need a real Postgres/TimescaleDB connection (no SQLite fallback), so
bring up the `db` service first:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d db
```

Then, from `backend/`:

```bash
uv run pytest -m integration -q
```

Or run everything (`uv run pytest -q`) once `db` is up.

Test settings (`config/settings_test.py`) force `CELERY_TASK_ALWAYS_EAGER = True`, so
upload/webhook flows run synchronously in-process — no Redis or worker needed for tests.
A throwaway test database is created and destroyed automatically per run.

Stop `db` when you're done (non-destructive — data persists in its volume):

```bash
docker compose stop db
```

## Starting / stopping the services

All commands below run from the **repo root**.

**Local development** (live-reload, source bind-mounted, `db`/`redis` ports exposed to
the host):

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

**Production-like** (baked image, gunicorn, no bind mounts, no `db`/`redis` host ports):

```bash
docker compose up -d
```

Tail logs:

```bash
docker compose logs -f backend celery-worker
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

## Linting

[`ruff`](https://docs.astral.sh/ruff/) handles both linting and formatting
(`backend/pyproject.toml`'s `[tool.ruff]`):

```bash
uv run ruff check .      # lint
uv run ruff format .     # format
```

A pre-commit hook (`.pre-commit-config.yaml` at the repo root) runs both automatically
on every commit. One-time setup (needs the [`pre-commit`](https://pre-commit.com/)
CLI — `brew install pre-commit` or `pipx install pre-commit`):

```bash
pre-commit install
```

## Walkthrough: register, authenticate, upload a `.fit` file

There's no separate login endpoint — registering an account returns an access/refresh
token pair immediately (the toolkit's `authorization_code`/`refresh_token` grants at
`/oauth/token` are for a real OAuth client app; there's no frontend yet to drive that
redirect dance). All examples assume the dev stack is up at `http://localhost:8000`.

**1. Register** (this *is* the auth step — no separate login call needed):

```bash
curl -s -X POST http://localhost:8000/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name": "Ada Athlete", "email": "ada@example.com", "password": "correct-horse-battery"}'
```

```json
{
  "athlete": {"id": "usr_r0fie11v8275pu", "name": "Ada Athlete", "email": "ada@example.com", ...},
  "tokens": {
    "access_token": "cad_at_dkCWduZuyJztSRocJvvw9K5rXZPJTirJaGkgxF0R",
    "refresh_token": "cad_rt_OCpXNYZdWN0x1jyU9MVIJ18hUm2HjTUi2OVAfk0p",
    "token_type": "Bearer",
    "expires_in": 21599,
    "scope": "activities:read activities:write workouts:write calendar:write coach gear:write"
  }
}
```

Save the access token:

```bash
TOKEN="cad_at_dkCWduZuyJztSRocJvvw9K5rXZPJTirJaGkgxF0R"
```

**2. Upload an activity file.** `POST /v1/activities` takes `multipart/form-data` with
a `file` field. A few small real device `.fit` fixtures already live in the repo for
trying this out (`uploads/tests_fixtures/`):

```bash
curl -s -X POST http://localhost:8000/v1/activities \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@uploads/tests_fixtures/running_treadmill.fit" -i
```

```
HTTP/1.1 202 Accepted
Location: /v1/uploads/upl_js2o56gou1rl32
Retry-After: 2

{"id":"upl_js2o56gou1rl32","object":"upload","status":"queued","progress":0.0,
 "filename":"running_treadmill.fit","activity_id":null,"error":null, ...}
```

**3. Poll until processing finishes** (the Celery worker parses the file, computes
derived metrics, and creates the `Activity` in the background):

```bash
curl -s http://localhost:8000/v1/uploads/upl_js2o56gou1rl32 -H "Authorization: Bearer $TOKEN"
```

```json
{"id":"upl_js2o56gou1rl32","object":"upload","status":"ready","progress":1.0,
 "filename":"running_treadmill.fit","activity_id":"act_k9vgibr0ls5hlv","error":null, ...}
```

**4. Fetch the resulting activity:**

```bash
curl -s http://localhost:8000/v1/activities/act_k9vgibr0ls5hlv -H "Authorization: Bearer $TOKEN"
```

```json
{
  "id": "act_k9vgibr0ls5hlv",
  "sport": "run",
  "environment": "indoor",
  "name": "Run on 2026-04-05",
  "moving_time": 5299,
  "distance_km": 18.003,
  "avg_power": 249,
  "norm_power": 251,
  "avg_hr": 140,
  "max_hr": 185,
  "avg_air_temp": 20.8,
  "avg_humidity": 35,
  ...
}
```

From here, `GET /v1/activities/{id}/streams` returns the raw 1Hz samples,
`/laps` the lap splits, and `/curves` the power/HR duration curves — see
[`../openapi.yaml`](../openapi.yaml) or `/schema/docs/` for the full set of fields and
endpoints (laps, tags, best efforts, gear, workouts, scheduling, webhooks, sharing/coaching).

**Minting a long-lived API token.** The access token above expires in ~6 hours. For
scripts, create a personal access token instead (doesn't expire unless you ask it to,
shown once at creation):

```bash
curl -s -X POST http://localhost:8000/v1/auth/tokens \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "my-script", "scopes": ["activities:read", "activities:write"]}'
```

## Backing up Postgres data

Data persists in the `postgres_data` named volume across restarts and rebuilds. For a
portable dump (e.g. before a risky migration, or to move to another machine):

```bash
docker compose exec db pg_dump -U cadence cadence > backup.sql
```

Restore:

```bash
docker compose exec -T db psql -U cadence cadence < backup.sql
```

## Troubleshooting

- **`pytest` can't connect to Postgres** — make sure `db` is up
  (`docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d db`) and
  reachable on `localhost:5432` (the dev compose override is what exposes that port).
- **Uploads stay `queued` forever** — the `celery-worker` container isn't running, or
  `CELERY_TASK_ALWAYS_EAGER` is misconfigured. Check `docker compose logs celery-worker`.
- **401s on every request** — confirm the `Authorization: Bearer <token>` header is
  present and the token hasn't expired (`expires_in` from registration is in seconds).
- **403 instead of 404** — this is intentional: delegation failures (acting on an
  athlete you have no relationship with) return `403`, not `404`, to avoid leaking
  whether the resource exists.
