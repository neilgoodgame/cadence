# Cadence Backend

Django + DRF REST API backed by TimescaleDB, with Celery/Redis for async upload
processing. See `../openapi.yaml` for the API contract.

## Prerequisites

- Docker + Docker Compose
- [uv](https://docs.astral.sh/uv/) (only needed for native, non-Docker development)

## First-time setup

From the repo root, create your local env file (only needed once):

```bash
cp .env.example .env
```

The defaults work out of the box for local development.

## Starting the service

All commands below are run from the **repo root** (where `docker-compose.yml` lives).

**Local development** (live-reload, source bind-mounted, `db`/`redis` ports exposed to
the host for `psql`/`redis-cli`/native `pytest`):

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

**Production-like** (baked image, gunicorn, no bind mounts, no `db`/`redis` host ports):

```bash
docker compose up -d
```

Either way, this brings up four containers: `db` (TimescaleDB), `redis`, `backend`
(the Django API on `http://localhost:8000`), and `celery-worker` (async upload/webhook
processing). On first boot, the backend container generates JWT signing keys and runs
`manage.py migrate` automatically before anything else starts.

Check it's up:

```bash
curl http://localhost:8000/healthz
# {"status": "ok"}
```

Tail logs:

```bash
docker compose logs -f backend celery-worker
```

## Stopping the service

Stop containers but keep them (and all data) around for a quick restart:

```bash
docker compose stop
```

Stop and remove the containers (data is **not** lost — `db` data, JWT keys, and
uploaded media live in named Docker volumes that persist independently of the
containers):

```bash
docker compose down
```

To also wipe persisted data (Postgres data, JWT keys, uploaded media) — destructive,
rarely what you want:

```bash
docker compose down -v
```

## Native (non-Docker) development

Dependencies are managed with `uv` (`pyproject.toml` / `uv.lock`). With the dev Compose
stack's `db`/`redis` running (so `localhost:5432`/`localhost:6379` are reachable):

```bash
uv sync                                    # install deps into backend/.venv
uv run python manage.py runserver
uv run python manage.py migrate
uv run pytest -q
```

## Backing up Postgres data

Data persists in the `postgres_data` named volume across restarts and container
rebuilds. For a portable dump (e.g. before a risky migration, or to move to another
machine):

```bash
docker compose exec db pg_dump -U cadence cadence > backup.sql
```

Restore:

```bash
docker compose exec -T db psql -U cadence cadence < backup.sql
```
