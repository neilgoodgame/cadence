# AWS Migration Plan

## 0. Purpose and scope

This document plans a migration of Cadence from local Docker Compose to a
production AWS deployment. It covers: service-to-service AWS mapping, the
Python-vs-Java backend question, whether FIT/GPX/TCX ingestion should move to
Lambda, deployment/CI-CD strategy, observability, rough costs, a phased
migration path, and a list of decisions that need a human call rather than an
engineering default.

It's written against the codebase as it actually exists today, not an
idealized version of it — where the current code has an assumption that
would break in AWS (e.g. a direct filesystem path read), that's called out
as a concrete pre-migration task, not glossed over.

## 1. Current architecture (as-is)

See `ARCHITECTURE.md` for the full system reference (domain model, auth,
the CQL query language, ingestion pipeline, frontend, testing, CI). This
section is just the subset relevant to an AWS move.

| Component | What it is today |
|---|---|
| **Python backend** | Django 6 + DRF, `gunicorn`, served on :8000. OAuth2 (django-oauth-toolkit) + scoped delegated JWTs (RS256, key pair on disk). |
| **Java backend** | Spring Boot 4 / Java 24, parity port of the same API, served on :8080. Same JWT scheme, own key pair. Uses Spring Batch **in-process** (not a queue) for upload ingestion — no separate worker process, no Redis dependency — but jobs are deliberately serialized onto a single virtual thread, one file at a time, not concurrent (see `ARCHITECTURE.md` §5.2). |
| **Celery worker (Python only)** | Separate container, consumes Redis-backed queue: `process_upload` (parses FIT/GPX/TCX, computes streams/laps/best-efforts/TSS) and `deliver_webhook` (signed outbound POST with retry/backoff). Also runs an embedded beat scheduler (`-B` flag) for one periodic task, `ensure_record_partitions` (see the Database row below). |
| **Database** | Plain PostgreSQL 16, one instance per backend locally. `activities_record`/`record` (1 Hz stream data: HR/power/cadence/etc. per second per activity) is **natively RANGE-partitioned on `ts`**, monthly partitions plus a `DEFAULT` catch-all, created via raw SQL migration. A small scheduled task in each backend keeps rolling the forward edge of the partition range ahead over time (Postgres has no automatic partition creation the way TimescaleDB's hypertables did — this app previously used TimescaleDB for that, dropped in favor of native partitioning once it was confirmed unsupported on AWS RDS/Aurora; see §4). |
| **Redis** | Celery broker + result backend (Python only; unused by Java). |
| **File storage** | Django `default_storage`, currently `FileSystemStorage` on a Docker volume (`/app/media`). Uploaded raw files live at `uploads/{athlete_id}/{upload_id}_{filename}`. |
| **JWT signing keys** | RSA keypair generated on first container boot by `entrypoint.sh`, written to a Docker volume (`/app/keys`), shared read-only by the Celery worker (which waits on a `.ready` sentinel file). Not currently a secret-management pattern — it's "generate once, persist on disk." |
| **Frontend** | React 19 + TypeScript + Vite SPA. No Dockerfile — it's a pure static build (`npm run build` → `dist/`), served via `vite preview`/`vite dev` locally. Nothing server-side. |
| **CI** | GitHub Actions: separate lint/unit/integration workflows per backend (path-filtered so both stay required checks without blocking unrelated PRs), CodeQL, frontend typecheck/lint/test/build. Java integration tests spin up Testcontainers Postgres; Python integration tests use a `services:` Postgres container. |
| **External integrations** | OAuth "social" providers referenced in the schema (`strava`, `google`, `apple`) and outbound webhooks to third-party URLs (signed HMAC, retried). No inbound webhook receivers from Strava/Garmin found in the current code — import is user-initiated file upload, not a push API integration (relevant later: no need for a public webhook receiver endpoint from those providers yet). |

Two backends are maintained in strict parity today (shared `openapi.yaml`,
`SCHEMA_COMPARISON.md`) — that's a deliberate choice already made upstream of
this document, and the plan below treats "run both" and "pick one" as two
real options rather than assuming either.

## 2. Service-by-service AWS mapping

| Local component | AWS equivalent | Notes |
|---|---|---|
| `backend` (Django, gunicorn) | ECS Fargate service (or EKS) behind ALB | Stateless, horizontally scalable as-is. |
| `celery-worker` | ECS Fargate service, separate task definition, no ALB target | Scales independently of the API; see §5 for the Lambda alternative. |
| `backend_java` | ECS Fargate service behind ALB | No worker service needed — ingestion runs in-process. |
| Postgres | **RDS for PostgreSQL**, with the app's own native table partitioning (not a Timescale extension — unsupported on RDS/Aurora, see §4) | Same connection string shape, same migrations as local dev. |
| Redis | ElastiCache for Redis (Python only) | Only needed if Celery-on-containers is kept; drop entirely if ingestion moves to Lambda+SQS (§5). |
| `media` volume | S3 bucket (`cadence-uploads-{env}`), private, SSE-S3 or SSE-KMS | Requires a small code change — see §5.3. |
| JWT keypair volume | Secrets Manager (private key) + either Secrets Manager or a public config value (public key/JWKS) | Generate once via a one-off script or CDK custom resource, not at container boot. |
| `.env` | Secrets Manager (secrets: DB password, `DJANGO_SECRET_KEY`, OAuth client secret) + SSM Parameter Store (non-secret config: allowed hosts, CORS origins) | Injected as ECS task-definition secrets, not baked into the image. |
| Frontend static build | S3 (private bucket) + CloudFront (OAC), or Amplify Hosting | No compute needed — it's a static SPA. CloudFront also gives you the CDN/TLS/edge-cache layer for free. |
| Docker images | ECR (one repo per backend) | GitHub Actions builds and pushes on merge to `main`. |
| Local `docker-compose` orchestration | ECS service definitions (or a single `docker compose` → `ecs compose` translation as a bootstrap, not long-term) | |
| DNS | Route 53 | `JWT_ISSUER` is already `https://api.cadence.cc` — suggests a real domain is already assumed; confirm ownership before provisioning. |
| TLS | ACM certificate on the ALB/CloudFront | |
| WAF | AWS WAF on ALB/CloudFront (rate-limiting, managed rule groups) | Worth it before this is public — file upload endpoints are a natural abuse target. |
| CI/CD | GitHub Actions (kept) → OIDC role assumption → ECR push → ECS deploy | No need to introduce CodePipeline; GitHub Actions already does everything and OIDC avoids long-lived AWS keys in CI. |
| Logs | CloudWatch Logs (via `awslogs` driver on the Fargate task def) | |
| Metrics/tracing | CloudWatch Container Insights + (optionally) X-Ray or OpenTelemetry → CloudWatch/Grafana | |
| Backups | RDS automated snapshots + point-in-time recovery; S3 versioning on the uploads bucket | |

## 3. Python backend vs Java backend in AWS

Both can run identically on ECS Fargate — this isn't a "one is impossible"
question, it's a cost/ops-shape question. Assuming the intent is eventually
to run **one** of them as the production backend (running both forever
doubles infra cost and on-call surface for no user-facing benefit):

| | Python/Django | Java/Spring Boot |
|---|---|---|
| **Fargate task footprint** | Smaller baseline (gunicorn + Django is comfortable at 0.25–0.5 vCPU / 512MB–1GB per task for this app's likely load). | JVM baseline memory is higher even idle (typically 1–2GB minimum to avoid GC pressure); with virtual threads (Java 24) it handles concurrency well, but you're paying for a heavier floor. |
| **Cold start / scale-out latency** | Fast — gunicorn workers come up in ~1–2s. Matters for ECS scale-out events and for any future serverless (Lambda) use. | Slower JVM startup (~5–15s typical for Spring Boot even with CDS/AOT tuning), which means slower ECS scale-out reaction and a worse fit for Lambda. |
| **Async/background work** | Needs Redis + a separate Celery worker service — **two more moving pieces** (ElastiCache cluster, a second ECS service, broker failure modes) to run and pay for. | Runs upload ingestion in-process via Spring Batch — **no queue, no worker service, no Redis**. Simpler AWS footprint for this specific piece. |
| **Steady-state compute cost** | Lower, roughly proportional to the smaller memory/CPU floor. | Higher per-task, partially offset by not needing a worker fleet. |
| **Throughput under sustained load** | Good for I/O-bound REST + Celery-offloaded CPU work; GIL is a non-issue since parsing happens in worker processes, not request threads. | JVM + virtual threads generally out-scales a single gunicorn process per vCPU for high-concurrency request handling, if that ever becomes the bottleneck. |
| **Ecosystem maturity for this app's actual needs** (FIT parsing, OAuth, JWT, ORM/migrations) | `fitparse`, `gpxpy`, DRF, `django-oauth-toolkit` — all mature, all already in use and tested (386 passing tests). | Garmin's official `com.garmin:fit` SDK is used directly, Spring Security/OAuth2 authorization server — also mature and already tested. |
| **Team familiarity / iteration speed** | This session's history shows most recent feature work (inference engine, best-efforts fixes, activity delete, etc.) landing Python-first, Java second — suggests Python is currently the faster-iteration side in practice. | Ported second each time — currently trailing, not leading, in this codebase's actual workflow. |
| **Operational surface if you pick one and drop the other** | Drop ElastiCache + worker service if Java is dropped instead — no, wait: dropping Java keeps the *Python* worker/Redis need. See below. | Dropping Python removes Redis/ElastiCache and the worker service entirely — the leaner AWS bill of the two end states. |

**Net take**: Python has the lighter compute footprint per request, but Java
has the lighter *infrastructure* footprint (no broker, no worker fleet) for
this specific app because ingestion is already synchronous-in-process there.
If forced to pick one for long-term AWS cost/ops simplicity and neither
codebase is meaningfully ahead in features, **Java's simpler infra shape
(one service, no Redis, no worker fleet) is the cheaper and lower-toil AWS
deployment**, at the cost of a heavier per-task memory footprint and slower
scale-out. Python's advantage is faster iteration (borne out by this
project's own history) and a cheaper compute floor — that matters more if
feature velocity is still the bottleneck, less once the app is stable and
running-cost is what matters.

This is ultimately a product/team call, not a technical one — see the open
question in §11.

## 4. Database: native partitioning on RDS (corrected 2026-08-15 — see below)

**This section originally recommended "RDS + the community `timescaledb`
extension" as option 1. That recommendation was wrong** — verified directly
against AWS's own documentation while starting the actual RDS provisioning
step (RDS parameter group `shared_preload_libraries` allowed values, the RDS
PostgreSQL extensions overview, the authoritative RDS PostgreSQL Release
Notes extension list, and Aurora PostgreSQL's equivalent list): `timescaledb`
is not supported on **any** AWS-managed PostgreSQL — not standard RDS, not
Aurora, any version. It simply isn't in the binary AWS ships; this isn't a
permissions restriction (`rds.allowed_extensions`) that can be worked
around.

The `activities_record`/`record` hypertable was the one place either backend
depended on Timescale being present at all — a full codebase audit found no
`time_bucket()`, no compression policies, no retention policies, no
continuous aggregates anywhere in either backend. The fix: **native Postgres
declarative `RANGE` partitioning on `ts`**, dropped in as its own PR
(`drop-timescaledb-native-partitioning`) before this AWS migration's Step 3
resumed — see `infra/README.md` for the full build log of that decision, and
that PR's migrations (`backend/activities/migrations/
0003_record_native_partitioning.py`, `backend_java/src/main/resources/db/
migration/V10__records.sql`+`V11__record_partitions.sql`) for the actual
implementation: monthly partitions, a `DEFAULT` catch-all partition as a
safety net, and a small scheduled task in each backend
(`activities/tasks.py`'s `ensure_record_partitions` / Java's
`PartitionMaintenanceService`) that keeps rolling the forward edge of the
partition range ahead over time, since Postgres has no automatic partition
creation the way Timescale does.

Net effect for this section's original three-option framing: option 1 (RDS)
is still correct — RDS remains the right database service — just with native
partitioning instead of the Timescale extension, which needed no code that
wasn't already being written for the AWS move anyway. Options 2 (Timescale
Cloud) and 3 (self-managed EC2) are no longer necessary — both existed
specifically to keep the Timescale extension available, which the app no
longer depends on. Plain **RDS for PostgreSQL, with the app's own native
partitioning** gets every operational win those two options exist for
elsewhere in this plan (automated backups, PITR, Multi-AZ failover, managed
patching) with no extra vendor relationship and no self-managed operational
burden.

### 4.1 Underlying storage volume

RDS storage is EBS-backed under the hood — the concrete choice is **gp3
(General Purpose SSD) with storage autoscaling enabled**, not Provisioned
IOPS (io2):

- **Why gp3, not io2**: the app's workload is small OLTP reads/writes with
  occasional bulk-insert bursts (a FIT upload writes thousands of 1Hz
  `activities_record` rows at once; a Garmin batch import multiplies that
  across files). gp3's baseline (3,000 IOPS / 125 MB/s, both provisionable
  higher **independent of volume size**, unlike the older gp2) comfortably
  covers this. io2's higher per-GB and per-IOPS cost only pays off at
  sustained high-IOPS workloads this app doesn't have — start on gp3,
  watch `ReadIOPS`/`WriteIOPS`/`DiskQueueDepth` in CloudWatch, only move to
  io2 if those actually pressure the baseline.
- **Why autoscaling matters more than initial sizing here**: `activities_record`
  is a 1Hz time-series table that only grows — every second of every
  uploaded activity, indefinitely, with no retention or compression policy
  in the current migrations (§4 already notes no compression policies
  exist today). Picking a fixed volume size up front means someone has to
  notice and manually grow it before it fills; **RDS Storage Autoscaling**
  (set a max threshold, RDS grows the volume automatically as it
  approaches the current limit) turns that into a non-event. Pair it with
  a CloudWatch alarm on free storage space as a sanity check, not as the
  primary mechanism.
- **The actual long-term lever is data volume, not disk type**: if
  stream-data growth ever becomes a real cost driver, the fix is a
  retention policy — dropping (or archiving to S3) whole old partitions past
  a certain age, which native partitioning makes cheap (`DROP TABLE
  activities_record_p202601` on a partition is instant, unlike deleting the
  equivalent rows one by one) — not a bigger or faster disk.
- **Backups are separate**: RDS automated snapshots land in S3, managed by
  AWS as part of the RDS service — not a volume you provision or size
  yourself.

## 5. Ingestion: should FIT/GPX/TCX processing move to Lambda?

### 5.1 What "moving to Lambda" would look like

Event-driven pattern: client uploads directly to S3 (presigned URL) or via
the API which streams to S3 → S3 `ObjectCreated` event → SQS queue → Lambda
consumes, parses the file (`fitparse`/`gpxpy`/`lxml`, same libraries used
today), writes activity/lap/record rows to RDS, fires the same
`activity.created` webhook event. Batch imports (`.zip` of many files) would
fan out to one Lambda invocation per file via SQS rather than one Celery
task per file as today.

### 5.2 Trade-offs

| | Celery-on-Fargate (current shape) | Lambda |
|---|---|---|
| **Idle cost** | Worker task runs 24/7 regardless of upload volume — you're paying for idle capacity between uploads. | Pay only per invocation + duration. For a training-log app, upload traffic is bursty (batch Garmin exports, occasional single uploads) with long idle stretches — this is close to Lambda's ideal use case. |
| **Scaling burst imports** | A large batch (e.g. a full Garmin export .zip) queues up behind however many worker processes/tasks you've provisioned; scaling out means adding Fargate tasks, which isn't instant. | Scales concurrent file-parses automatically; a several-hundred-file batch finishes in roughly the time one file takes, not N× one file. Note the app's own batch cap is up to 50,000 files (`MAX_BATCH_FILES` in `uploads/services.py`) — a batch anywhere near that size would hit the **default Lambda account concurrency limit (1,000 concurrent executions)**, which is a soft limit AWS will raise on request, worth flagging as a setup step rather than a blocker. |
| **Cold starts** | N/A — long-running process. | Python Lambda cold start is small (typically sub-second to ~1-2s with the dependency set here — `fitparse`/`gpxpy`/`lxml` aren't huge). Provisioned concurrency can remove this entirely if it matters, at a cost. |
| **Execution time limit** | Unbounded (worker just keeps running). | 15-minute hard cap per invocation. A single FIT/GPX/TCX file parse is milliseconds-to-low-seconds today — not a real constraint here unless a single file gets pathologically large. |
| **Code reuse** | `uploads/processing.py`'s `ingest_upload()` already isolates parsing from the Celery/Django-request plumbing around it. | Same function is largely reusable — the main rewrite is the entry point (Lambda handler instead of `@shared_task`) and, critically, the DB access pattern (see below). |
| **Operational surface removed** | Keeps Redis/ElastiCache + a worker ECS service. | Removes both — SQS + Lambda replaces Redis-as-broker and the worker fleet entirely for this one workload. Webhook delivery (`deliver_webhook`) is a similarly good separate Lambda-on-SQS candidate for the same reasons. |
| **DB connection handling** | Long-lived worker process, normal connection pooling. | Lambda's connection-per-invocation pattern needs **RDS Proxy** (or a pool like `pgbouncer` sitting in front of RDS) to avoid exhausting Postgres's max-connections under concurrent invocation bursts — an extra piece to add, not a blocker. |
| **Local dev parity** | Identical to prod (same `celery worker` command). | Diverges from local dev — either keep Celery locally and Lambda in prod (drift risk, two code paths to keep working) or run something like SAM/LocalStack locally to emulate Lambda (added dev-environment complexity). |

### 5.3 A real code gap to fix either way

`uploads/processing.py` calls `default_storage.path(upload.stored_path)`
directly — that's a `FileSystemStorage`-only API; Django's S3 storage
backend (`django-storages`' `S3Boto3Storage`) raises `NotImplementedError`
on `.path()`, since S3 objects don't have a local filesystem path. **This
has to change regardless of whether ingestion stays on Celery or moves to
Lambda**, the moment `media` moves off a local volume onto S3: switch to
reading via `default_storage.open(upload.stored_path)` (a file-like object)
or download-to-tempfile, whichever the FIT/GPX/TCX parsers can accept
without a rewrite (`fitparse`/`gpxpy`/`lxml` all accept file-like objects or
byte streams, so this is a small, contained change — one call site, plus
whatever the parser wrapper functions expect). Flag this as a concrete
pre-migration ticket, not a someday nice-to-have — it'll break silently
(files upload fine, then processing throws) if S3 is wired up before this
fix lands.

### 5.4 Recommendation

**This whole section's case for Lambda is conditioned on keeping the
Python backend.** Every argument in §5.2 — removing idle 24/7 worker cost,
removing Redis/ElastiCache, elastic burst scaling for batch imports — is a
Lambda-vs-Celery-on-Fargate comparison. Java doesn't have the thing Lambda
would be replacing: ingestion already runs in-process inside the API
service (Spring Batch, on a single dedicated virtual thread — see
`ARCHITECTURE.md` §5.2 for why it's deliberately serialized to one file at
a time, not concurrent), with no dedicated worker task idling between
uploads and no Redis broker in the picture at all. So:

- **If Python is the chosen production backend**: move `process_upload` to
  Lambda-on-SQS. The idle-cost and burst-scaling arguments are real and
  specific to this app's usage pattern (bursty batch imports, long idle
  gaps). Move `deliver_webhook` to Lambda-on-SQS too — an even better fit
  (short, bounded HTTP calls, already has retry/backoff logic that maps
  directly onto SQS redrive + DLQ). Combined with dropping Celery, this is
  what gets Redis/ElastiCache off the bill entirely.
- **If Java is the chosen production backend**: don't move ingestion to
  Lambda, but **do** split it off the request-serving fleet — this isn't a
  hypothetical "if it ever becomes a concern." The app already caps batch
  imports at up to **50,000 files / 200MB per `.zip`**
  (`MAX_BATCH_FILES`/`MAX_BATCH_BYTES` in `uploads/services.py`, mirrored
  in Java's `max-batch-files: 50000`), specifically because a full Garmin
  Connect account export — the README names Garmin Connect as a primary
  import source, and `process_upload` already has special-case handling
  for the metadata-stub FITs those exports mix in — can be exactly that
  large. A batch that size runs in-process today, one file at a time on a
  single dedicated virtual thread (a deliberate fix for a real cross-file
  data-corruption bug when jobs ran concurrently — `ARCHITECTURE.md` §5.2
  has the mechanism), meaning that CPU-bound parsing work both takes a
  while to work through *and* shares the same task's heap as whatever else
  it's serving at that moment, including live user requests. The fix is a
  **second ECS service running the same Java image**, invoked via its own
  internal endpoint or a lightweight SQS-consumed-by-ECS queue (not
  Lambda — same JVM runtime, no cold-start penalty, no rewrite), so a large
  Garmin export can scale out its own task count independently of the
  request-serving fleet instead of competing with it — more parallel
  single-worker queues (one per task), which is the only axis this
  particular serialization can be scaled on. Java's JVM cold-start
  penalty on Lambda specifically (seconds, not the sub-second Python figure
  in §5.2) would need SnapStart or GraalVM native-image work to avoid —
  complexity that buys nothing here since ECS-to-ECS decoupling already
  solves the actual problem (contention), as opposed to Lambda's problem
  (idle cost), which Java never had. Webhook delivery has the same answer:
  it's a lighter-weight in-process call today with its own retry handling;
  no separate service needed unless delivery volume grows enough to justify
  one.

Net: §10 Phase 3 (the Lambda ingestion move) is a real, worthwhile phase
**only in the Python-backend branch of §13's open decision #1**. If Java is
picked, Phase 3 is void — Redis/ElastiCache was never provisioned for Java
in the first place (§3), so there's nothing left to remove.

Keep this as a **second-phase** migration step (§10), after the initial
lift-and-shift is live and stable — don't do the Lambda rewrite and the AWS
move in the same change window.

## 6. Secrets and configuration

- **JWT keypairs**: generate once (locally or via a one-time CDK custom
  resource / bootstrap script), store the private key in Secrets Manager,
  inject as an ECS task secret mounted to the path the app already expects
  (`JWT_PRIVATE_KEY_PATH`/`JWT_PUBLIC_KEY_PATH` env vars already exist and
  point at configurable paths — no code change needed, just stop generating
  at container boot). Remove the `entrypoint.sh` first-boot keygen path for
  the AWS image variant, or gate it behind "only if the file doesn't
  already exist" (it already is — confirm this stays inert once the file is
  mounted from Secrets Manager).
- **DB credentials, `DJANGO_SECRET_KEY`, OAuth client secret**: Secrets
  Manager, referenced by ARN in the ECS task definition's `secrets` block —
  never baked into the image or committed.
- **Non-secret config** (`DJANGO_ALLOWED_HOSTS`, `CORS_ALLOWED_ORIGINS`,
  `JWT_ISSUER`/`JWT_AUDIENCE`): SSM Parameter Store, one path prefix per
  environment (`/cadence/staging/...`, `/cadence/prod/...`).
- **Rotation**: RDS credentials can use Secrets Manager's native rotation
  Lambda; JWT keys are a manual/scripted rotation (a key rotation needs a
  `kid`-aware overlap period — `JWT_KID` already exists in config, so
  supporting two active keys during rotation is mostly a matter of the JWKS
  endpoint serving both, not a schema change).

## 7. Networking

- **VPC**: private subnets for RDS, ElastiCache (if kept), and ECS
  tasks; public subnets only for the ALB and a NAT gateway (needed for ECS
  tasks to reach the internet — webhook delivery, OAuth provider callbacks
  to Strava/Google/Apple — without being publicly reachable themselves).
- **ALB**: one per backend (or one ALB with path/host-based routing to both,
  if genuinely running both long-term — cheaper, but couples their release
  cadence to a shared front door).
- **Security groups**: ALB → ECS tasks on the app port only; ECS tasks → RDS
  on 5432 only; ECS tasks → ElastiCache on 6379 only (if kept); nothing
  else ingress. No component needs a public IP except the ALB.
- **WAF**: attach to the ALB (or CloudFront if the API is also fronted by
  it) with AWS Managed Rules (common exploits) plus a rate-based rule on
  the upload endpoints specifically — they're the most abuse-prone surface
  (large file POSTs, unauthenticated-until-token-checked).

## 8. Deployment / CI-CD

Current GitHub Actions setup (path-filtered lint/unit/integration per
backend, CodeQL, frontend build) is a solid foundation — extend it rather
than replace it:

1. **On merge to `main`**: build the changed backend's Docker image
   (reuse the existing `Dockerfile`s as-is), tag with the git SHA, push to
   ECR via a GitHub Actions OIDC role (no long-lived AWS access keys
   stored in GitHub secrets).
2. **Deploy**: `aws ecs update-service --force-new-deployment` with the new
   image tag (or a proper `aws ecs deploy` / CodeDeploy blue-green if
   zero-downtime matters from day one — ECS's built-in rolling deployment
   with a health-check grace period is a reasonable starting point given
   this app has no long-lived connections/websockets to drain carefully).
3. **Migrations**: run as a one-off ECS task (same image, overridden
   command: `python manage.py migrate` / Flyway via the Java jar) as a
   *separate* deploy step before the service update, not inside the
   container's `entrypoint.sh` at every boot — running migrations on every
   task start (current local behavior) is fine for a single dev container
   but is a race condition waiting to happen once you have >1 task
   starting concurrently during a deploy or scale-out event.
4. **Frontend**: `npm run build` → sync `dist/` to the S3 bucket →
   CloudFront invalidation. Fully decoupled from backend deploys — can
   ship independently, matching how frontend/backend already have separate
   CI workflows.
5. **Environments**: at minimum `staging` and `production` as fully
   separate stacks (separate RDS instances, separate ECS clusters/services,
   separate secrets) — never share a database between them. Staging can run
   on smaller instance sizes / single-AZ to control cost (§9).
6. **Infrastructure as code**: define all of the above in Terraform or AWS
   CDK, not click-ops — this is a multi-service, multi-environment stack
   and hand-provisioned infra will drift and become undocumented within a
   few months. CDK (Python or TypeScript) has the advantage of being closer
   to the team's existing languages than raw Terraform HCL, if that
   matters; either is fine as a technical choice.

## 9. Observability

- **Logs**: `awslogs` driver on every ECS task definition → CloudWatch Log
  Groups, one per service, with a retention policy set explicitly (default
  is "never expire," which quietly becomes a cost line).
- **Metrics**: ECS Container Insights (CPU/memory/task count) plus
  application-level metrics — request latency/error rate per endpoint are
  worth emitting explicitly (structured logs + a CloudWatch metric filter,
  or an EMF-formatted log line) since the JQL-style `/v1/activities` query
  endpoint and the FIT-parsing path are the two most likely sources of
  latency/error surprises.
- **Alarms**: RDS CPU/storage/connections, ECS service task-count-below-
  desired, ALB 5xx rate, SQS DLQ depth (once Lambda ingestion lands, §5) —
  wire to SNS → email/Slack.
- **Tracing**: optional at this scale, but if added, AWS X-Ray (or
  OpenTelemetry → CloudWatch) across API → DB and API → Lambda-ingestion
  would make the "why did this one upload take 40 seconds" class of
  question answerable without log spelunking.

## 10. Phased migration plan

Do this in phases — don't attempt the AWS move and the Lambda ingestion
rewrite and the backend-consolidation decision all at once. Each phase
should be independently shippable and rollback-able.

**Phase 0 — pre-migration code fixes** (do in the current repo, before
touching AWS):
- Fix `default_storage.path()` in `uploads/processing.py` (§5.3) — required
  before S3-backed media works at all.
- Move migrations out of `entrypoint.sh`'s always-run-at-boot path into an
  explicit deploy step (§8.3) — required before running >1 task safely.
- Decide and implement the JWT-key-from-Secrets-Manager path, gating the
  `entrypoint.sh` keygen behind "only if missing."

**Phase 1 — lift-and-shift** (prove the stack works in AWS with minimal
behavior change):
- Provision VPC, RDS (native partitioning, no extension needed), ElastiCache (if
  Celery is kept for now), ECR, ECS cluster, ALB, S3+CloudFront for the
  frontend, Secrets Manager/SSM entries — via IaC.
- Deploy exactly what runs locally today, containers unchanged apart from
  Phase 0 fixes, media on S3, Celery/worker still on ECS (not yet Lambda).
- Point a staging DNS name at it, run the full manual smoke pass (upload →
  processing → analysis screens → best efforts → export) plus the existing
  automated test suites against the deployed staging stack.

**Phase 2 — cutover**:
- DNS cutover from local/dev to the production AWS stack for real users
  (if any exist yet — worth confirming, see §11).
- Decommission local-only infra once production is stable for an agreed
  bake period.

**Phase 3 — ingestion modernization** (do once Phase 1/2 are boring and
stable; shape depends on §13 decision 1 — see §5.4):
- **Python branch**: move `process_upload` and `deliver_webhook` to
  Lambda-on-SQS, drop the Celery worker service, drop ElastiCache. Add RDS
  Proxy in front of RDS for the Lambda connection pattern.
- **Java branch**: split ingestion out of the request-serving ECS service
  into its own ECS service (same image, same in-process Spring Batch code
  path — no Lambda, no rewrite), so a large Garmin Connect batch export
  (up to the app's own 50,000-file batch limit) scales its own task count
  independently instead of contending with live API traffic on the same
  task.

**Phase 4 — backend consolidation** (optional, product decision — §11):
- If/when the team decides to run one backend long-term, decommission the
  other's AWS resources (and, separately/later, its code — out of scope for
  this document).

## 11. Cost estimate (rough order of magnitude)

These are **rough monthly estimates** for a small-scale production
deployment (single region, low-to-moderate traffic — a handful to low
hundreds of athletes, not a public consumer launch) using on-demand
pricing, US-East-1-ish rates as of this writing. Treat as a planning
input, not a quote — run the real numbers through the AWS Pricing
Calculator once instance sizes are chosen, and note these will shift with
actual traffic/data volume.

| Item | Staging (minimal) | Production (small) |
|---|---|---|
| RDS Postgres (single instance, Multi-AZ in prod only) | `db.t4g.micro`, single-AZ: ~$15–25/mo | `db.t4g.medium`, Multi-AZ: ~$140–180/mo |
| ECS Fargate — Python API (0.5 vCPU/1GB, 1 task staging / 2 tasks prod) | ~$15/mo | ~$60–70/mo |
| ECS Fargate — Java API (1 vCPU/2GB, if run in parallel) | ~$30/mo | ~$120–140/mo |
| ECS Fargate — Celery worker (0.5 vCPU/1GB, if kept vs Lambda) | ~$15/mo | ~$30–35/mo |
| ElastiCache Redis (`cache.t4g.micro`, if Celery kept) | ~$12/mo | ~$25/mo (add a replica for HA) |
| ALB(s) | ~$16/mo base + LCU | ~$20–30/mo |
| NAT Gateway | ~$33/mo + data processing | ~$33/mo + data processing |
| S3 (uploads + frontend build, low volume) | <$5/mo | $5–20/mo, scales with stream-data volume |
| CloudFront | <$5/mo at low traffic | $5–15/mo |
| Secrets Manager (~6 secrets) | ~$2.5/mo | ~$2.5/mo |
| CloudWatch Logs/metrics | ~$5/mo | $10–30/mo (retention-dependent) |
| Route 53 hosted zone + queries | ~$1/mo | ~$1–2/mo |
| **Rough total (running BOTH backends)** | **~$150–180/mo** | **~$450–550/mo** |
| **Rough total (ONE backend only)** | **~$100–130/mo** | **~$280–380/mo** |

Biggest cost levers, in order: (1) whether both backends run in parallel
long-term (§3/§11 open question) — this roughly doubles compute; (2)
Multi-AZ RDS in production (worth it for a real user base, skip it for
staging); (3) if Python is the chosen backend, whether Celery/ElastiCache
is kept vs the Lambda move in Phase 3, which removes a fixed monthly cost
in favor of pay-per-use — moot if Java is chosen, since it never needed
Celery/ElastiCache in the first place; (4)
NAT Gateway is a fixed cost regardless of traffic — a NAT instance or VPC
endpoints for AWS-service traffic (S3, Secrets Manager) can trim this
if it matters at this scale.

### 11.1 Cost per user

Measured directly from the local Java stack's real data (`testuser1783507448@example.com`,
a genuinely-migrated Garmin Connect account: 2,609 activities from 2020-08-04
to 2026-07-28, ≈63.5 min average activity), rather than assumed:

- The `record` hypertable (1Hz stream data) is **2,674 MB for 9,912,650 rows
  DB-wide → ≈282.9 bytes/row**, a schema-level constant, not user-specific.
- This one account's 9,230,146 rows work out to **≈2.6 GB** — and that's
  ~99.8% of its own footprint; every other table combined (`lap`,
  `duration_curve`, `activity`, `best_effort`) is under 6 MB DB-wide across
  *all* accounts. Stream data is the entire storage story.
- Average **≈1.0 MB of stream data per activity**.

Projected forward at the stated **2 activities/day**: +2.0 MB/day → **+730
MB/year** per user. On RDS gp3 (≈$0.115/GB-month, us-east-1 — confirm
current rate): today's 2.6 GB ≈ **$0.30/month**; even five more years at
this pace (→~6.3 GB) is only **≈$0.72/month**. Compute is the same story —
a FIT parse is sub-second of CPU, so at 2/day the marginal compute cost per
user is well under a cent a month regardless of whether it runs on
already-provisioned Fargate capacity or billed per-Lambda-invocation.

**Conclusion: per-user storage/compute cost is a rounding error at this
usage rate.** "Cost per user" is really the §11 fixed infrastructure floor
(RDS instance, ECS baseline tasks, ALB, NAT Gateway — all roughly flat
until a real scaling threshold is hit) divided by however many users share
it:

| Users | Cost/user/month (one-backend production, ~$280–380/mo floor) |
|---|---|
| 10 | ~$28–38 |
| 100 | ~$2.80–3.80 |
| 1,000 | ~$0.28–0.38 — data volume starts to become a real fraction here |
| 10,000 | ~$0.03–0.04 — the fixed floor no longer dominates; RDS tier size and stream-data volume start to matter more than instance count |

The practical implication: don't right-size infrastructure around per-user
data volume at this app's usage pattern — right-size it around how many
users are sharing the fixed floor, and revisit only once user count is
large enough that the floor stops dominating (rough rule of thumb from the
table above: somewhere past a few thousand active users).

## 12. Other things worth deciding now, not discovering later

- **Data residency / backups**: confirm RDS automated backup retention
  (7–35 days) and whether cross-region backup replication is needed — not
  currently a stated requirement, but cheap to decide up front.
- **File retention for uploads**: the "Danger zone → Remove all activities"
  feature already keeps unlinked uploaded files around for re-import
  (per the README) — decide an S3 lifecycle policy (e.g. transition
  unlinked-but-undeleted uploads to Infrequent Access or Glacier after N
  days) so this doesn't become an unbounded-growth cost surprise. Separately,
  if 1Hz stream-data volume grows large enough to matter, dropping or
  archiving old `activities_record`/`record` partitions (§4.1) is a later
  lever — only relevant at a scale this app isn't at yet.
- **Multi-tenancy/scale ceiling**: nothing in the current schema or infra
  plan assumes single-tenant isolation beyond row-level `athlete_id`
  scoping — fine at this scale, but if "coach with many athletes" usage
  grows significantly, revisit whether a single shared RDS instance is
  still the right shape before it becomes a hot-instance problem.
- **Zero-downtime migrations**: Django and Flyway migrations both run
  auto-generated forward-only SQL; nothing currently enforces
  backward-compatible migration ordering (expand/contract) for zero-
  downtime deploys. Not a blocker for a first AWS deployment, but worth a
  team convention once rolling ECS deploys are the norm (a migration that
  drops/renames a column a still-running old task version depends on will
  cause real errors during the rollout window).
- **Compliance**: nothing in the current codebase suggests specific
  regulatory scope (no health-data-specific claims, no explicit PII
  classification beyond normal account data), but confirm before launch
  whether anything here (weight, HR data) needs to be treated as sensitive
  under a specific framework in the target market — that could add KMS
  encryption requirements, audit logging, or data-deletion SLAs beyond
  what's here.
- **Secrets in the repo today**: `cadence_pw` sits untracked in the repo
  root — confirm it's genuinely excluded from anything that gets built
  into a Docker image or committed, before this migration puts any of this
  in front of a wider audience.

## 13. Open decisions (need a human call, not an engineering default)

1. **Run one backend or both in production?** This is the single biggest
   lever on both cost (§11) and ongoing engineering toil (parity
   maintenance × two, double the on-call surface). §3 leans toward Java
   for infra simplicity if forced to pick, but Python's faster iteration
   speed may matter more if the app is still actively growing features.
2. ~~RDS+extension vs Timescale Cloud~~ **Resolved 2026-08-15**: moot — the
   `timescaledb` extension isn't supported on RDS/Aurora at all (§4), so the
   app moved to native Postgres partitioning instead. No longer an open
   decision.
3. **Timeline for Phase 3's ingestion split** (§5/§10 Phase 3) — shape
   depends on decision 1: Lambda-on-SQS if Python, a second same-image ECS
   service if Java (both driven by the same real concern — a Garmin
   Connect export can be up to the app's own 50,000-file batch cap).
   Either way: bundle into the initial migration, or deliberately defer
   until the lift-and-shift is proven stable? Recommendation is to defer.
4. **Is there a real user base to cut over already**, or is this
   pre-launch? Changes whether Phase 2's DNS cutover has a bake-period/
   rollback plan that matters for real people, or is just "flip it."
5. **Target AWS region(s)** and whether multi-region DR is a real
   requirement or aspirational — everything above assumes single-region.
