# Repo Layout

Cadence is a training-log/analytics app: two parity-kept backends implementing one REST API, behind one React frontend.

- `backend/`: Django + DRF. Not deployed.
- `backend_java/`: Spring Boot. **The deployed backend** (`infra/scripts/deploy-backend.sh` ships only this).
- `frontend/`: React + Vite + TypeScript. Calls whichever backend `VITE_API_BASE_URL` (`frontend/.env`) points at.
- `infra/`: Terraform + deploy scripts (`deploy-backend.sh`, `deploy-frontend.sh`) for AWS staging.
- `openapi.yaml`: hand-maintained API contract. It is the source of truth if it disagrees with a backend's generated `/schema/docs`.
- Stack-specific rules: `backend/CLAUDE.md`, `backend_java/CLAUDE.md`, `frontend/CLAUDE.md`. Deeper docs: `ARCHITECTURE.md`, `GETTING_STARTED.md`.

## Parity (important)

A schema or behavior change usually needs the same change in **both** backends (separate migration chains: Django migrations and Flyway; same Postgres schema shape). Before calling work done, check whether the other backend needs it.

- `backend/.claude/settings.json` and `backend_java/.claude/settings.json` grant each session access to the sibling directory (source only; secrets and build dirs are denied).

## Local run

- Backend (Java), http://localhost:8080: `cd backend_java && docker compose up -d`
- Frontend, http://localhost:5173 (check Vite output): `cd frontend && npm install && npm run dev`
- Django instead: run it on :8000 and set `VITE_API_BASE_URL=http://localhost:8000`.
- Staging frontend is built against `https://api.cadence.bioinform.co.uk` (Java).

## AWS

- Use the AWS MCP server or an AWS skill if one is available; otherwise use the AWS CLI.
- Prefer infrastructure-as-code (CDK or CloudFormation) over ad-hoc CLI changes.
- If unsure of an AWS parameter, permission or limit, check the docs and say so. Do not guess.
- No em dashes in AWS resource names or descriptions; use hyphens.

## Secrets

- Never print or fetch a secret value into the conversation. Never run `secretsmanager get-secret-value` or `batch-get-secret-value`.
- Use `{{resolve:secretsmanager:secret-id:SecretString:json-key}}` with `asm-exec` when available. If it isn't, stop and ask the user to run the command.
