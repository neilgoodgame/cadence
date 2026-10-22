# Garmin Connect sync: design note (draft)

Status: **draft, written 2026-10-22 before any API access.** Garmin's per-API docs
(Activity API spec, OAuth, endpoints) are behind the developer portal and could not
be fetched, so everything marked **UNVERIFIED** comes from secondary sources and must be
checked against the portal docs once Starter access is granted.

## Why

Import is currently user-initiated file upload only (`.fit`/`.gpx`/`.tcx`, single or
bulk `.zip`; see ARCHITECTURE.md "Webhooks" row). The October 2026 Connect Developer
Program revamp (see DC Rainmaker article, 2026-10) makes automatic activity sync
available to small apps for free.

## What is verified (from developer.garmin.com/gc-developer-program/overview/)

| Tier | Access | Activity data | Delivery | History | Users |
|---|---|---|---|---|---|
| Personal | Personal API key + tokens from Garmin Connect (`connect.garmin.com/app/settings/personalApiToken`) | JSON + `.FIT` | **Pull only** | n/a | 1 (yourself) |
| Starter (business) | Apply via Garmin form, then create an app | JSON + `.FIT` | **Push only** | **None** | Limited |
| Enterprise | Request via portal after Starter | JSON + `.FIT` | Push or ping/pull | 1 month or more | Unlimited, support |

Consequences:

1. **Personal and Starter use different delivery models** (pull vs push). Personal is
   *not* a dry run of the Starter integration. It can validate file contents but not
   the webhook path.
2. **Starter has no historical data.** A newly connected athlete only gets activities
   from connection time onward. The existing bulk-zip import stays the backfill path.
3. Terms of Use apply (`garmin.com/en-US/legal/terms-of-use/`) plus Garmin brand
   guidelines (attribution requirements likely).

## Risks and open questions

- **Program status.** Forum posts (pre-reopening) reported requests going unanswered.
  Approval time for Starter is unknown. The article claims near-instant.
- **Eligibility.** One third-party source (Open Wearables docs) says personal-use apps
  and free-mail contact addresses are rejected and a legal entity is required. That
  predates the revamp and may be superseded. **Check the Starter form requirements
  before applying** (company email domain? legal entity?).
- **Auth scheme. UNVERIFIED.** Sources conflict: older guides say OAuth 1.0a
  (consumer key/secret); Open Wearables describes OAuth 2.0 three-legged with
  client id/secret and an exact-match registered redirect URI. Assume 2.0 for the new
  program; confirm token lifetime, refresh, PKCE and scopes.
- **FIT delivery mechanism. UNVERIFIED.** Whether push payloads carry the FIT inline
  or a download URL, and the URL's lifetime. Forum reports say `callbackURL` and
  `summaryId` in payloads vary per call, so do not use them as identity; dedupe on
  activity id / start time.
- **Stripped data: NOT observed on the Personal tier (tested 2026-10-22).** The
  article (citing Runalyze) says API FIT files may lack fields present in manual
  exports. For 3 activities (marathon, a 4 km run, a 3.3 km run) the file from
  `GET /download-service/files/activity/{id}` is **byte-identical (SHA-256)** to the
  manual "Export Original". For the 4 Oct 35 km run (Stryd + CORE) the API file
  contains the CORE developer fields (`core_temperature`, `skin_temperature`,
  `heat_strain_index`) and all Stryd fields, with the same message counts as
  the user's `_corrected.fit` (which differs only by a couple of header bytes; it is an
  edited file, not a pristine export). Caveat: this is the Personal pull endpoint.
  Starter delivers via push, so re-run the comparison on a pushed file.
- **Terms vs sharing.** Commenters report a ban on passing data to downstream parties
  or sublicensing without written permission. Cadence has coach/viewer sharing, outbound
  HMAC webhooks, and public pages. Read the terms and decide what is in scope.
- **Gear.** No official endpoint reported, so shoe/bike assignment would stay manual.
- **Backfill (if ever Enterprise).** Open Wearables reports about 1 month, one day per
  request, once per data type, and an opt-in backfill scope. Not relevant to Starter.

## Proposed design

### Principle
Garmin is just a new *source* feeding the existing upload and parse pipeline. Do not
build a second ingestion path.

