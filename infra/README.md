# AWS migration - build log

A step-by-step record of migrating Cadence to AWS, kept as we go. See
`AWS_MIGRATION_PLAN.md` (repo root) for the full architectural analysis this
is executing against - this file is the narrower "what we actually did and
why" log, including the small real-world snags (expired sessions, disk
space, credential plumbing) that the plan document doesn't cover. See
`NETWORK_ARCHITECTURE.md` for the target network diagram and traffic flow.

## Decisions made

| Decision | Choice | Why |
|---|---|---|
| Production backend | Java (Spring Boot) | Simpler AWS footprint - no Redis/Celery worker fleet, ingestion runs in-process. See `AWS_MIGRATION_PLAN.md` §3. |
| Launch status | Pre-launch | No real users yet - DNS cutover can just be a flip, no bake period needed. |
| AWS region | `eu-west-2` (London) | Already the account's configured default region. |
| Domain | `cadence.bioinform.co.uk` (frontend), `api.cadence.bioinform.co.uk` (API) | The account's only Route 53 hosted zone is `bioinform.co.uk` - the `cadence.cc` in the code's `JWT_ISSUER` default is just a placeholder, not a domain actually owned in this account. |
| IaC tool | Terraform | Team already uses it at work; this doubles as a learning exercise. |
| Environments | One to start (`staging`) | Learn on a low-stakes environment first; copy the proven pattern to add `production` later once comfortable. |
| Database | ~~RDS PostgreSQL + community `timescaledb` extension~~ **RDS PostgreSQL + native declarative partitioning** | Plan §4's recommendation is **factually wrong** - verified against AWS's own docs (RDS parameter group `shared_preload_libraries` allowed values, the RDS PostgreSQL extensions overview, the authoritative RDS PostgreSQL Release Notes extension list, and Aurora PostgreSQL's equivalent list): `timescaledb` isn't supported on any AWS-managed PostgreSQL, standard RDS or Aurora, any version. Decided 2026-08-15: replace the one hypertable (`activities_record`) with native Postgres range partitioning instead, dropping TimescaleDB everywhere (local dev image too, for parity - see the reasoning below). Rejected alternatives: self-managed TimescaleDB on EC2 (ongoing patch/backup/HA burden), Timescale Cloud (separate vendor bill) - both real options, but native partitioning was preferred since the app's actual Timescale usage is already minimal (confirmed: no compression policies, no continuous aggregates in the current migrations - just the partitioning itself). |
| Native-partitioning migration sequencing | **Own PR, before resuming AWS database work** | User will do a full data export of the local dev DB first (low-stakes since this is pre-launch/test-account data, but a real hassle to re-import ~2,600 activities/9M+ rows by hand if skipped) - see `AWS_MIGRATION_PLAN.md`'s upcoming §4 correction for the actual migration mechanics (create the new partitioned table, backfill-copy every row, swap/rename, drop the old hypertable). Step 3 (RDS) below is **blocked** until this PR lands. |
| RDS never has a public IP or internet route | Confirmed | Matches the plan's networking design (§7) - see `NETWORK_ARCHITECTURE.md`. |
| Ad-hoc DB access (debugging/admin queries) | SSM Session Manager port-forwarding, not AWS Client VPN | Client VPN has a fixed ~$72/mo cost (confirmed: $0.10/hr per subnet association, billed whether or not anyone's connected) plus certificate-authority setup, for "always on network access" this solo/low-frequency use case doesn't need. SSM port-forwarding (via a stoppable bastion or `ecs execute-command` into a running task) is IAM-controlled, per-session, and has near-zero idle cost. To be built alongside Step 3's RDS setup. |
| NAT Gateway count | Single, in every environment including production (not just staging) | ~$33/mo saved per environment, ongoing - not a staging-only shortcut. Tradeoff: both AZs' private subnets share one AZ's outbound path. Upgrade path (one-line toggle, purely additive, no downtime) documented in `NETWORK_ARCHITECTURE.md`'s "Upgrading to per-AZ NAT Gateways". |
| First-admin bootstrap on Java in AWS | SSM bastion + direct `UPDATE users SET is_admin = true` | Django has `grant_admin` (a documented management command); Java, the chosen production backend, has no equivalent CLI mechanism. Building it just for a one-time bootstrap wasn't worth the effort - the SSM bastion (built for general ad-hoc DB access anyway) solves it directly. Worth adding a real `grant_admin`-equivalent to the Java side eventually, not urgent. |
| `/app/media` on Fargate | EFS mount, not S3 | Found while writing the ECS task definition: the Java backend has **no S3 storage code at all** - `UploadIngestService`/`ExportService`/`ImportController` all resolve plain local filesystem paths via `CadenceProperties.uploads().mediaRoot()`, unlike `AWS_MIGRATION_PLAN.md`'s Phase 1 assumption. On Fargate's ephemeral disk that would silently lose uploaded activity files and pending export/import artifacts on every deploy/restart. EFS needs zero app code changes (still a POSIX path) and unblocks ECS today; a real S3-backed storage abstraction can still land later as its own PR. |

## Layout

```
infra/terraform/
  bootstrap/     # one-off, LOCAL state - creates the S3 bucket everything
                 # else's state lives in. Apply once, rarely touch again.
  envs/staging/  # the staging environment's root module - what you actually
                 # `terraform apply` day to day.
  modules/       # reusable building blocks (vpc, rds, ecs, ...) that
                 # envs/staging (and later envs/production) call into.
```

## Step 1 - Terraform state backend

**What & why:** Terraform tracks every resource it manages in a state file -
a JSON mapping from `.tf` resource blocks to real AWS resource IDs. Left as
a local file it can't be shared or locked safely (two concurrent `apply`
runs can corrupt it) and isn't backed up. Fix: store it in S3 (versioned,
encrypted), with locking so concurrent runs queue instead of colliding.

There's a bootstrapping problem - Terraform needs a bucket to store state
*in*, but that bucket has to be created by something. `bootstrap/main.tf`
is a small one-off config using local state (the one exception in this
whole project) that creates just that bucket, versioning, SSE-KMS
encryption, and a public-access block.

**Locking:** as of Terraform 1.11+, the S3 backend supports native locking
(`use_lockfile = true`) via S3 conditional writes - no DynamoDB table
needed (the older pattern). We're on Terraform 1.15.8, so every real
backend config in `envs/*` will use this.

**What got created** (`cadence-terraform-state-423351912929`, applied
2026-08-14): the bucket, versioning enabled, SSE-KMS (AWS-managed `aws/s3`
key, no custom KMS key needed), all public access blocked. Verify in the
console: **S3 → Buckets → `cadence-terraform-state-423351912929`** →
Properties tab for versioning/encryption, Permissions tab for the
public-access block.

**Snags hit along the way** (kept here since they're the kind of thing that
wastes an hour if undocumented):
- `terraform init` failed with "no space left on device" - the Mac's data
  volume was down to 269MB free. Freed ~4GB via `docker builder prune -af`
  and `docker image prune -af` (safe - only unused build cache and images,
  no running containers or volume data touched).
- `terraform plan` failed with "No valid credential sources found" /
  an EC2 IMDS timeout, even though `aws sts get-caller-identity --profile
  neil` worked fine via the CLI directly. Cause: the `aws login`
  browser-based credential flow writes a `login_session` key into
  `~/.aws/config` that only the AWS CLI's own credential resolution
  understands - Terraform's AWS provider (a separate Go SDK) doesn't
  recognize it and falls through to looking for an EC2 instance role.
  Fix: added `credential_process = aws configure export-credentials
  --profile neil` to the `[profile neil]` block in `~/.aws/config` - a
  standard mechanism every AWS SDK understands, which re-exports whatever
  credentials the CLI already resolved in the format `credential_process`
  expects. No secret material needs to pass through anything but the two
  `aws` CLI processes talking to each other.
- The interactive "type yes to approve" prompt doesn't work through this
  session's non-interactive shell execution path - `terraform apply`
  needs `-auto-approve` here instead (only after reviewing `plan`'s output
  first, never blind).

