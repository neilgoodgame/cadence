# AWS migration - build log

A step-by-step record of migrating Cadence to AWS, kept as we go. See
`AWS_MIGRATION_PLAN.md` (repo root) for the full architectural analysis this
is executing against - this file is the narrower "what we actually did and
why" log, including the small real-world snags (expired sessions, disk
space, credential plumbing) that the plan document doesn't cover.

## Decisions made

| Decision | Choice | Why |
|---|---|---|
| Production backend | Java (Spring Boot) | Simpler AWS footprint - no Redis/Celery worker fleet, ingestion runs in-process. See `AWS_MIGRATION_PLAN.md` §3. |
| Launch status | Pre-launch | No real users yet - DNS cutover can just be a flip, no bake period needed. |
| AWS region | `eu-west-2` (London) | Already the account's configured default region. |
| Domain | `cadence.bioinform.co.uk` (frontend), `api.cadence.bioinform.co.uk` (API) | The account's only Route 53 hosted zone is `bioinform.co.uk` - the `cadence.cc` in the code's `JWT_ISSUER` default is just a placeholder, not a domain actually owned in this account. |
| IaC tool | Terraform | Team already uses it at work; this doubles as a learning exercise. |
| Environments | One to start (`staging`) | Learn on a low-stakes environment first; copy the proven pattern to add `production` later once comfortable. |
| Database | RDS PostgreSQL + community `timescaledb` extension | Plan's default recommendation (§4) - least-change path from local dev, nothing in the current migrations needs Timescale's commercial-only features. |

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

## Next: Step 2 - Networking (VPC)
