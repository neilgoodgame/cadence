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

## Next: Step 3 continued - ECS, ALB
