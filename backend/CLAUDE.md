# Backend (Django)

Django + DRF implementation of `../openapi.yaml`. Not deployed; kept at parity with `../backend_java` (see root `CLAUDE.md`). `../backend_java` is readable/editable from here.

## Commands (run from `backend/`)

Before pushing, all three must pass:

1. `uv run ruff check .`
2. `uv run ruff format --check .`
3. `uv run pytest -m unit -q` and `uv run pytest -m integration -q` (integration needs the `db` container up)

- One app while iterating: `uv run python manage.py test <app>` (e.g. `gear`).
- Long output: redirect, then read the summary only.
  - `uv run pytest -m unit -q > /tmp/pytest.log 2>&1; echo $?`
  - `grep -A 50 "short test summary" /tmp/pytest.log`
  - Rerun the one failing test for details: `uv run pytest path/to/test.py::TestCase::test_name`
- New migration: `uv run python manage.py makemigrations <app>`. If it includes changes you didn't make, split them into a separate migration.

## Conventions

- Apps map ~1:1 to API resource groups: `accounts`, `activities`, `athletes`, `gear`, `workouts`, `scheduling`, `races`, `dataexport`, `uploads`, `webhooks`, `adminapi` (the in-app Admin screen, not Django's `/admin/`).
- Access checks: use `core.permissions.user_may_read` / `user_may_write` / `user_is_admin` and `core.auth_context.get_effective_athlete_id`. Never hand-roll ownership checks.
- API errors: raise exceptions from `core/exceptions.py`; `cadence_exception_handler` turns them into the `{"error": {...}}` envelope.