## Step 2 - Networking (VPC)

**What & why:** see `NETWORK_ARCHITECTURE.md` for the full diagram, traffic
flow, and component-by-component rationale (styled after AWS's own
Prescriptive Guidance docs). Short version: a dedicated VPC (not the
account's default), 2 Availability Zones, public subnets for the ALB/NAT
Gateway, private subnets for ECS/RDS, one NAT Gateway (staging: cost over
redundancy).

`modules/vpc/` is the reusable building block; `envs/staging/main.tf` calls
it with `environment = "staging"`, `single_nat_gateway = true`. `terraform
plan` reviewed: 18 resources to create (1 VPC, 1 IGW, 4 subnets, 1 NAT
Gateway + EIP, 3 route tables, 3 routes, 6 route table associations), 0
changed, 0 destroyed - matches the target diagram exactly.

**Applied 2026-08-15**: `vpc-000f87250add86796`, 4 subnets
(`subnet-00cf0b2fcf858719a`/`subnet-0117996c558413f3a` public,
`subnet-08e1c2bc8109e6827`/`subnet-0cc7d9ea047abc07d` private), NAT Gateway
`nat-0c7d5201969261592` (took ~1m24s to reach "Available" - normal for NAT
Gateway creation). All 18 resources matched the plan exactly. (Applying
this needed Step 2.5 below first - `terraform init` for this config hit the
same credential issue Step 1 did.)

Console check: **VPC → Your VPCs** → `cadence-staging-vpc`; **VPC →
Subnets** for the 4 subnets; **VPC → NAT Gateways** → `nat-0c7d5201969261592`.

## Step 2.5 - long-lived Terraform credentials

`aws login`'s browser-based session kept expiring mid-work (its refresh
token appears short-lived, and there's no `--duration` flag to extend it).
For a personal, single-operator learning account, the standard fix is a
dedicated IAM user for Terraform to run as, with a static access key -
simpler than fighting session expiry, at the cost of a long-lived secret
that needs to be treated carefully (never committed, never shared in chat).

- `infra/terraform/bootstrap/iam.tf` creates the `cadence-terraform` IAM
  user (applied via the still-working `neil` session at the time), with
  `PowerUserAccess` + `IAMFullAccess` attached - broader than a hand-scoped
  policy, deliberately: this is a single-owner account with no other
  tenants to isolate from, and a narrower policy would need constant
  iteration as new services get touched through the rest of this build.
  Revisit if this account ever stops being single-owner.
- The access key itself was **not** created via Terraform -
  `aws_iam_access_key` would store the secret value in Terraform state in
  plain text, a well-known leak vector. Created out-of-band instead:
  `aws iam create-access-key` piped straight into `aws configure set` for
  a new `cadence-terraform` CLI profile, so the secret only ever touched
  two `aws` CLI processes talking to each other - never this chat, never a
  file that persists past the one-line pipeline.
- Every `provider "aws" { profile = ... }` block and every `backend "s3" {
  profile = ... }` block (the two are configured independently - easy to
  fix one and forget the other, which is exactly what happened once) now
  points at `cadence-terraform` instead of `neil`.

Console check: **IAM → Users → `cadence-terraform`** → Permissions tab.

## Blocked: dropping TimescaleDB before Step 3

Discovered while starting Step 3 (RDS): `timescaledb` isn't supported on
RDS or Aurora PostgreSQL at all (see the Decisions table above). Rather
than route around it inside this infra build, that's a real schema change
belonging in its own PR against the main app repo - dropping TimescaleDB
from local dev too (same reasoning as keeping the two backends in strict
parity: prod and dev must run the same migrations, or they silently
drift). User is doing a full data export of the local dev DB first as a
safety net before that PR touches `activities_record`.

**Resolved 2026-08-15**: PR #224 merged. Both local dev databases were
wiped and rebuilt on the new native-partitioned schema, then fully
restored via the app's own export/import feature (validating it as a
real DR mechanism, not just a backup that sits unused):

- **Java**: 9,348,679 records, 2,634 activities, 1,003 best efforts.
  0 rows landed in the `DEFAULT` catch-all partition - the 10-year-back/
  6-month-forward range fully covers the real data.
- **Django**: same row counts (9,348,679 records, 2,634 activities),
  1,049 best efforts, also 0 rows in `DEFAULT`. Along the way, cleaned
  up 2 duplicate test accounts created during CORS-related registration
  troubleshooting (`CORS_ALLOWED_ORIGINS` needed the frontend's actual
  dev-server port added - a local-env snag, not a code bug).

