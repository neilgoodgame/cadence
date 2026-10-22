# Garmin Connect sync: design note

Status: **Personal-key pull sync built (Java + Django, PR #457).** Starter-tier push
(OAuth + webhook) is not built; it is waiting on Garmin approval and the section
"Future: Starter push" is still a draft. Anything marked **UNVERIFIED** comes from
secondary sources and must be checked against the portal docs once Starter access is
granted.

## Why

Import was user-initiated file upload only (`.fit`/`.gpx`/`.tcx`, single or bulk
`.zip`). The October 2026 Connect Developer Program revamp (DC Rainmaker article,
2026-10) makes automatic activity sync available to small apps for free.

## Garmin tiers (verified from developer.garmin.com/gc-developer-program/overview/)

| Tier | Access | Activity data | Delivery | History | Users |
|---|---|---|---|---|---|
| Personal | Personal API key from Garmin Connect (`connect.garmin.com/app/settings/personalApiToken`) | JSON + `.FIT` | **Pull only** | n/a | 1 (yourself) |
| Starter (business) | Apply via Garmin form, then create an app | JSON + `.FIT` | **Push only** | **None** | Limited |
| Enterprise | Request via portal after Starter | JSON + `.FIT` | Push or ping/pull | 1 month or more | Unlimited, support |

Consequences:

1. Personal and Starter use different delivery models (pull vs push). Personal is **not**
   a dry run of the Starter integration: it validated file contents, not the webhook path.
2. Starter has no history. A newly connected athlete only gets activities from connection
   time onward; bulk-zip import stays the backfill path.
3. Terms of Use apply (`garmin.com/en-US/legal/terms-of-use/`) plus brand guidelines.
   Personal keys are "not intended for commercial applications serving multiple end
   users", so the built feature is a stopgap until Starter, and **the Terms still need
   reading** before other users store keys on a shared deployment.

## Personal API facts (spike, 2026-10-22)

- Token: `POST personal.connectapi.garmin.com/personalapitoken-service/personal-api/personal-api-key/token`
  with `Authorization: ApiKey <key>` returns a bare JWT (text/plain, **not** JSON), valid
  24h, no refresh. A sync exchanges the key every time.
- List: `GET /activitylist-service/activities/search/activities` (`start`, `limit`,
  `startDate`, `endDate`, `activityType`). **Page size max 50**; 100 or more returns HTTP 500.
- File: `GET /download-service/files/activity/{id}` returns a **ZIP** containing
  `{id}_ACTIVITY.fit`, the same shape as a manual export zip.
- **No stripped data observed.** For 3 activities the API file was byte-identical
  (SHA-256) to the manual "Export Original". The 4 Oct 35 km Stryd + CORE run kept all
  developer fields (`core_temperature`, `skin_temperature`, `heat_strain_index`, Stryd).
  Caveat: this is the Personal pull endpoint. Starter delivers via push, so re-run the
  comparison on a pushed file.

## What is built

Principle: Garmin is just a new *source* feeding the existing batch upload and parse
pipeline. There is no second ingestion path.

### Connection (Preferences > Garmin)
- `GET/PUT/DELETE /v1/garmin/connection`. Stores the Personal API key, a Garmin account
  email (defaults to the Cadence email) and an optional key expiry date.
- The key is encrypted with **AES-256-GCM**: `v1.<base64url(iv12 || ciphertext+tag)>`,
  the owner id as AAD, key from `GARMIN_ENCRYPTION_KEY` (base64, 32 bytes). No default:
  with it unset, saves return 503. The key is never returned by the API.
- A past expiry date is rejected; the UI warns at 14 days or less. An expired key blocks
  sync (409) with a message pointing at Preferences.
- Java and Django ciphertext are interchangeable, proven by a shared fixture in both
  test suites.
- `sync_limit` (1-100, default 50) is how many activities one sync pulls, set in Preferences
  ("Activities per sync"). It is per athlete, not server config; 100 is a fixed ceiling.
- Table `garmin_connection` (owner, email, encrypted key, expiry, `sync_limit`, `last_synced_at`).

### Sync (`POST /v1/garmin/sync`, body `{limit?, since?, latest?, ...session details}`)
Pull on request, from the Import screen. Returns 202 with the counts and the upload batch.

1. Window start is `since`, else `last_synced_at - 1 day`, else 30 days back.
2. Exchange the key for a bearer token, then list the window in pages of 50 (cap 1000
   summaries), oldest first.
3. Drop Garmin activity ids already recorded for this athlete (**before** downloading).
4. Take the first `limit` candidates. `limit` defaults to the athlete's saved `sync_limit`
   (50 unless changed), hard max **100**. `truncated` is true when candidates remain.
5. Download and unzip each (a short pause between downloads). A 502 from Garmin counts as
   `failed` and the sync continues; a 401/403 is a 422 "key rejected" and aborts.
6. Hand the FIT files to the normal batch upload (`createBatchUpload` /
   `create_batch_from_files`, filename `garmin-sync`, `OnDuplicate.SKIP`). The pipeline's
   per-athlete SHA-256 file-hash dedupe still applies, so a manual upload of the same
   file is reported as a duplicate.
7. Record each downloaded id (`garmin_synced_activity`).
8. `last_synced_at` is the next window marker: the last taken activity's start time when
   truncated, else now. It **only advances if no downloads failed**, so a failed activity
   is inside the next window.

### Latest-only sync (`latest: true`)
Syncs just Garmin's newest activity (`start=0&limit=1`), for when you want to add the session
details right away. It accepts the same optional details as a single-file import
(`weight_before_kg`, `weight_after_kg`, `fluids_ml`, `shoe_id`), applied to the imported
activity; they are rejected without `latest`, and an unknown shoe is a 400 before any run is
recorded. It goes through the same one-file batch, records the id like any sync (so a later
normal sync skips it), and **does not move `last_synced_at`**, so older activities in the window
are still picked up by the next normal sync. If the newest activity was already synced it is
skipped, so edit that activity directly to add details.

### Activity-id tracking (why re-syncs behave)
`garmin_synced_activity` holds (owner, Garmin activity id, start time, run) with a unique
(owner, id). It outlives both runs (FK SET NULL) and Cadence activities, so:

| Situation | Result |
|---|---|
| Sync twice | Known ids are skipped without downloading |
| Activity deleted in Cadence | **Not** brought back (tombstone) |
| Same file also uploaded manually | File-hash dedupe marks the Garmin copy a duplicate |
| Activity edited in Garmin Connect | Not re-fetched (same id). A "re-import" action is not built |
| Download fails (502) | Not recorded, retried next sync |
| Download succeeds but fails to parse | Recorded as synced, shown as "Import failed", **not retried** |

The latest synced activity is known from this table (`last_activity` on the connection).

### History
- Every attempt is a `garmin_sync_run` (started, finished, found, already_synced,
  requested, failed, truncated, error, batch). Failed attempts record a reason we wrote,
  never the key. The latest **50 per athlete** are kept.
- `GET /v1/garmin/syncs?limit=` returns runs with their activities (Garmin id, start,
  upload status, linked Cadence activity), joined to the batch by `<id>_ACTIVITY.fit`.
- The connection response carries `last_run_at` and `last_activity`.
- A worker interrupt (timeout, shutdown) closes the run with an "interrupted" error
  rather than leaving it in flight (Django; Java would leave it open if the JVM is killed).

### Frontend
- Import screen: **Sync from Garmin** card above the dropzone: Sync now, last-run summary
  ("N downloaded, N already synced, N failed"), live batch status, "More activities
  remain, sync again" when truncated, and a collapsible Recent syncs list. Rows read
  Importing, Already imported, Import failed, Skipped (no activity data), Import record
  cleared, or Activity no longer in Cadence, and link to the activity. The history polls
  every 3s while a run is in flight. Not connected: a link to Preferences. Expired: the
  button is disabled.
- The card also has **Latest only...**, which opens the same Session details fields as the
  single-file import (shared `SessionDetailsFields` component) and a Sync latest activity button.
- Preferences > Garmin shows connection status, last sync and latest activity, and sets
  **Activities per sync** (1-100).

### Contract and parity
`openapi.yaml` (tag Garmin) is canonical. Java: `com.cadence.api.garmin`, Flyway V84/V85.
Django: `backend/garmin`, migrations 0001/0002. Prefixes `gcn`, `gsr`.

## Workout push (local bridge)

The Personal API key has **no workout routes** (tested 2026-10-22: 404 on `/workout-service/*` on the
Personal gateway, and the Personal token is rejected by `connectapi.garmin.com`). The only way to push
workouts today is the unofficial `garminconnect` login that the `garmin-sync` CLI uses. The Developer
Program Agreement (section 15.8) forbids a licensee from obtaining end-user Garmin credentials or
implementing an unapproved authorization system, so **Cadence never holds Garmin credentials**: the push
runs on the athlete's own machine and Cadence only keeps a queue.

- **Browser** (shown whenever there is a usable Garmin connection): "Push to Garmin Connect" when saving a
  workout, "Also add to Garmin Connect" when scheduling, and "Apply to Garmin Connect" when rescheduling or
  unscheduling something already on the Garmin calendar. The browser builds the `.tcx` (it needs the athlete's
  thresholds, and the generator lives in `workoutExport.ts`) and posts operations to the outbox.
- **Outbox** (`garmin_workout_op`, `/v1/garmin/workout-ops`): kinds `upsert_workout`, `schedule`, `reschedule`,
  `unschedule`. A pending operation for the same workout or scheduled workout is updated in place instead of
  queued twice; `schedule`/`reschedule` merge; cancelling something that never reached Garmin drops it.
- **Links** (`garmin_workout_link`, `garmin_schedule_link`, `/v1/garmin/workout-links`): Cadence ids to Garmin
  ids, filled from the bridge's results, so edits update in place and moves and cancels hit the right calendar
  entry.
- **Bridge** (`garmin-sync push-workouts`, in the separate garmin-sync repo): fetches pending operations,
  applies them in order with the athlete's own login (upload or PUT the workout, `schedule_workout`,
  `unschedule_workout`), and reports each result. A failure is shown in Preferences and the rest of the queue
  still runs. A reschedule is unschedule plus schedule.
