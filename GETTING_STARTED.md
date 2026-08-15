# Getting Started

Quick setup guide for running Cadence locally: clone the repo, start a backend, start
the frontend. For architecture/design details see [`README.md`](README.md) and
[`ARCHITECTURE.md`](ARCHITECTURE.md); for backend internals see
[`backend_java/README.md`](backend_java/README.md) and
[`backend/README.md`](backend/README.md).

## Two backends, one frontend

The repo ships **two independent, fully-featured backend implementations** of the same
REST API contract ([`openapi.yaml`](openapi.yaml)) — a Java/Spring Boot one
(`backend_java/`) and a Django/DRF one (`backend/`). They don't share a database and can
run side by side on different ports. Pick one to start with; this guide defaults to the
**Java backend** since it's the simpler one-command startup (no separate worker
container). Everything below applies equally to the Django backend with the path/port
swaps noted inline.

## Tools required

| Tool | Needed for | Notes |
|---|---|---|
| [Git](https://git-scm.com/) | Cloning the repo | — |
| [Docker + Docker Compose](https://docs.docker.com/get-docker/) | Running either backend | The only hard requirement — both backends run entirely in containers |
| [Node.js 22](https://nodejs.org/) + npm | Running the frontend | Matches CI (`.github/workflows/frontend.yml`) |
| Java 24 | *Optional* — native (non-Docker) Java backend dev | The Gradle wrapper provisions its own toolchain if you don't have this |
| Python 3.12 + [uv](https://docs.astral.sh/uv/) | *Optional* — native (non-Docker) Django backend dev | `uv` will fetch Python 3.12 for you if needed |

## 1. Clone the repo

```bash
git clone <repo-url> design_handoff_fitness_viewer
cd design_handoff_fitness_viewer
```

## 2. Start the backend

### Java (default)

```bash
cd backend_java
cp .env.example .env        # defaults work out of the box for local dev
docker compose up -d
```

This starts two containers: `db` (PostgreSQL 16, host port **5433**) and `backend` (Spring
Boot API on `http://localhost:8080`). First boot generates a JWT signing keypair and
runs Flyway migrations automatically.

Confirm it's up:

```bash
curl http://localhost:8080/healthz
# {"status":"ok"}
```

Swagger UI: `http://localhost:8080/schema/docs`.

### Django (alternative)

```bash
cp .env.example .env        # from the repo root
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

This starts four containers: `db`, `redis`, `backend` (Django on
`http://localhost:8000`), and `celery-worker`.

```bash
curl http://localhost:8000/healthz
# {"status": "ok"}
```

Swagger UI: `http://localhost:8000/schema/docs/`.

## 3. Start the frontend

```bash
cd frontend
npm install       # first time only
npm run dev
```

Vite prints the local URL in its startup output — usually `http://localhost:5173`
(it'll pick the next free port if that one's taken).

`frontend/.env` already points `VITE_API_BASE_URL` at `http://localhost:8080` (the Java
backend). To point it at the Django backend instead, change it to
`http://localhost:8000` and restart `npm run dev`.

## 4. Create an account

Open the frontend URL and use the sign-up form, or register via `curl` (this *is* the
auth step — no separate login call needed, and it returns a token pair immediately):

```bash
curl -s -X POST http://localhost:8080/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name": "Ada Athlete", "email": "ada@example.com", "password": "correct-horse-battery"}'
```

(Swap the port for `8000` if you're running the Django backend.)

## Running the tests

**Java** (from `backend_java/`):

```bash
./gradlew unitTest          # no external services needed
./gradlew integrationTest   # needs Docker (Testcontainers spins up its own Postgres)
./gradlew test              # everything
```

**Django** (from `backend/`, with `uv sync` run once first):

```bash
uv run pytest -m unit -q          # no external services needed
uv run pytest -m integration -q   # needs `db` up (see backend/README.md)
```

**Frontend** (from `frontend/`):

```bash
npx tsc -b       # typecheck
npm run lint     # eslint
npm test         # vitest
```

## Troubleshooting

- **Frontend loads but API calls fail** — check `VITE_API_BASE_URL` in `frontend/.env`
  matches the backend you actually started, and that backend's `/healthz` responds.
- **Port already in use** — Vite will automatically move to the next free port; check
  its startup output for the actual URL.
- **Uploads stay `queued` forever** — see the Troubleshooting section in
  [`backend_java/README.md`](backend_java/README.md) or
  [`backend/README.md`](backend/README.md) depending on which backend you're running.