**Found one real pre-existing bug while restoring, unrelated to this
migration** - flagging here since it surfaced from actually exercising
the app end-to-end for the first time against a full-scale (~2,634
activity) account: `POST /v1/athletes/{id}/best-efforts/recompute`
(`backend/athletes/views.py`'s `_recompute_stream`) times out against
gunicorn's default 30s sync-worker limit (`backend/Dockerfile`'s `CMD`
has no `--timeout` override) when recomputing a full large account via
the streaming HTTP endpoint - a Python ORM-object-instantiation
throughput limit, not a partitioning/query-plan issue (confirmed:
`backend/athletes/views.py` and the Dockerfile's gunicorn command were
untouched by the TimescaleDB-removal PR). Worked around for this
verification by invoking `_recompute_stream` directly via
`manage.py shell`, bypassing gunicorn entirely. **Fixed properly in a
follow-up PR** (#225, merged) - converted to a Celery job + polling,
same pattern as uploads/exports/imports. Verified against the real
restored account: 2,606/2,606 processed, no timeout.

## Step 3 - RDS

**What & why:** plain RDS PostgreSQL 16, no custom parameter group -
native table partitioning needs no special server config, unlike the
TimescaleDB extension the original plan assumed (see the Decisions
table). `modules/rds/` follows the same pattern as `modules/vpc/`:
`aws_db_subnet_group` spanning the private subnets, a security group
with **zero ingress rules for now** (nothing should reach RDS until
the ECS service exists - that one rule gets added at the root module
level once both this and the ECS module exist), and the
`aws_db_instance` itself.

Key choices: `db.t4g.micro`, single-AZ (`multi_az = false`), gp3
storage with autoscaling (20GB initial → 100GB ceiling, matching
`AWS_MIGRATION_PLAN.md` §4.1's reasoning), `manage_master_user_password
= true` (RDS creates and rotates the master password in Secrets
Manager - the actual value never appears in this Terraform config or
state), `skip_final_snapshot = true` (staging: easy to tear down/
recreate while learning; would flip to `false` + `deletion_protection
= true` for production).

**Applied 2026-08-15**: `cadence-staging.c5ygo26aqlnu.eu-west-2.rds.amazonaws.com:5432`,
status `available`, Postgres 16.14. Took ~6 minutes to provision (normal
for RDS). Console check: **RDS → Databases → `cadence-staging`**
(Configuration tab); **Secrets Manager → Secrets** for the
auto-created master password secret.

## Step 3 - SSM bastion (ad-hoc DB access, moved up)

**What & why:** brought forward from its original place in the plan
(alongside ECS) because a real, concrete need showed up first: with no
self-service way to become the first in-app admin (Django has
`grant_admin`, a documented "one-off bootstrap path, run manually
against each environment" - `backend/accounts/management/commands/
grant_admin.py`; **Java, the chosen production backend, has no
equivalent**), the only way to bootstrap the first admin in AWS is a
direct SQL `UPDATE users SET is_admin = true WHERE email = ...` -
which needs DB access that doesn't require the app to be deployed yet
either.

`modules/bastion/`: one `t4g.micro` EC2 instance (Amazon Linux 2023,
ARM/Graviton - AMI pulled from the public SSM parameter
`/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64`,
not hardcoded), **zero ingress rules** (SSM doesn't need any - the
agent makes an *outbound* connection to AWS Systems Manager, and `aws
ssm start-session` tunnels through that; nothing on the internet can
ever open a connection *to* this instance), IAM role with just
`AmazonSSMManagedInstanceCore` attached. The one new cross-module
wire-up: an `aws_security_group_rule` in `envs/staging/main.tf` adding
the bastion's security group as an allowed source on RDS's - the first
of what will eventually be two such rules (ECS gets its own, later).

**Two real snags, both AWS API charset restrictions** (worth keeping
here since they're not obvious from the error message alone): security
group descriptions only allow `a-zA-Z0-9. _-:/()#,@[]+=&;{}!$*` - no
`→` arrows (hit this in a route description) and **no apostrophes**
(hit this in "SSM doesn't need any" - AWS's error message doesn't
mention apostrophes specifically, it just lists the allowed set, so
it's easy to miss on a quick read). Neither restriction applies to
Terraform's own `description` fields on `variable`/`output` blocks -
those are local documentation, never sent to any AWS API.

**Applied and verified 2026-08-15**: instance `i-073b678e1bcd4b9b8`,
SSM agent online within ~10s of boot. Verified the actual tunnel
end-to-end (not just "resources exist"): `aws ssm start-session
--document-name AWS-StartPortForwardingSessionToRemoteHost` to forward
local port 15432 to the RDS endpoint's 5432, then a raw TCP connect
test (`nc -zv localhost 15432`) confirmed the full path - local
machine → SSM session → bastion → RDS security group → Postgres port.
Deliberately stopped short of an authenticated `psql` connection here:
that needs the RDS master password from Secrets Manager, which should
never be fetched into this kind of session (matches the general
principle in `CLAUDE.md`'s Secret Safety section, even without a
specific secrets-manager skill loaded) - that step is for whoever
actually runs the bootstrap, on their own machine, once there's a real
`users` table to update.

The instance is **stopped by default between uses** (`aws ec2
stop-instances`) - billed only while running, and a `t4g.micro` is
cheap enough (~$6/mo) that leaving it running is also a defensible
choice if the stop/start friction isn't worth it day to day.

Console check: **EC2 → Instances → `cadence-staging-bastion`**;
**Systems Manager → Fleet Manager** to see it listed as a managed
instance once running.

## Step 3 - ECR

**What & why:** one ECR repo per backend (`cadence-backend-java`),
**not per environment** - the same built image (tagged by git SHA)
gets deployed to staging then production, rather than rebuilt per
environment. `image_tag_mutability = "IMMUTABLE"` (a given tag always
points at the same image, no silent overwrites), `scan_on_push = true`
(Well-Architected: catch known CVEs before deploying), and a lifecycle
policy keeping the last 10 tagged images + expiring untagged ones
after 1 day (unbounded image accumulation is a real, silent cost
creep otherwise).

**Applied and image pushed 2026-08-15**:
`423351912929.dkr.ecr.eu-west-2.amazonaws.com/cadence-backend-java`,
tags `latest` and `sha-b2831b1` (matching `main`'s tip at the time).
Built for **ARM64/Graviton** - matches the bastion, ~20% cheaper than
x86 Fargate for equivalent specs, and this is an Apple Silicon Mac so
it builds natively with no emulation.

**Two snags, both worth remembering for the next manual push:**
- `docker buildx build` in recent Docker versions attaches
  provenance/SBOM attestations by default, producing an OCI manifest
  list ECR's registry rejected outright (`400 Bad Request` on the
  manifest commit, no other detail). Fix: `--provenance=false
  --sbom=false` on the build.
- The first (failed) push attempt still partially registered the
  `sha-b2831b1` tag against the attestation-manifest's digest before
  erroring - and `IMMUTABLE` tag mutability then correctly refused to
  let the retry reassign that same tag to the real image's digest.
  Fix: `aws ecr batch-delete-image` on the stale tag, then push again
  clean. (This is the mutability setting doing exactly its job - a
  genuine accidental overwrite would be blocked the same way, which is
  the point.)

Console check: **ECR → Repositories → `cadence-backend-java`**.

## Step 3 continued - JWT keys, OAuth secret, EFS, ALB, ECS

**What & why:** the last pieces to actually run the Java backend.

- **JWT signing keypair**: generated locally with the exact same `openssl
  genrsa` / `pkcs8` / `rsa -pubout` sequence `entrypoint.sh` already uses for
  local dev (PR #228), then uploaded straight into a single Secrets Manager
  secret (`cadence-staging-jwt-keys`, JSON with `jwt_private_key_pem` /
  `jwt_public_key_pem` keys) without the key material ever landing in a file
  I read or a shell variable I could see - `openssl` wrote straight to temp
  files, `jq --rawfile` built the JSON from those files, `aws secretsmanager
  create-secret --secret-string file://...` uploaded it, then the temp dir
  was shredded. Same secret-handling discipline as the RDS master password.
- **OAuth first-party client secret**: `OAUTH_FIRST_PARTY_CLIENT_SECRET` (used
  as the first-party OAuth client's credential, `FirstPartyClientConfig.java`)
  was still on its `dev-only-secret-change-me` default - a real value needed
  generating for staging. The generate+upload one-liner got blocked by this
  session's sandbox classifier (secret creation is understandably sensitive),
  so it was run by hand instead of by me - `cadence-staging-oauth-first-party-client-secret`,
  a plain string secret (not JSON, unlike the JWT one).
- **`/app/media` → EFS, not S3**: see the decisions table above. New `efs`
  module - one filesystem, a mount target per private subnet, an access
  point scoped to `/media` with a fixed POSIX owner (the container runs as
  root, matching the Dockerfile), IAM-authorized access (`iam = "ENABLED"`
  on the volume's `authorization_config`, plus a scoped
  `elasticfilesystem:ClientMount`/`ClientWrite` policy on the ECS task role)
  layered on top of the security-group-level NFS rule, not instead of it.
- **ALB**: new `alb` module - internet-facing, HTTP-only listener on port 80
  for now. Port 443 is open on the security group already so adding the
  HTTPS listener later (once ACM + the `cadence.bioinform.co.uk` Route 53
  record happen) needs no SG change, just the listener resource itself.
  Target group uses `target_type = "ip"` (required for Fargate's `awsvpc`
  networking - there's no EC2 instance to register by ID) and health-checks
  `/healthz`.
- **ECS**: new `ecs` module - Fargate cluster, ARM64/Graviton task definition
  (matches the bastion and the already-pushed image), two IAM roles (execution
  role: ECR pull + CloudWatch Logs + `secretsmanager:GetSecretValue` scoped to
  exactly the 3 secrets used, not a blanket grant; task role: the EFS IAM
  policy above, nothing else - the app makes no other AWS API calls today).
  `POSTGRES_PASSWORD`, `JWT_PRIVATE_KEY_PEM`, `JWT_PUBLIC_KEY_PEM`, and
  `OAUTH_FIRST_PARTY_CLIENT_SECRET` come in as task-definition `secrets`
  (resolved by the execution role before the container starts, never touching
  Terraform state); everything else (`POSTGRES_HOST`/`DB`/`USER`,
  `JWT_KID`/`ISSUER`/`AUDIENCE`, `CORS_ALLOWED_ORIGINS`) is a plain
  `environment` entry. `JWT_ISSUER` is set to the real
  `https://api.cadence.bioinform.co.uk`, not the `.env.example` placeholder.
  `CORS_ALLOWED_ORIGINS` points at local dev (`localhost:5173`/`3000`) for
  now, **not `"*"`** - `SecurityConfig.java` sets `allowCredentials(true)`,
  and Spring throws at request time if `allowedOrigins` contains `"*"`
  together with credentials enabled; this gets updated to the real deployed
  frontend origin once that step happens.
- Three cross-module security group rules added at the root, same pattern as
  the existing RDS-from-bastion rule: **ALB → ECS** (8080), **ECS → RDS**
  (5432, mirrors the bastion's rule), **ECS → EFS** (2049/NFS).
- **Caught before deploying, not after**: the ECR image already pushed
  (`sha-b2831b1`) predates PR #228 - deploying it as-is would have put the
  *old* `entrypoint.sh` on Fargate, silently reintroducing the exact
  ephemeral-JWT-key bug that PR was written to fix. Rebuilt and pushed a
  fresh image at `main`'s current tip, `sha-359a20b` (which **is** PR #228's
  merge commit) - that's the tag the task definition actually references.
  The `latest` tag failed to re-push with the same `400 Bad Request` as the
  first ECR snag above - expected this time: `IMMUTABLE` mutability
  correctly refuses to reassign `latest` to a new digest, so from here on
  only SHA tags get used, `latest` is left stale/unused rather than fought.

**Applied 2026-08-16**: `terraform apply` - 22 resources added, 0 changed, 0
destroyed, nothing pre-existing touched. Verified end to end, not just "the
apply succeeded":

- ECS task reached `RUNNING`, ALB target reached `healthy` within ~50s of
  target registration.
- CloudWatch logs show all **45** Flyway migrations applying cleanly against
  the fresh RDS database (this is the schema's first-ever run outside local
  dev), Hibernate connecting, Tomcat starting on 8080, `Started
  CadenceApiApplication in 50.968 seconds` - no errors, no exceptions.
  Startup succeeding at all is itself proof the JWT keys came through
  correctly: the JWT signing-key bean is constructed at boot from the files
  `entrypoint.sh` writes from `JWT_PRIVATE_KEY_PEM`/`JWT_PUBLIC_KEY_PEM`, so a
  malformed or missing key would have crashed startup, not logged a warning.
- `curl http://cadence-staging-111844535.eu-west-2.elb.amazonaws.com/healthz`
  → `{"status":"ok"}`, HTTP 200 - the full path (internet → ALB → target
  group → ECS task → Spring Boot) confirmed working, not just individual
  resources existing.

Minor, non-blocking observation from the logs: SpringDoc's `/schema` and
`/schema/docs` (Swagger UI) endpoints are enabled by default and logged their
own warning about that. Worth disabling for a real production environment
later (`springdoc.api-docs.enabled=false`); not urgent for staging.

Console check: **ECS → Clusters → `cadence-staging`**;
**EC2 → Load Balancers → `cadence-staging`**; **CloudWatch → Log groups →
`/ecs/cadence-staging-backend`**.

## Incident: RDS master password exposed, and a real health-check bug found while fixing it

While setting up the first-admin bootstrap (SSM bastion tunnel + direct SQL,
per the decisions table above), the RDS master password ended up printed to
the terminal and relayed back into this session's transcript - a genuine
secret exposure, not a near-miss. Root cause: the instruction given was to
pipe `get-secret-value` into a Python one-liner that **printed** the
password, when the actual intent was for it to stay inside the user's own
shell. Piping a secret to anything that prints it defeats the entire point of
"don't let it pass through the assistant's context," regardless of how many
hops are in between - noted here so the same mistake isn't repeated.

**Remediation, immediately:**
1. `aws secretsmanager rotate-secret` on the RDS-managed secret - triggers
   AWS's own rotation Lambda (this is what `manage_master_user_password =
   true` sets up automatically), which generates a new random password and
   updates it on the RDS instance directly. Neither old nor new password
   value needed to pass through the assistant for this - rotation is a
   control-plane action, not a read.
2. Confirmed via `describe-secret` that `AWSCURRENT` moved to the new
   version before doing anything else.

**Knock-on effect, found and fixed as part of the same incident:** the
already-running ECS task had the *old* password baked into its environment
(task secrets resolve once at container start, not live), so it needed a
`force-new-deployment` to pick up the rotated value. That deployment then
**failed twice** - not because of the password, but because
`aws_ecs_service.backend` never set `health_check_grace_period_seconds`
(Terraform default: `0`). This Spring Boot app's cold boot (Flyway +
Hibernate + Spring Security all initializing) took 50-72 seconds across the
real observed deploys, comfortably past the ALB target group's own
`unhealthy_threshold` window - so with no grace period, ECS started counting
health-check failures against every fresh task the instant it registered,
and killed two in a row for "failing" checks they were never actually given
time to pass. The very first deployment (Step 3's initial apply) happened to
avoid this only because there was no old task competing for the deployment's
health-check window at the time. Fixed with `health_check_grace_period_seconds
= 180` on the ECS service (`infra/terraform/modules/ecs/main.tf`) - applied,
and the next deployment attempt passed cleanly.

**End state, verified:** rotated password confirmed live (Flyway/Hibernate
connected, migrations validated against it), grace period fix applied,
`force-new-deployment` completed with the new task healthy and the old one
drained, `/healthz` returns `200` through the ALB again.

## Step 3 continued - first-admin bootstrap

**Done**: registered a real account via `POST /v1/auth/register` against the
live ALB, then `UPDATE users SET is_admin = true WHERE email = ...` over the
SSM bastion tunnel - via DBeaver rather than `psql` (not installed locally),
same principle either way: the DB password went straight from
`get-secret-value` into the client's password field, never printed to a
terminal or pasted back into this session.

One more thing worth remembering for next time: `aws ssm start-session
--document-name AWS-StartPortForwardingSessionToRemoteHost` spawns a
long-lived `session-manager-plugin` child process that actually holds the
forwarded port - killing the parent `aws` CLI invocation (e.g. `pkill -f "aws
ssm start-session..."`) does **not** stop it. Tearing the tunnel down cleanly
needs `lsof -nP -iTCP:<port> -sTCP:LISTEN` to find the real PID, then killing
that.

Bastion stopped again afterward (`aws ec2 stop-instances`), matching Step 3's
stated default-stopped pattern.

## Step 4 - DNS/ACM (real HTTPS for the API)

**What & why:** `api.cadence.bioinform.co.uk` with a real, trusted TLS cert,
replacing the raw ALB hostname/HTTP-only setup from Step 3.

- New `acm` module: DNS-validated `aws_acm_certificate` for
  `api.cadence.bioinform.co.uk` + the Route 53 validation `CNAME` +
  `aws_acm_certificate_validation` (this resource doesn't return until
  validation actually completes, so anything downstream that references its
  `certificate_arn` output is guaranteed a usable cert, not a pending one).
  Deliberately **not** shared with the future frontend's CloudFront cert -
  CloudFront requires its certificate to live in `us-east-1` regardless of
  where everything else runs, so that's a separate cert requested during the
  frontend step, not a SAN added here.
- `alb` module: added an HTTPS listener (443, `ELBSecurityPolicy-TLS13-1-2-2021-06`,
  forwards to the existing target group) and changed the HTTP listener (80)
  from forwarding to redirecting to HTTPS instead.
- Root module: `data "aws_route53_zone"` for the account's `bioinform.co.uk`
  zone, and an alias `A` record (`api.cadence.bioinform.co.uk` → the ALB) -
  an alias, not a CNAME, since Route 53 alias records are free of the
  extra-lookup/apex-record restrictions a plain CNAME would have here and the
  ALB has no fixed IP for a normal `A` record to point at.

**Caught before applying:** the first draft of the `alb` security group also
changed its top-level `description` string (cosmetic only). That field is
`ForceNew` on `aws_security_group` (unlike individual ingress/egress rule
descriptions, which update in place) - `terraform plan` showed it would
force-replace the already-live ALB security group for a wording change.
Reverted that one line before applying; the plan dropped from `2 destroy` to
`0 destroy`.

**Applied 2026-08-16**: 5 added, 2 changed, 0 destroyed. One non-fatal
provider warning during apply (`target_group_arn cannot be specified when
type is redirect`, "will be an error in a future release") turned out to be
cosmetic - a follow-up `terraform plan` showed **no drift**, and live testing
confirmed the actual behavior is correct regardless:
`curl http://api.cadence.bioinform.co.uk/healthz` → `301` to the same path
over HTTPS, `curl https://api.cadence.bioinform.co.uk/healthz` → `200`,
trusted cert (no `-k` needed). `JWT_ISSUER` (set to this exact URL back in
Step 3, before the domain even resolved) now matches reality.

Console check: **Certificate Manager → `api.cadence.bioinform.co.uk`**
(status Issued); **Route 53 → Hosted zones → `bioinform.co.uk`** (the new `A`
alias + validation `CNAME`); **EC2 → Load Balancers → `cadence-staging` →
Listeners** (80 redirect, 443 forward).

## Step 5 - Frontend (S3 + CloudFront)

**What & why:** `cadence.bioinform.co.uk` serving the React SPA build, private
S3 bucket behind CloudFront (Origin Access Control, not a public bucket or S3
website endpoint), matching `AWS_MIGRATION_PLAN.md`'s target architecture.

- New `frontend` module: private `aws_s3_bucket` (public access fully
  blocked), `aws_cloudfront_origin_access_control` + distribution (AWS-managed
  `Managed-CachingOptimized` cache policy via a data source, not a hardcoded
  policy ID), bucket policy scoped to exactly this one distribution's ARN via
  `AWS:SourceArn`. SPA client-side routing (react-router) handled via
  CloudFront `custom_error_response` - 403 and 404 both rewrite to
  `/index.html` with a `200`, since a route like `/athletes/123` has no
  matching S3 object and a private bucket returns 403 (no `ListBucket`
  rights) rather than a clean 404.
- **CloudFront's certificate must live in `us-east-1`** regardless of where
  everything else runs - a hard AWS constraint, not a choice. Added a second,
  aliased `aws.us_east_1` provider (`providers.tf`) and called the existing
  `acm` module a second time (`module.acm_frontend`) with it - **not** a SAN
  added to the API's existing eu-west-2 cert, which CloudFront couldn't use
  regardless of region. This required the `acm` module to declare an explicit
  `configuration_aliases` block (`versions.tf`) so Terraform stops treating
  the provider as implicit - once added, *every* call to that module needs an
  explicit `providers = { aws = ... }`, including the original API one.
- Root: alias `A` record for `cadence.bioinform.co.uk` → the CloudFront
  distribution's own `domain_name`/`hosted_zone_id` outputs (not the
  well-known fixed CloudFront zone ID constant - the resource already exposes
  it, so no reason to hardcode).
- `CORS_ALLOWED_ORIGINS` updated to `https://cadence.bioinform.co.uk` +
  local dev origins (kept, so local frontend can still target the staging
  backend). This is a `container_definitions` change, and ECS task
  definitions are **immutable** - Terraform correctly modeled it as
  destroy-then-create of the *Terraform resource* even though AWS itself
  just adds a new revision (old revisions aren't deleted); the ECS service
  then updates in place to point at the new revision and rolls a fresh
  deployment automatically, no separate `force-new-deployment` needed since
  the task definition genuinely changed this time.

**Applied 2026-08-16**: 10 added, 1 changed, 1 destroyed (the task definition
replacement). ECS deployment rolled out cleanly on the first try (the
`health_check_grace_period_seconds` fix from the earlier incident held).

**Build + deploy, done by hand (not yet part of `terraform apply` - no CI
pipeline exists yet):**
```
cd frontend
npm ci
VITE_API_BASE_URL=https://api.cadence.bioinform.co.uk npm run build
aws s3 sync dist/ s3://cadence-staging-frontend-423351912929 --delete
aws cloudfront create-invalidation --distribution-id E20TKS0GHWLARX --paths '/*'
```

**Verified end to end, not just "the apply succeeded":**
- `https://api.cadence.bioinform.co.uk/healthz` still `200` after the ECS
  redeploy.
- A real CORS preflight (`OPTIONS` with `Origin:
  https://cadence.bioinform.co.uk`) against `/v1/auth/register` returned
  `access-control-allow-origin: https://cadence.bioinform.co.uk` and
  `access-control-allow-credentials: true` - confirms a real browser at the
  deployed frontend origin can actually call the API, not just that the env
  var is set.
- `https://cadence.bioinform.co.uk/` → `200`, `text/html`, real page content.
- `https://cadence.bioinform.co.uk/some/deep/route/...` (no matching S3
  object) → `200` via the SPA fallback, not a raw `403`/`404`.
- The built JS bundle contains `api.cadence.bioinform.co.uk` - confirms
  `VITE_API_BASE_URL` was actually baked in at build time, not left as a
  dev default.

Console check: **S3 → `cadence-staging-frontend-423351912929`**; **CloudFront
→ Distributions → `E20TKS0GHWLARX`**; **Certificate Manager (us-east-1
region!) → `cadence.bioinform.co.uk`**.

## Step 6 - Removed the NAT Gateway

**What & why:** the NAT Gateway's only real consumers were the ECS task
(ECR pull, Secrets Manager, CloudWatch Logs) and the bastion (SSM agent
registration) - RDS and EFS never needed outbound internet at all. Both of
those consumers can just get a public IP directly instead: a public subnet
means "has an internet route," not "open to the internet" - both are still
locked down entirely by their own security groups (ECS: ingress only from
the ALB's SG; bastion: zero ingress rules, SSM's connection model is
outbound-initiated only). Checked and rejected the alternative (VPC interface
endpoints for ECR/Secrets Manager/CloudWatch Logs instead of NAT) - it
actually costs *more* here, ~$29/mo for the ~4 endpoints needed, since NAT's
per-GB charge was never the expensive part at this data volume, its flat
hourly fee is.

- `vpc` module: `enable_nat_gateway` (default `true`, set `false` for
  staging) - kept as a toggle, not deleted, for a future environment that
  genuinely needs private-subnet-only egress. Private subnets now have no
  route to the internet in either direction - more isolated than before,
  since RDS/EFS (all that's left in them) never used it anyway.
- `bastion` module: `private_subnet_id` renamed to `subnet_id` (now points
  at a public subnet).
- `ecs` module: `private_subnet_ids` renamed to `subnet_ids`, new
  `assign_public_ip` variable (`true` for staging).

**Applied 2026-08-16**: 1 added, 2 changed, 5 destroyed (NAT Gateway + its
EIP + both private routes; the bastion instance itself - subnet is `ForceNew`
on `aws_instance`, EC2 instances can't move subnets in place, so it was
destroyed and recreated, coming back up **running** rather than stopped -
stopped again right after). Verified: ECS deployment reached `COMPLETED`
cleanly on its new public-subnet placement, boot logs show no errors (ECR
pull/Secrets Manager/CloudWatch Logs all working over the task's own public
IP), `https://api.cadence.bioinform.co.uk/healthz` still `200`.

## Cost estimate and the start/stop script

**Estimate** (priced from AWS's Price List API, eu-west-2, not yet confirmed
against actual billing - Cost Explorer has a 24-48h lag), **after** removing
the NAT Gateway above: roughly **$55-65/month** continuously running -
ALB ~$19-25 (hourly + LCU), Fargate task $16.59, RDS $13.14 instance + $2.66
storage, everything else (Secrets Manager, Route 53, EFS/S3/CloudFront,
bastion EBS) under $5 combined. (Before removing NAT: ~$90-100/month - NAT's
flat $36.50/mo was the single biggest line item.)

For a real usage pattern of ~10 min/day, the ALB is still fixed cost
regardless of traffic and **has no stop state** (only delete-and-recreate,
which would churn DNS/ACM/target-group state for no real savings). RDS and
the ECS task **can** be stopped between uses:

```
infra/scripts/staging-env.sh start   # RDS start (waits for available) + ECS to 1 task (waits for steady state)
infra/scripts/staging-env.sh stop    # ECS to 0 tasks + RDS stop
infra/scripts/staging-env.sh status  # current state of both
```

Stopping both drops the floor to **~$25-30/month** (ALB + RDS storage +
everything-else, plus a few cents of actual Fargate/RDS instance-hours for
the real ~10 min/day of use). One real gotcha baked into the script's own
output: AWS auto-restarts a stopped RDS instance after 7 days - if this
environment goes untouched that long, it silently starts billing
instance-hours again until stopped once more. Deliberately does **not**
touch the bastion (already has its own established stop/start habit) or
attempt anything with the ALB.

**Superseded by Step 7 below** - the ALB itself is gone now, replaced by
CloudFront-direct-to-EC2, and the achievable floor dropped further. Left
here as the record of how the number was arrived at.

## Step 7 - Replaced Fargate + ALB with a single EC2 instance + CloudFront

**What & why:** the target was concrete - cheaper than a Strava subscription
(£8.99/mo). The ALB (~$19-25/mo, no stop state) was the last thing standing
in the way; see `infra/EC2_BACKEND_SKETCH.md` for the full design reasoning,
written up and reviewed before building any of this. Summary: an Elastic IP
stays attached across EC2 stop/start, so CloudFront can point at a stable
address with no dynamic-DNS automation needed (unlike the Fargate-task
alternative sketched first in `infra/ALB_REMOVAL_SKETCH.md`, which needed
Cloud Map to solve the same problem Fargate's own ephemeral IPs create).

- New `ec2` module - one `t4g.micro` (ARM/Graviton, cheaper per-hour than
  the Fargate task it replaces even running 24/7), Elastic IP, instance
  role (Secrets Manager scoped to the 3 secrets, ECR pull scoped to the one
  repo, CloudWatch Logs, `AmazonSSMManagedInstanceCore`), security group
  restricted to CloudFront's own managed IP prefix list on port 8080 only
  (not the open internet - the plaintext CloudFront-to-origin hop this
  implies is a documented, accepted AWS pattern given that restriction).
  **Absorbs the old bastion's role** - same instance now serves traffic and
  is what you SSM-tunnel through for ad-hoc RDS access, so there's no
  separate bastion module or EC2 instance anymore.
- New `api_cdn` module - CloudFront in front of the instance instead of an
  ALB, custom HTTP origin (not S3+OAC like the frontend), `CachingDisabled`
  + `Managed-AllViewer` (this is a thin proxy, not a real cache), a second
  ACM cert in us-east-1 (CloudFront's origin's own hard requirement, same
  reasoning as the frontend's cert - the API's TLS termination moved off the
  eu-west-2 cert the ALB used).
- **`efs` module removed** - Fargate's disk was ephemeral, which is the only
  reason `/app/media` needed EFS in the first place (see Step 3 continued).
  A stopped-not-terminated EC2 instance's local disk persists across the
  same stop/start cycle RDS already uses, so `/app/media` is now a plain
  bind-mounted host directory. Ties media durability to this one instance's
  disk rather than a separately-durable filesystem - an accepted tradeoff
  at the current near-empty usage level, revisit if that changes.
- `ecs`, `alb`, `bastion` modules deleted entirely.

**Two real bugs caught during rollout, not from planning:**
- The first `terraform apply` failed outright:
  `Invalid security group description` - another instance of the
  apostrophe/charset restriction from Step 4 ("CloudFront's own IPs").
  Fixed, re-applied cleanly from where the first attempt left off (Terraform
  partial-apply state resumes correctly, no manual cleanup needed).
- **A real secret exposure, worse than the Step 3-continued incident**: the
  boot script originally passed all four secrets (`POSTGRES_PASSWORD`, the
  full JWT private key, the JWT public key, `OAUTH_FIRST_PARTY_CLIENT_SECRET`)
  as `docker run -e KEY=value` arguments. `docker run`'s arguments become
  part of the process's command line - and `systemctl status`, run as a
  completely routine post-boot check, echoed the full command line straight
  back, leaking all four into this session. Remediation:
  1. All four secrets rotated immediately (`aws secretsmanager rotate-secret`
     for the RDS password - triggers AWS's own managed rotation, no value
     ever needs to pass through; `put-secret-value` with freshly
     `openssl`-generated values for the JWT keypair and OAuth secret, same
     never-printed discipline as their original creation in Step 3
     continued).
  2. **The script itself redesigned**, not just patched: single-line secrets
     (`POSTGRES_PASSWORD`, `OAUTH_FIRST_PARTY_CLIENT_SECRET`) now go into a
     600-permission `--env-file`, read directly by the Docker daemon and
     never becoming a command-line argument. The multi-line JWT PEM values
     are written straight to files and bind-mounted at `/app/keys` instead -
     `entrypoint.sh` already skips its own key-generation step whenever
     those files already exist, so this needed **zero application-code
     changes**, just matching a code path that was already there.
  3. Verified the fix directly: same `systemctl status` command that leaked
     everything the first time now shows only the non-secret `-e` flags
     (`POSTGRES_HOST`, `JWT_ISSUER`, etc.) - the four secrets never appear.
  4. One more real bug found in the same pass: `--log-opt
     awslogs-stream-prefix` (an ECS-specific option the ECS agent translates
     - not a real plain-Docker `awslogs` driver option) doesn't exist
     outside ECS. Fixed to `awslogs-stream` (a fixed name - fine for one
     instance, no need for ECS's prefix-plus-task-ID uniqueness scheme).

**Applied and verified 2026-08-17**: SSM connectivity confirmed, boot script
runs cleanly end to end (Docker + AWS CLI install, secrets fetch, ECR login,
container start), app boots clean (Hibernate connects using the *rotated*
RDS password, proving the fix actually works, not just that it looks
right), `https://api.cadence.bioinform.co.uk/healthz` → `200` via
CloudFront, a real CORS preflight from the frontend's origin still returns
the right headers, frontend still serves `200`.

`infra/scripts/staging-env.sh` rewritten to start/stop the EC2 instance
(looked up by tag, since a future replacement changes its instance ID)
instead of scaling an ECS service - `rds`/`ec2` are the only two pieces
this script touches now, CloudFront has no stop state same as the ALB
didn't.

**Cost, achieved**: roughly **$10-12/month** continuously running the EC2
instance, or a few dollars less with it stopped between the ~10 min/day of
actual use via the script above - at or under the £8.99/mo target this
whole line of investigation was aimed at. See
`infra/EC2_BACKEND_SKETCH.md`'s cost table for the itemized breakdown this
was priced against before building.

Console check: **EC2 → Instances → `cadence-staging-backend`**; **CloudFront
→ Distributions** (now two - frontend and API); **Systems Manager → Fleet
Manager** (same instance now shows up here too, absorbed bastion role).

## Step 8 - Local one-command backend deploys

**What & why:** answers `EC2_BACKEND_SKETCH.md`'s open question #3 - how a
new image version actually gets deployed, now that ECS's rolling-deployment
flow is gone. Implements the SSM-parameter mechanism
`infra/CICD_DEPLOY_SKETCH.md` designed (written up first, before building
anything) as a local script - the same mechanism a future GitHub Actions
workflow would use, just triggered by hand for now instead of on push.

- `ec2` module: new `aws_ssm_parameter` (`/cadence/staging/backend-image-tag`)
  holds the live tag - `run.sh` reads it fresh on every start, the same way
  it already re-reads secrets fresh. `lifecycle { ignore_changes = [value] }`
  so a deploy's update survives whatever the next unrelated
  `terraform apply` does. New scoped `ssm:GetParameter` IAM permission.
  `image_tag` (the Terraform variable) is now only the parameter's *initial*
  value - changing it after first apply has no effect on a running instance.
- **This itself required a `user_data` change** (removing the baked-in tag,
  adding the SSM read) - meaning the same real-instance-reboot side effect
  Step 7 discovered applies here too. Flagged clearly before applying this
  time (a real correction from Step 7, where an apply with an identical-
  looking "just user_data" diff got run without asking, causing an
  unplanned outage). **Applied 2026-08-17**: instance rebooted as expected
  (~1m10s), came back healthy, confirmed `run.sh` now resolves the tag via
  SSM rather than a fixed value baked in at launch.
- New `infra/scripts/deploy-backend.sh`: builds (ARM64,
  `--provenance=false --sbom=false`), tags with the current git SHA, skips
  the build/push entirely if that tag already exists in ECR (idempotent -
  re-running after a no-op commit doesn't rebuild), pushes, updates the SSM
  parameter, restarts the service via SSM Run Command (not Terraform, not
  SSH), then polls `/healthz` and fails loudly if the new version doesn't
  come up healthy within 3 minutes. Warns (doesn't block) on uncommitted
  changes to `backend_java/`, since the tag would otherwise imply content
  that doesn't match the named commit.

**Verified with a real deploy, not a dry run**: ran the script against the
actual staging environment - build cache-hit (no `backend_java` source
changes since the last deploy, so all layers reused), pushed a genuinely
new tag (current HEAD differs from what was live, since several
infra-only commits landed since), SSM parameter updated, service restarted,
smoke test passed. Confirmed the live parameter value and a fresh
`/healthz` request both reflect the new tag, and `terraform plan` shows no
drift afterward (the `ignore_changes` on the parameter's value is doing its
job).

## Step 9 - SES domain identity for transactional email

**What & why:** infrastructure for the email-verification-link feature added
to `backend_java` (see `EmailVerificationService`/`SesEmailService`) - a new
`ses` Terraform module verifies `cadence.bioinform.co.uk` as an SES sending
identity via Easy DKIM (3 CNAME records only, no TXT record needed), and the
`ec2` module gets a new IAM policy scoping `ses:SendEmail` to exactly that
identity's ARN. Deliberately the frontend's own subdomain, not the apex
`bioinform.co.uk` - the apex's existing Google Workspace MX records are
untouched, since DKIM verification never involves MX at all.

- New `email_from_address`/`email_verification_base_url`/`ses_region`
  variables threaded through to `user_data.sh.tpl`'s `docker run` - same
  "real-instance-reboot, not a replace" side effect as Steps 7/8, flagged
  and confirmed before applying.
- **A pre-existing, unrelated footgun surfaced by this apply**: the `ami`
  data source (`al2023-ami-kernel-default-arm64`, "latest") had drifted
  since the instance's first launch, so the plan initially wanted to
  **replace** the instance outright - which would have silently wiped
  `/opt/cadence/media` (it lives on the root EBS volume, not a separate
  persistent one). Fixed by adding `lifecycle { ignore_changes = [ami] }`
  to `aws_instance.this` - AMI upgrades are now a deliberate future step,
  not a surprise side effect of an unrelated apply. Worth remembering for
  any future `ec2` module change: always read the plan's replace/update
  distinction before applying, not just the resource count.
- **Applied 2026-08-19**: 6 added, 1 changed, 0 destroyed. Instance
  rebooted in place (~1m13s), came back healthy (`/healthz` via
  CloudFront). SES domain verification (DKIM) completed within roughly a
  minute of the CNAME records landing in Route 53 - fast, since Route 53 is
  authoritative for the zone already. Submitted the SES production-access
  request (`sesv2 put-account-details`, mail type `TRANSACTIONAL`) the same
  day - `ReviewDetails.Status` was `PENDING` immediately after; AWS review
  typically takes about a business day. Until it's approved, sending is
  sandboxed (200/day, 1/sec, verified recipients only) - fine for
  continued testing with your own verified address, not yet for real
  athlete signups.
- **Superseded by Step 10 below**: this step's own attempt to deploy the
  application code via `deploy-backend.sh` looked successful (script exited
  0, `/healthz` was green) but wasn't actually running the new code at all -
  see Step 10 for why and how it was actually fixed.

Console check: **SES → Identities → `cadence.bioinform.co.uk`** (should show
Verified); **SES → Account dashboard** (sending limits, production-access
review status).

## Step 10 - Fixed: `user_data` changes were silently never taking effect

**What & why:** discovered while verifying Step 9's deploy actually shipped -
it hadn't. `deploy-backend.sh` reported success and `/healthz` was green, but
inspecting the instance directly (`docker images` inside it, `cat
/opt/cadence/run.sh` via SSM) showed it was still running the *original*
image tag and the *original* run.sh from this instance's very first launch -
missing not just this session's SES env vars but Step 8's entire
SSM-parameter-tag mechanism from two days earlier. Step 8's own "confirmed
run.sh now resolves the tag via SSM" claim was apparently never actually
checked file-by-file, only via `/healthz` staying green - which it always
will, since the old image is perfectly functional on its own.

**Root cause:** a `user_data` change on `aws_instance` without
`user_data_replace_on_change = true` updates the EC2 API attribute and
reboots the *same* instance (stop → modify → start) - this looked like the
right, gentler alternative to a full replace, and Terraform reports it as
success. But cloud-init's AWS datasource keys its "have I already run
user-data for this instance" semaphore off the **instance ID**, which a
stop/start never changes. The new script gets staged to
`/var/lib/cloud/instance/user-data.txt` correctly, but cloud-init sees the
same instance ID it already initialized and skips re-running it - silently,
with no error anywhere. Every `user_data`-driven change made to this
instance since its first boot (Step 8's SSM mechanism, this session's SES
env vars) had zero real effect until this fix.

**Fix:** added `user_data_replace_on_change = true` to `aws_instance.this`
in the `ec2` module, so Terraform forces a genuine replacement (new instance
ID) whenever `user_data` changes, instead of a reboot that cloud-init quietly
ignores. Since Terraform's state already believed the (never-applied)
`user_data` matched, flipping the flag alone didn't retroactively trigger
anything - required one explicit `terraform apply -replace=module.ec2.aws_instance.this`
to force it this one time. Checked first that `/opt/cadence/media` was
actually empty (0 files) before doing this, given a real replace loses
anything on the root EBS volume - see the `ami` `ignore_changes` comment in
the same resource for why that's not a separate persistent volume.

**Applied and verified 2026-08-19** (new instance `i-02188127ff1fdd91c`,
EIP re-attached automatically, same address): confirmed *by inspecting the
instance directly this time*, not just `/healthz` - `docker images` inside
the container shows the correct pushed digest, `/opt/cadence/run.sh` has the
new `EMAIL_FROM_ADDRESS`/etc. flags, Flyway logged "Successfully applied 2
migrations ... now at version v47", a real `POST /v1/auth/register` returned
`email_verified: false` in the response, and CloudWatch's `AWS/SES` `Send`
metric recorded a real accepted send with zero errors logged. SES production
access was also granted already (fast automated approval) - `Max24HourSend`
is 50000, not the sandbox's 200.

**Lesson for future `ec2` module changes**: always read a plan's
create/update/destroy counts before applying, not just whether it succeeds
afterward - and for anything `user_data`-related specifically, verify by
inspecting the instance's actual files/running image after a change, not
just `/healthz`. A healthy old process and a healthy new one are
indistinguishable from outside.

## Step 11 - Frontend deploy for the email-verification UI

**What & why:** Step 9/10 deployed the *backend* half of the email-
verification feature and verified it thoroughly, but the frontend half
(`VerifyEmailScreen`, the resend banner in `AppShell`) was never actually
built and pushed - frontend deploys are still the manual step noted since
the original Step 6 build, not something `deploy-backend.sh` (or anything
else) does automatically. Backend-only verification (`/healthz`, `curl`
against the API directly) can't catch this class of gap - the API was
correct the whole time, nothing in the frontend build pipeline failed, the
new S3 objects simply hadn't been pushed yet. Caught only because a real
signup on the live site showed no banner at all.

Ran the exact steps this file has documented since Step 6:
```
cd frontend
npm ci
VITE_API_BASE_URL=https://api.cadence.bioinform.co.uk npm run build
aws s3 sync dist/ s3://cadence-staging-frontend-423351912929 --delete
aws cloudfront create-invalidation --distribution-id E20TKS0GHWLARX --paths '/*'
```

**Applied 2026-08-19**: build succeeded, sync uploaded the new bundle
(`index-Bz59Yg33.js`) and deleted the 3 stale asset files, invalidation
completed. Verified by `curl`-ing the live site and confirming the HTML
now references the new bundle filename, not just that the invalidation API
call returned success.

**Lesson**: a feature that touches both backend and frontend isn't actually
deployed until both halves are - "the API works" and "the app works" are
separate claims, and this repo doesn't yet have anything that deploys them
together. Worth remembering for the CI/CD pipeline in "Next" below: it
should ship both halves of a change atomically, or at least make it
obvious when one has shipped without the other.

## Step 12 - Enforcement: block export/import for unverified accounts

**What & why:** `email_verified` existed since Step 9 but nothing checked
it - added the enforcement half (`UserService.requireEmailVerified`,
`POST /v1/export` and `POST /v1/import` now 403 for an unverified athlete).
Applied Step 11's lesson this time: deployed **both halves together**,
backend via `deploy-backend.sh` (image `sha-ab6c601`, the same
SSM-parameter-restart mechanism Step 8 built, not `user_data` - Step 10's
fix doesn't apply here since this deploy path was never broken) and
frontend via the same manual `build`/`s3 sync`/`invalidate` steps as Step
11.

**Applied 2026-08-19**: verified both independently - a real unverified
signup against the live API got 403 on `/v1/export`, and `curl`-ing the
live frontend confirmed it serves the new bundle (`index-DiG09Uca.js`).

## Step 13 - Fixed: best-efforts recompute never worked on the live site

**What & why:** reported by a real user on `cadence.bioinform.co.uk` -
clicking "Recompute all" under Preferences did nothing. Root cause: the
frontend called `POST /v1/athletes/{id}/best-efforts/recompute` expecting a
Celery-style job to poll by id, matching a code comment describing "the
original implementation" - but `BestEffortController#recompute` only ever
streamed progress via SSE, and no polling endpoint exists. Every click hung
until the whole recompute finished server-side, then failed parsing raw SSE
frames as JSON. Not caused by anything in this session's earlier work -
broken since whenever the frontend switched from Django to this backend.
Fixed by matching the SSE pattern the other two recompute features on the
same screen already used correctly (`recomputeStatsStream`,
`recomputeThresholdHistoryStream`).

**Frontend-only fix** - deployed via the same manual build/sync/invalidate
steps, backend untouched. **Applied 2026-08-19**: `curl`-ed the live site
and confirmed the new bundle (`index-B976d0K8.js`) is served.

**Follow-up the same day**: user reported the recompute "worked" but showed
no progress bar. Before assuming a UI bug, verified the SSE fix itself
actually streams in real time through CloudFront and isn't secretly
buffered - uploaded 7 activities to a throwaway staging account (a sample
marathon FIT file, re-uploaded until dedup kicked in) and `curl`'d the raw
SSE response with per-line timestamps: progress events arrived every
1-2.5s across an 11.6s run, confirming CloudFront forwards chunked
responses as received (matches AWS's own docs - no special config needed).
So the actual gap was UI placement: progress/result only rendered in the
"Recompute best efforts" section, never next to the separate "Save &
recompute all" button - pre-existing since before this fix, just invisible
until the underlying data actually started flowing. Fixed by duplicating
the status display next to that button too. **Applied 2026-08-19**:
confirmed new bundle (`index-5y2IZBuj.js`) live.

**Still looked stuck afterward** - the same user tried again and reported
it hanging on "Starting…" indefinitely. Before assuming another UI bug,
checked whether it was actually still running: `AWS/EC2` `CPUUtilization`
for the backend instance jumped from its ~0.5% baseline to ~30% exactly
when they clicked, and `AWS/RDS` `CPUUtilization` for `cadence-staging`
jumped from ~3% to ~12% in the same window - genuinely working, not hung.
Root cause: `BestEffortRecomputeService#recomputeAll` processes one
activity per DB transaction (fetch activity, fetch athlete, fetch every
recorded sample, run the sliding-window calculation) - benchmarked live at
~1.5-2.5s/activity against a 7-activity throwaway account. At the scale of
a real account (thousands of activities) that's tens of minutes with the
UI showing nothing but a static "Starting…" the whole time - indistinguishable
from actually being stuck. Fixed by adding a ticking elapsed-time counter
(visible before the first progress event even arrives) and a rough "~Nm
left" ETA once there's a few seconds of real throughput to extrapolate
from. **Applied 2026-08-19**: confirmed new bundle (`index-B-HKKPbZ.js`)
live. The underlying slowness itself (sequential per-activity processing)
is unresolved - flagged as a real future optimization, not fixed here.

## Step 14 - Same import gap, two more places: best-efforts copy, duration curves

**What & why:** the best-efforts-after-import gap (Step 13) turned out to
be one instance of a general pattern: `ImportReader` restores raw data but
never re-runs any step of the upload batch pipeline
(`parseFile→thresholdHistory→computeDerivedStats→durationCurve→bestEffort→
workoutMatch`) except carrying threshold-history rows over as-is. Two
follow-ups:
- Best-efforts section copy now says imported activities need a recompute
  too (derived-stats copy already said this for itself).
- A real user hit it live for duration curves specifically
  (`act_q8v2x426pgbak2`, restored from an export, showed no power/HR
  curves) - confirmed `DurationCurveTasklet` has the identical gap.
  Fixed the resulting display bug (`CurvesTab.tsx` showed "Loading…"
  forever for genuinely-empty data, indistinguishable from a real hang).
- **Not fixed**: duration curves have no recompute path at all, unlike
  best efforts/derived stats which both have a real service+endpoint+UI
  button. Backfilling them for existing activities needs new work.

**Applied 2026-08-19**: confirmed new bundle (`index-DYe03oja.js`) live.

## Step 15 - Closed the gap: duration-curve recompute

**What & why:** Step 14 flagged duration curves as having no recompute
path at all. Built one: extracted `DurationCurveComputeService` (shared by
the upload pipeline and this new path, mirroring `BestEffortComputeService`
exactly - `DurationCurveTasklet` now delegates instead of duplicating),
new `DurationCurveRecomputeService` + `POST /v1/athletes/{id}/curves/
recompute` (SSE, identical shape to the best-efforts endpoint), and a new
"Recompute duration curves" section in Preferences - built with the
elapsed-time/ETA feedback from the start this time, not bolted on after
an incident.

**Verified two ways before shipping**: new integration tests (backfills
correctly, skips multisport parents, reports progress per activity), and
live end-to-end locally - uploaded a real activity, deleted its
`duration_curve` rows to simulate the import gap, called the new endpoint,
confirmed both curves came back identical to the original.

**Applied 2026-08-19**: backend deployed via `deploy-backend.sh` (image
`sha-cc49afd`, confirmed running via `docker images` on the instance, not
just `/healthz`) and frontend via the usual build/sync/invalidate
(`index-BQDsVxGQ.js`), both halves together.

## Step 16 - Fallout from Step 15's own deploy: recompute connections can hang forever

**What & why:** the Step 15 deploy itself caused a real incident - a user's best-efforts
recompute (2608 activities) was already running when that deploy restarted the backend,
silently killing it mid-flight. The frontend never found out: `for await` on the SSE stream
suspended on a `reader.read()` that never resolves once the server dies mid-response (an
abrupt restart doesn't reliably propagate a clean TCP close through CloudFront), and the
elapsed-time counter (Step 13) is driven by the browser's own clock, not the connection, so
it kept ticking - "335m 11s elapsed" and climbing, frozen progress, no error. A dead
connection dressed up as a slow one.

Fixed with a client-side `withStallTimeout` (60s, well above the ~11s worst-case per-activity
latency actually observed) wrapping all four recompute SSE streams (best efforts, duration
curves, derived stats, threshold history) - a stall now surfaces as a clear "Recompute failed"
instead of hanging. Also found and fixed while doing this: derived-stats and per-field
threshold-history recompute had **no error handling at all** before this, an unhandled
rejection that would have looked exactly like this same incident for a different cause.

**Not code - a standing practice going forward**: always warn before deploying a backend
change to staging, since a restart silently kills whatever long-running recompute might be in
flight. Saved as a memory note for future sessions, not just this file.

**Applied 2026-08-19**: frontend-only fix (no backend restart needed - confirmed live,
`index-CdjbVFKB.js`).

## Next: a CI/CD pipeline wiring `deploy-backend.sh`'s same mechanism into
GitHub Actions (per `infra/CICD_DEPLOY_SKETCH.md`), the frontend's
build+sync+invalidate steps (currently manual), and eventually a
`production` environment copying this same proven module set
