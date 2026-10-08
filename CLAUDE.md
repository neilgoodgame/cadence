# Repo Layout

Cadence is a training-log/analytics app: **two independent, parity-kept backend
implementations** of the same REST API contract, behind one React frontend.

- `backend/` — Django + DRF (Python). Not currently deployed anywhere.
- `backend_java/` — Spring Boot (Java). **This is the one actually deployed** —
  `infra/scripts/deploy-backend.sh` only builds and ships this image.
- `frontend/` — React + Vite + TypeScript. Talks to whichever backend
  `VITE_API_BASE_URL` points at.
- `infra/` — Terraform + deploy scripts (`infra/scripts/deploy-backend.sh`,
  `infra/scripts/deploy-frontend.sh`) for the AWS staging environment.
- `openapi.yaml` (repo root) — the **hand-maintained, canonical REST API
  contract** both backends implement. Each backend also serves its own
  auto-generated live schema at `/schema/docs` (Swagger UI) for introspection —
  `openapi.yaml` is the source of truth when the two disagree.
- `backend/CLAUDE.md`, `backend_java/CLAUDE.md`, `frontend/CLAUDE.md` hold
  stack-specific guidance (test/lint commands, conventions, known gotchas).
  `ARCHITECTURE.md`, `GETTING_STARTED.md`, `backend/README.md`,
  `backend_java/README.md` hold deeper setup/design detail.

## How the frontend talks to a backend

One env var: `VITE_API_BASE_URL` (`frontend/.env`), e.g. `http://localhost:8080`.
The two backends don't share a database and never talk to each other — the
frontend just points at whichever one is running. Every request is a
Bearer-token-authenticated JSON (or multipart) call through
`frontend/src/api/client.ts`'s `apiFetch`/`apiFetchStream`; there's no
server-rendering or proxy layer in between.

- **Local dev**: `frontend/.env` defaults to `http://localhost:8080` (Java).
  Swap to `http://localhost:8000` to point at Django instead.
- **Staging**: `infra/scripts/deploy-frontend.sh` builds with
  `VITE_API_BASE_URL=https://api.cadence.bioinform.co.uk` — the Java backend.

## Parity requirement

A schema or behavior change almost always needs mirroring in **both**
`backend/` and `backend_java/` — they implement the same `openapi.yaml`
contract and the same Postgres schema shape, via two independently-authored
migration chains (Django migrations and Flyway) rather than a shared
database. When changing one, check whether the other needs the same change
before calling the work done.

## Running the whole stack locally

Full walkthrough: `GETTING_STARTED.md`. Quick version:

```bash
# Backend (Java, default) — http://localhost:8080
cd backend_java && docker compose up -d

# Frontend — http://localhost:5173 (or next free port, check Vite's output)
cd frontend && npm install && npm run dev
```

# AWS Guidance

- Prefer the AWS MCP Server for AWS interactions — it provides sandboxed
  execution, observability, and audit logging. If unavailable, use the
  AWS CLI directly.
- Before starting a task, check whether a relevant AWS skill is available.
  Load the skill with `retrieve_skill` and prefer its guidance over
  general knowledge.
- When uncertain about specific AWS details (API parameters, permissions,
  limits, error codes), verify against documentation rather than guessing.
  State uncertainty explicitly if you cannot confirm.
- When creating infrastructure, prefer infrastructure-as-code (AWS CDK or
  CloudFormation) over direct CLI commands.
- When working with infrastructure, follow AWS Well-Architected Framework
  principles.
- Do not use em dashes in AWS resource names or descriptions. Use
  hyphens instead.

## Secret Safety

- MUST load the `aws-secrets-manager` skill first for any secret,
  credential, API key, token, or password task. MUST NOT call
  `secretsmanager get-secret-value` or `batch-get-secret-value`, and MUST
  NOT hit the Secrets Manager Agent daemon directly. MUST use
  `{{resolve:secretsmanager:secret-id:SecretString:json-key}}` with
  `asm-exec` so the secret resolves at runtime without entering context.
