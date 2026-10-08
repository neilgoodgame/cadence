# Backend (Django)

Django + DRF implementation of the Cadence API contract (`../openapi.yaml`).
**Not currently deployed** — `backend_java/` is what's live in
staging/production; this one is kept at feature parity in case production
ever switches to it (see the root `CLAUDE.md`'s parity requirement). For
setup/architecture, see `README.md` in this directory and the repo root's
`GETTING_STARTED.md`/`ARCHITECTURE.md`.

A session started in this directory can also read/edit `../backend_java` —
see `.claude/settings.json`'s `additionalDirectories` — which is useful when
checking the other side of a parity change.

## Commands

- Tests (matches CI): `uv run pytest -m unit -q` (no DB needed) and
  `uv run pytest -m integration -q` (needs the `db` container up). A targeted
  `.venv/bin/python manage.py test <app>` (e.g. `gear`, `athletes`) also
  works and is usually faster while iterating on one app.
- Lint/format (matches CI): `uv run ruff check .` and
  `uv run ruff format --check .` — pre-commit also runs ruff on every commit.
- **Taming output**: a full `pytest`/`manage.py test` run can print hundreds
  of lines per failure (full traceback, Django request/response dumps on
  some error paths). Redirect to a file instead of letting it print
  directly: `uv run pytest -m unit -q > pytest.log 2>&1; echo $?` (scratch
  path, not tracked), then read only what's needed — pytest's own
  one-line-per-test "short test summary info" at the end
  (`grep -A 50 "short test summary" pytest.log`) usually tells you which
  test(s) failed without needing the full tracebacks; rerun just that test
  (`uv run pytest path/to/test.py::TestCase::test_name`) for its full output
  once you know which one it is.
- New migration: `python manage.py makemigrations <app>` after a model
  change. **Watch for unrelated drift**: `makemigrations` surfaces *every*
  unmigrated model change, not just yours — if it bundles something you
  didn't touch, split it into its own migration rather than silently
  shipping an unrelated change in your PR.

## Conventions

- Apps map roughly 1:1 to API resource groups: `accounts` (User/auth),
  `activities`, `athletes` (thresholds/zones/best-efforts), `gear`,
  `workouts`, `scheduling`, `races`, `dataexport`, `uploads`, `webhooks`,
  `adminapi` (the in-app Admin screen — distinct from Django's own
  `/admin/`).
- Every view's access check goes through `core.permissions.user_may_read`/
  `user_may_write`/`user_is_admin` and
  `core.auth_context.get_effective_athlete_id` (handles coach delegation) —
  don't hand-roll ownership checks.
- Custom API exceptions live in `core/exceptions.py` (`ConflictError`,
  `PayloadTooLargeError`, ...) — `core.exceptions.cadence_exception_handler`
  wraps everything into the `{"error": {...}}` envelope `openapi.yaml`
  documents.
