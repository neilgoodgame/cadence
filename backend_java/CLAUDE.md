# Backend (Java / Spring Boot)

Spring Boot implementation of `../openapi.yaml`. **This is the deployed backend.** `../backend` (Django) is kept at parity (see root `CLAUDE.md`) and is readable/editable from here.

## Commands (run from `backend_java/`)

Before pushing, run both (not just `./gradlew test`; a stale local Testcontainers DB can hide isolation bugs):

1. `./gradlew unitTest` (no Docker)
2. `./gradlew integrationTest` (needs Docker)

- Compile check only: `./gradlew compileJava compileTestJava`
- Long output: redirect, then read the summary only.
  - `./gradlew integrationTest --console=plain > /tmp/gradle.log 2>&1; echo $?`
  - `grep -E "FAILED|ERROR" /tmp/gradle.log`, or `tail -n 30 /tmp/gradle.log`
- New migration: `src/main/resources/db/migration/V<N>__description.sql`, where `N` = highest existing `V*` number + 1.

## Gotchas

- **Fixture collisions in `IntegrationTest`.** All integration test classes share one Postgres that is not rolled back. Two test files reusing the same literal (user email, `ShoeModel` manufacturer+model, tag name) collide.
  - Rule: grep `src/test` for a literal before using it. Give each new test class a unique prefix for its fixtures.
  - This only shows on a fresh `./gradlew integrationTest` run.
- **Positional record construction in tests.** Adding a field to the middle of a record breaks every `new Foo(...)` call. Append new fields at the end.
- **`LazyInitializationException`.** If a controller loads an entity outside a transaction and passes it to a `@Transactional` service method, lazy associations can't be read (`.getId()` is fine). Re-fetch the entity inside the service method (example: `ActivityService.updateActivity`).

## Conventions

- Packages map ~1:1 to API resource groups (same as Django apps): `activities`, `athletes`, `gear`, `workouts`, `admin`, `uploads`, `scheduling`, `races`, `sharing`, `tokens`, `export`/`imports`, `webhooks`, `users`.
- Entities with Stripe-style ids (`act_...`, `wkt_...`) extend `common.id.PrefixedIdEntity` and implement `idPrefix()`. Internal join/log tables don't.
- API errors: extend `common.error.ApiException`; `common.error.ApiExceptionHandler` produces the `{"error": {...}}` envelope.
- Access checks: `security.AccessGuard` (`requireRead` / `requireWrite` / `requireAdmin`).

## Memory-capped profiling (rarely needed)

Staging runs with a 600MB cap and NMT on. To mirror it locally: `docker compose --profile memcapped up -d backend-memcapped` (port 8081). Read NMT with a companion container sharing its PID namespace (the runtime image has no `jcmd`):

`docker run --rm --pid=container:<memcapped container name> eclipse-temurin:25-jdk-noble jcmd 1 VM.native_memory summary`

Find the container name with `docker ps`.
