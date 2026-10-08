# Backend (Java / Spring Boot)

Spring Boot implementation of the Cadence API contract (`../openapi.yaml`).
**This is the backend actually deployed** to staging/production —
`infra/scripts/deploy-backend.sh` only builds and ships this image
(`backend/`, the Django implementation, is kept at feature parity but not
deployed). For setup/architecture, see `README.md` in this directory and the
repo root's `GETTING_STARTED.md`/`ARCHITECTURE.md`.

A session started in this directory can also read/edit `../backend` — see
`.claude/settings.json`'s `additionalDirectories` — which is useful when
checking the other side of a parity change.

## Commands

- Tests (matches CI exactly): `./gradlew unitTest` (no Docker) and
  `./gradlew integrationTest` (needs Docker — Testcontainers starts its own
  Postgres). **Run these two tasks specifically before pushing, not just
  `./gradlew test`** — a stale local Testcontainers instance (reused across
  many local runs) can mask a test-isolation bug that only shows up against
  CI's fresh database. See the `IntegrationTest` gotcha below.
- Compile only: `./gradlew compileJava compileTestJava` — much faster than a
  full test run for checking a change compiles.
- New migration: add `src/main/resources/db/migration/V<N>__description.sql`
  (Flyway), where `N` is one more than the highest existing `V*` file.

## Gotchas

- **`IntegrationTest` shares one non-rolled-back Postgres across every test
  class in a run** (deliberate — avoids container start/stop races between
  sibling classes; see `support/IntegrationTest.java`'s own comment). Two
  *different* test files that happen to reuse the same literal fixture
  string (a `User` email, a `ShoeModel` manufacturer+model, a tag name, ...)
  collide — either a real unique-constraint violation, or a silently wrong
  row match via a `findFirst`-style lookup. **Grep the whole `src/test` tree
  for a literal before reusing it**, and prefer a file-scoped unique prefix
  for every new test class's fixtures. This has broken real PRs twice; it
  only reproduces with a *fresh* `./gradlew integrationTest` run, not a
  long-lived local one.
- **Records used as DTOs/request bodies, constructed positionally in
  tests**: adding a field to the *middle* of a record breaks every
  positional `new Foo(...)` call site. If there are many call sites and
  they're all flat (no nested parens), a small script that splits each call
  on `,` and inserts the new arg at the right index is far more reliable
  than a regex (a call site with nested parens silently won't match a naive
  regex). Appending the new field at the *end* of the record avoids this
  entirely when there's no strong reason to put it elsewhere.
- **A JPA entity's lazy (`FetchType.LAZY`) association can throw
  `LazyInitializationException`** if the entity was loaded by a *different*,
  already-closed Hibernate session than the one currently open — e.g. a
  controller does a plain (non-`@Transactional`) repository lookup, then
  passes that entity into a `@Transactional` service method: Spring's
  `@Transactional` opens a *new* session for that method but does not
  reattach the entity. Reading a lazy association's `.getId()` is safe
  either way (Hibernate proxies store the FK without initializing); fully
  dereferencing it is not. Fix: re-fetch within the transactional method's
  own session before touching anything beyond an id (see
  `ActivityService.updateActivity`'s own comment for a worked example).

## Conventions

- Packages map roughly 1:1 to API resource groups, mirroring `backend/`'s
  Django apps: `activities`, `athletes`, `gear`, `workouts`, `admin`,
  `uploads`, `scheduling`, `races`, `sharing`, `tokens`, `export`/`imports`,
  `webhooks`, `users`.
- Every entity needing a Stripe-style id (`act_...`, `wkt_...`, ...) extends
  `common.id.PrefixedIdEntity` and implements `idPrefix()`. Internal-only
  join/log tables don't.
- Custom API exceptions extend `common.error.ApiException`, caught centrally
  by `common.error.ApiExceptionHandler` into the same `{"error": {...}}`
  envelope Django produces.
- Access checks go through `security.AccessGuard`
  (`requireRead`/`requireWrite`/`requireAdmin`), mirroring Django's
  `core.permissions`/`core.auth_context`.
