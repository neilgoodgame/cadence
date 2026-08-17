# Sketch: replacing Fargate with a dedicated EC2 instance

**Status: built - see `infra/README.md`'s Step 7.** Both open questions
below were settled (bastion absorbed, EFS dropped) and the third (deploy
mechanism) is still genuinely open - `infra/README.md` covers what actually
shipped, including two real bugs this sketch didn't anticipate (a security
group description charset error, and a secret-exposure incident from
`docker run -e` putting secrets on the process command line). Left in place
as the design record, not updated to match reality after the fact.

Supersedes the Cloud Map piece of
`ALB_REMOVAL_SKETCH.md` - same end goal (CloudFront in front of the backend
instead of an ALB, to get off a flat hourly cost), but a genuinely simpler
path to it. That sketch is still worth reading for the CloudFront-side
pieces (cache policy, us-east-1 cert, `CachingDisabled`) which carry over
unchanged here.

## Why this is simpler than the Cloud Map sketch

The Cloud Map sketch existed to solve one problem: Fargate tasks get a new
IP on every restart, so something has to keep CloudFront's origin config in
sync with whatever IP the task currently has. An EC2 instance doesn't have
that problem if it's stopped rather than replaced - an **Elastic IP (EIP)**
stays attached across `stop`/`start` (same pattern as
`infra/scripts/staging-env.sh` already uses for RDS), so the address is
simply static. CloudFront's origin gets configured once and never touched
again. No Cloud Map namespace, no service registration, no automation to
build or trust.

## Cost comparison (eu-west-2, current Price List API rates)

One thing this sketch corrected in an earlier estimate: **AWS bills
$0.005/hr for any public IPv4 address, in-use or idle** (a 2024 pricing
change) - this already applies to the ECS task's current public IP, not
something new introduced by moving to EC2.