- **Not done:** deleting a Cadence workout does not delete it on Garmin; the queue only moves when the bridge
  is run (no daemon); coach-assigned workouts are only pushable once scheduled for the athlete.
- **Staging:** nothing runs in AWS for this. The bridge points `GARMIN_SYNC_URL` at the staging API with a
  Cadence personal access token. Deploying needs the Flyway V87 migration (applied on startup), a backend
  restart and a manual frontend deploy; no new secrets or Terraform. To avoid running it by hand, schedule
  `garmin-sync push-workouts` locally (cron or launchd); it does nothing when the queue is empty. Hosting it
  in AWS would mean storing Garmin credentials, which is ruled out above.
- **Verified** against a real Garmin account on localhost: push, schedule, reschedule (no duplicate), cancel
  (template kept) and edit (same Garmin id, new content).

## Operations
- **`GARMIN_ENCRYPTION_KEY` must be set** (`openssl rand -base64 32`). Staging gets it from
  Secrets Manager `cadence-staging-garmin-encryption-key` via Terraform/`user_data`
  (see infra/README.md). There is no key versioning: changing or losing it makes every
  stored Garmin key undecryptable and athletes must re-enter theirs.
- A sync is a **synchronous request** that can take tens of seconds. Django runs gunicorn
  with `--timeout 120` (the 30s default killed a 50-activity sync). On staging (Java
  behind CloudFront and the ALB) check the idle timeouts against a full 50-activity sync.