### Data model (both backends, parity)
- `garmin_connection`: athlete id (FK), Garmin user id, encrypted access/refresh
  tokens, expiry, granted scopes, `connected_at`, `last_event_at`, status
  (`ACTIVE`/`REVOKED`/`ERROR`).
- Dedupe key on activities: Garmin activity id (unique per athlete) so webhook retries
  and later manual uploads of the same activity do not duplicate. Check how existing
  upload dedupe works first and reuse it if possible (start time + duration?).
- Flyway migration + Django migration, `openapi.yaml` updated first.

### Endpoints (added to `openapi.yaml`, then both backends)
- `GET /garmin/connect` returns the Garmin authorize URL (state bound to the session).
- `GET /garmin/oauth/callback` exchanges the code and stores the connection.
- `DELETE /garmin/connection` revokes and deletes tokens.
- `POST /garmin/webhooks/push` is the **unauthenticated** Garmin receiver. It needs a
  verification model confirmed from the docs (shared secret, IP allowlist, or only the
  per-user token in the payload). Treat the body as untrusted, and never echo any of
  it into a response (see the SSE/CodeQL feedback). Return 200 fast and process async.
- Handle Garmin's user deregistration / data-deletion callbacks (**UNVERIFIED** that
  they exist; expected for compliance).

### Ingestion flow
1. Push arrives, receiver validates and maps Garmin user id to athlete.
2. Enqueue the same job the upload flow uses (Spring Batch `parseFileStep` /
   Celery task) with the FIT bytes (fetch if only a URL is supplied).
3. Parse with the existing `FitFileParser`. Stub or metadata-only files already exit
   cleanly (`NO_ACTIVITY_DATA` / `status=skipped`).
4. Batch status and Import screen show these as a "Garmin sync" source.

### Frontend
- Preferences: "Connect Garmin" / "Disconnect", last sync time, error state.
- Garmin brand attribution wherever the data is shown, if the guidelines require it.

### Infra
- Public HTTPS webhook route on `api.cadence.bioinform.co.uk` (first inbound
  third-party webhook, so AWS_MIGRATION_PLAN.md's "no public receiver needed" note no
  longer holds). Check ALB/WAF rules.
- Client id/secret in Secrets Manager (use the `aws-secrets-manager` skill; never read
  the values into context).
- **A backend restart kills in-flight work**, so make webhook processing idempotent and
  retry-safe (Garmin retries on non-2xx, but not forever).

## Plan

0. **Now:** read the Starter form requirements. Request Starter access. Request a
   Personal token.
1. **Personal-tier spike: DONE 2026-10-22, gate passed** (see "Stripped data"). Notes
   for later: token endpoint (`POST
   personal.connectapi.garmin.com/personalapitoken-service/personal-api/personal-api-key/token`,
   `Authorization: ApiKey <key>`) returns a bare JWT as text/plain (not JSON), valid 24h,
   no refresh. Activity list: `GET /activitylist-service/activities/search/activities`
   (`start`, `limit`, `startDate`, `endDate`, `activityType`). File: `GET
   /download-service/files/activity/{id}` returns a **ZIP** containing
   `{id}_ACTIVITY.fit`, which is the same shape as the manual export zip, so the existing
   zip upload path should accept it unchanged. These are Personal-tier paths; Starter
   push may differ. Personal keys are not for multi-user commercial apps.
2. **After Starter approval:** read the real portal docs, resolve every UNVERIFIED
   item above, and revise this note.
3. **Build (Java first, Django for parity):** OpenAPI, migration, OAuth connect,
   webhook receiver, pipeline hook, Preferences UI, tests including a fake-Garmin
   webhook fixture.
4. **Optional later:** Courses & Training API push from the Workout Designer (replace
   the `.tcx` export). Separate design, since it is outbound.

## Sources
- https://developer.garmin.com/gc-developer-program/overview/ (verified)
- https://www.dcrainmaker.com/2026/10/garmin-connect-api-revamps-connect-developer.html
- https://openwearables.io/docs/providers/garmin-api-integration (secondary, pre-revamp)
- https://forums.garmin.com/developer/connect-iq/f/discussion/440200/ (community, unofficial)