| | Fargate (current) | EC2 t4g.micro |
|---|---|---|
| Compute, running | $0.02272/hr (0.5 vCPU + 1GB, ARM) | $0.0094/hr (2 vCPU burstable, 1GB) |
| Compute, stopped | $0 | $0 |
| Public IP (flat, in-use or idle) | $0.005/hr | $0.005/hr (EIP) |
| EBS root volume | n/a | ~$0.74/mo (8GB gp3, matches the bastion's) |

t4g.micro is cheaper per hour than the current Fargate task **even running
24/7** ($6.86/mo vs $16.59/mo compute), and unlike the bastion's own
t4g.micro, this one only needs to be running for the ~10 min/day it's
actually used - same stop/start pattern already established.

**Estimated floor, everything stacked** (EC2 stopped between uses, RDS
stopped between uses via the existing script, CloudFront pay-per-request
for both frontend and API): EC2+EIP+EBS ~$4.5-5, RDS storage $2.66,
Secrets Manager $1.20, Route 53 $0.50, CloudFront (both distributions,
light traffic) ~$1-2, real Fargate-equivalent/RDS instance-hours for
~10 min/day of actual use, a few cents → **roughly $10-12/month**, at or
under the £8.99/mo target this whole line of investigation is aimed at.

## Architecture

- **One EC2 instance** (`t4g.micro`, ARM/Graviton - matches the ECR image
  already built for ARM), public subnet, Elastic IP attached. Runs the
  backend via `docker compose` - reusing `backend_java/docker-compose.yml`'s
  shape rather than inventing a new deployment mechanism, since that's
  already the pattern this whole project uses for local dev.
- **Absorbs the SSM bastion's role.** The bastion is a separate `t4g.micro`
  today purely for ad-hoc DB access; this instance already has SSM agent,
  network access to RDS, and a security group that's not open to raw
  internet traffic (see below) - no real reason to keep paying for a second
  instance + EBS volume just to keep the two concerns apart. (Worth a
  deliberate call, not an assumption - see "Open questions" below.)
- **Security group**: inbound restricted to
  [CloudFront's managed IP prefix list](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-restricting-access-to-s3.html#private-content-cloudfront-ip-prefixlist)
  (`com.amazonaws.global.cloudfront.origin-facing`) on port 8080 - not
  `0.0.0.0/0`. This is the same mitigation `ALB_REMOVAL_SKETCH.md` flagged
  for the CloudFront→origin plaintext-HTTP hop: only CloudFront's own edge
  nodes can reach the instance directly, not the open internet. SSM's own
  connection model still needs zero inbound rules, unaffected either way.
- **CloudFront**: same shape as `ALB_REMOVAL_SKETCH.md` already sketched -
  new distribution (or new behavior on the frontend one), `CachingDisabled`
  policy for API responses, us-east-1 ACM cert (CloudFront's hard
  requirement, same as the frontend's), alias swapped from the ALB to this
  distribution. Origin is a Route 53 A record pointed at the EIP - CloudFront
  requires a DNS name for its origin, not a raw IP, so this record exists
  purely for that (never touched again once set, unlike the Cloud Map
  sketch's dynamically-updated record).
- **RDS and EFS**: unaffected. Neither depends on the compute layer above
  them - see "Open questions" for whether EFS is even still needed once
  storage is no longer ephemeral.
- **IAM**: one instance profile/role replaces the current ECS task role +
  execution role - `secretsmanager:GetSecretValue` scoped to the same 3
  secrets, `ecr:GetAuthorizationToken`/`BatchGetImage` scoped to the one
  repo, `logs:*` if keeping the `awslogs` Docker logging driver (works
  standalone, doesn't require ECS), `AmazonSSMManagedInstanceCore` if
  absorbing the bastion role.
- **Secrets at boot**: no more native `secrets` block (that's an ECS task
  definition feature). A small boot script instead - `aws secretsmanager
  get-secret-value` (via the instance role, no different in spirit from what
  the JWT-keys/OAuth-secret generation already does this session) writing
  the resolved values into a `.env` file `docker compose` reads, before
  `docker compose up -d`. Less native/audited than ECS's `secrets` block,
  functionally equivalent.

## What gets removed

`ecs` module entirely (cluster, task definition, service, its security
group, both IAM roles), `alb` module entirely (load balancer, both
listeners, target group, its security group). `bastion` module too, if its
role is absorbed as above.

## What gets added

A new module (`ec2-backend` or similar): the instance, its EIP, instance
profile/role, security group, boot/deploy script. Extends the `frontend`
module's CloudFront pattern (or a sibling module) for the API's distribution
- most of `modules/frontend/main.tf`'s CloudFront/cache-policy/cert
plumbing carries over, swapping the S3+OAC origin for a custom HTTP origin
and `CachingOptimized` for `CachingDisabled`.

## Real tradeoffs, stated plainly

- **Deploys are no longer zero-downtime.** ECS's rolling deployment
  (`deployment_minimum_healthy_percent = 100`) keeps the old task serving
  until the new one passes health checks. A single EC2 instance running
  `docker compose` means a deploy is `docker compose pull && docker compose
  up -d` on that one instance - a real, if brief, outage window during the
  restart. Acceptable for a low-traffic personal/staging environment;
  wouldn't be for anything with real concurrent users.
- **This instance owns its own patching.** Fargate abstracts the underlying
  host away entirely - AWS patches it. An EC2 instance means OS-level
  security updates, kernel updates, AMI refreshes are now this project's
  responsibility, not AWS's. Amazon Linux 2023's automatic security-patch
  cadence helps, but it's not the same fully-managed story.
- **Doesn't scale past one instance without real work.** Fine for this
  project's actual usage pattern (~10 min/day, one environment), but if this
  ever needed multiple concurrent tasks or true production traffic, Fargate
  (or an EC2 Auto Scaling Group, which reintroduces most of this sketch's
  complexity back) is the better shape. Not a concern for what this
  environment is actually for right now.
- **Secrets resolution is a boot script, not a platform feature.** Same end
  result, less polish, one more script to trust doing the right thing on
  every boot.

## Open questions to settle before building

1. **Absorb the bastion, or keep it separate?** Combining saves ~$1.50/mo
   (a second instance's compute-while-running + EBS) and removes one moving
   part, at the cost of blurring "the thing serving public traffic" and "the
   thing with DB access" into one instance. Given the security group already
   restricts inbound to CloudFront only (not raw internet), the actual risk
   delta is small - but it's a real security posture choice, not just a cost
   one.
2. **Keep EFS, or move `/app/media` to local EBS?** EFS existed specifically
   because Fargate's disk is ephemeral (see `infra/README.md`'s Step 3
   continued section). A stopped-not-terminated EC2 instance's EBS volume
   persists across the same stop/start cycle RDS already uses - so EFS's
   original reason to exist goes away *as long as the instance is never
   terminated/replaced*. Local EBS is simpler (one less module, one less
   cross-module SG rule) but ties media durability to this one instance's
   volume rather than a separately-durable filesystem; EFS costs
   effectively nothing at the current near-empty usage level, so this is
   more a complexity call than a cost one.
3. **How does a new image version actually get deployed?** SSM Run Command
   (matching the "no SSH keys, ever" stance already established for the
   bastion) running the `docker compose pull && up -d` sequence is the
   sketch's assumption, but this needs an actual answer - triggered by hand,
   or wired into some future CI step - before this replaces the current
   `terraform apply`-driven ECS deployment flow.