- Applying the Terraform change replaces the EC2 instance (`user_data` changed); warn first.
- Frontend is deployed manually (`infra/scripts/deploy-frontend.sh`).

## Open questions and follow-ups
- Read Garmin's Terms of Use on hosting other users' personal keys.
- "Re-import this activity" for activities edited in Garmin Connect, and retry of
  parse-failed downloads.
- Run sync as a background job instead of a request if the sync duration is a problem.
- Close in-flight runs after a JVM kill on the Java side.
- Gear: no official endpoint reported, so shoe/bike assignment stays manual.

## Future: Starter push (draft, UNVERIFIED)

Not built. Revise once Starter access is granted and the portal docs can be read.

- **Eligibility.** One third-party source says personal-use apps and free-mail contact
  addresses are rejected and a legal entity is required (pre-revamp, may be superseded).
  Check the form before relying on it.
- **Auth.** Sources conflict: OAuth 1.0a vs OAuth 2.0 three-legged with an exact-match
  redirect URI. Confirm token lifetime, refresh, PKCE and scopes.
- **FIT delivery.** Whether pushes carry the FIT inline or a download URL, and its
  lifetime. Payload `callbackURL` and `summaryId` reportedly vary per call, so dedupe on
  the Garmin activity id, which the sync above already tracks.
- **Data model.** Reuse `garmin_connection` for OAuth tokens (encrypted the same way) and
  `garmin_synced_activity` as the dedupe record, so push and pull share one ledger.
- **Endpoints.** `GET /garmin/connect` (authorize URL), `GET /garmin/oauth/callback`,
  and an **unauthenticated** `POST /garmin/webhooks/push` whose verification model is
  unknown (shared secret, IP allowlist, or per-user token). Treat the body as untrusted,
  never echo it into a response (SSE/CodeQL feedback), return 200 fast and process async.
  Handle deregistration / data-deletion callbacks if they exist.
- **Ingestion.** Same batch upload job and `FitFileParser`; stub files already exit as
  `NO_ACTIVITY_DATA`.
- **Infra.** First inbound third-party webhook: public HTTPS route on
  `api.cadence.bioinform.co.uk`, ALB/WAF rules, client id/secret in Secrets Manager. A
  backend restart kills in-flight work, so make processing idempotent.
- **Re-verify** pushed FIT files against manual exports before trusting them.
- **Terms vs sharing.** Commenters report a ban on passing data to downstream parties.
  Cadence has coach/viewer sharing, outbound webhooks and public pages; decide scope.
- **Backfill** exists only on Enterprise (about 1 month, one day per request).
- **Optional later:** the official Training API would replace the local bridge for workout push (the
  agreement defines a Training API and a Courses API). Tier availability is unverified.

## Sources
- https://developer.garmin.com/gc-developer-program/overview/ (verified)
- https://www.dcrainmaker.com/2026/10/garmin-connect-api-revamps-connect-developer.html
- https://openwearables.io/docs/providers/garmin-api-integration (secondary, pre-revamp)
- https://forums.garmin.com/developer/connect-iq/f/discussion/440200/ (community, unofficial)
