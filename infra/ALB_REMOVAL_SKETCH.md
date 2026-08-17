# Sketch: replacing the ALB with CloudFront-direct-to-Fargate

**Status: superseded, not built as written.** The recommendation below
(don't build this) held right up until a concrete cost target - cheaper than
a Strava subscription - made the ALB's fixed cost non-negotiable. What
actually got built instead is `EC2_BACKEND_SKETCH.md`'s approach: an EC2
instance with an Elastic IP solves the same "CloudFront needs a stable
origin" problem this sketch's Cloud Map piece was solving for, without
needing Cloud Map, a public DNS namespace, or any dynamic-update automation
at all. See `infra/README.md`'s Step 7. The problem statement and the
CloudFront-side pieces below (cache policy, us-east-1 cert,
`CachingDisabled`) are still accurate background - just not how the origin
IP problem actually got solved.

## The problem being solved

Every other fixed-cost piece in staging has already been dealt with:
NAT Gateway removed entirely (Step 6), RDS + the ECS task stoppable between
uses (`infra/scripts/staging-env.sh`). The ALB is what's left - a flat
~$19-25/mo (hourly + LCU) with no stop state, existing/`infra/README.md`'s
cost section covers why deleting-and-recreating it per session isn't worth
it (DNS/ACM/target-group churn for a resource that's cheap to just leave
running).

CloudFront, by contrast, bills **per request** (pennies at 10 min/day of
traffic), not per hour - and it's already in this stack for the frontend.
Could the API sit behind CloudFront instead of an ALB?

## The actual blocker: Fargate tasks don't have a stable IP

An ALB (or NLB) exists to solve exactly one problem CloudFront-direct
reintroduces: **something has to track the ECS task's current IP**, because
Fargate assigns a new ENI (and new public IP, since Step 6 removed NAT) on
every task restart - a redeploy, a crash-triggered replacement, or literally
just running `infra/scripts/staging-env.sh stop` then `start`. CloudFront's
origin config is a hostname, not something that watches ECS for you.

**The native-AWS way to solve that piece**: [Cloud Map service
discovery](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service-discovery.html)
with a **public DNS namespace** (not the usual private one used for
service-to-service VPC traffic). ECS already has built-in integration for
this - a `service_registries` block on the `aws_ecs_service` resource points
at a Cloud Map service, and ECS itself keeps that service's DNS record in
sync with whichever task is actually running, on every start/stop/replace,
with no custom Lambda needed. CloudFront's origin would then point at that
stable Cloud Map hostname (e.g. `backend.cadence-staging.internal` publicly
resolvable) instead of a raw IP.

## Sketch of the pieces

- `aws_service_discovery_public_dns_namespace` - one per environment.
- `aws_service_discovery_service` - the record that tracks the task's
  current IP, A record, short TTL.
- `aws_ecs_service.backend.service_registries` - wires the ECS service to
  register/deregister itself automatically.
- New CloudFront distribution (or a second cache behavior on the existing
  frontend one, routed by path/host) - custom HTTP origin pointed at the
  Cloud Map hostname, `CachingDisabled` policy (API responses shouldn't be
  cached the way the frontend's static build is), a **second** ACM cert in
  us-east-1 for `api.cadence.bioinform.co.uk` (CloudFront's us-east-1
  requirement applies here too, same as the frontend's cert - see
  `infra/README.md` Step 5).
- Route 53: swap the `api.cadence.bioinform.co.uk` alias from the ALB to
  this new CloudFront distribution.
- ALB, its listeners, its security group, its target group: deleted.

## What this actually costs to build, not just in dollars

- **TLS gets more complicated, not less.** CloudFront terminates the
  client-facing HTTPS, but then needs to reach the task. Two options, both
  worse than what the ALB does today: (a) CloudFront → task over plain HTTP
  - simplest, but the request payload (auth tokens, personal fitness data)
  crosses the public internet unencrypted between CloudFront's edge and the
  task's public IP, even if briefly; (b) the task terminates its own TLS -
  real application-level work (a cert, a keystore, `entrypoint.sh`/Spring
  config changes), not a Terraform-only change.
- **A new latency/downtime window on every restart.** CloudFront caches DNS
  resolution for its origins for some period even with a short record TTL -
  so immediately after any task restart (including the routine
  `staging-env.sh start`), there's a window where CloudFront may still be
  routing to the now-dead old IP until it re-resolves. The ALB has no
  equivalent gap - it tracks target health directly, continuously.
- **More moving parts, more failure modes.** Cloud Map namespace, service
  registration, a second CloudFront distribution/behavior, a second
  us-east-1 cert - each one is a new thing that can misconfigure or drift,
  for an environment whose whole point right now is being simple enough to
  learn from.

## Recommendation

**Don't build this.** The dollar saving (~$15-20/mo) doesn't clear the bar
against the TLS regression and the restart-latency gap it introduces,
especially right after Step 6 specifically removed a different kind of
fragility (NAT Gateway as a single point of failure) in the name of keeping
this environment simple and reliable. Worth revisiting only if this pattern
needs to scale to many parallel low-traffic environments (e.g. a
preview-per-PR setup) where the *aggregate* ALB cost, not one environment's,
is what's actually being optimized.
