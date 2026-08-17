# Sketch: automated backend deploys via GitHub Actions

**Status: the mechanism is built (`infra/README.md`'s Step 8), the GitHub
Actions wiring described below is not.** The "gap this has to close first"
section - moving the live image tag out of `user_data` into an SSM
parameter `run.sh` reads fresh on every start - shipped as
`infra/scripts/deploy-backend.sh`, a local script using exactly this
sketch's mechanism, verified with a real deploy. What's left is only the
"trigger this automatically on push" part: the workflow YAML, the OIDC IAM
role, and wiring it into GitHub - none of that exists yet.

## The gap this has to close first

The current `run.sh` (see `infra/terraform/modules/ec2/user_data.sh.tpl`)
pulls a **fixed image tag baked into `user_data` at instance launch time**
(`image_tag` is a Terraform variable, currently hardcoded to `sha-359a20b`
in `envs/staging/main.tf`). Restarting the service today just re-runs the
same tag - it does not pick up a new image. Before any automation is worth
building, this needs to change to something `run.sh` reads **fresh on every
start**, the same way it already re-reads secrets fresh on every start.

**The wrong way to fix this, learned the hard way this session**: routing a
new tag through Terraform (`terraform apply` with an updated `image_tag`)
changes the `aws_instance`'s `user_data` attribute - and updating `user_data`
via the AWS API triggers a **real instance reboot**, even without Terraform
forcing full replacement. That's exactly what caused the unplanned ~1 minute
outage right after Step 7 shipped. A CI/CD pipeline that deploys this way
would cause a reboot on every single deploy - unacceptable for something
meant to run routinely.

**The fix**: move the "which tag is currently live" state out of Terraform
entirely, into an **SSM Parameter Store** parameter
(`/cadence/staging/backend-image-tag`, a plain `String`). Terraform sets it
once at first creation (`aws_ssm_parameter`, `overwrite` on subsequent
applies left `false` or the resource marked `lifecycle { ignore_changes =
[value] }` - so a `terraform apply` for something unrelated doesn't stomp
whatever CI/CD deployed since). `run.sh` reads it at the top, before pulling
the image:

```sh
IMAGE_TAG=$(aws ssm get-parameter --name /cadence/staging/backend-image-tag --query 'Parameter.Value' --output text)
```

A deploy then becomes: write the new tag to that parameter, then
`systemctl restart cadence-backend` (via SSM Run Command, not user_data) -
no Terraform involved, no reboot, just the same container-restart path
`infra/scripts/staging-env.sh` already uses.

## Authentication: OIDC, not a stored AWS key

GitHub Actions should authenticate via **OIDC federation**
(`aws-actions/configure-aws-credentials` with `role-to-assume`), not a
long-lived AWS access key stored as a repo secret. The trust policy on the
IAM role scopes to `token.actions.githubusercontent.com` with a condition
on the exact repo and branch (`repo:<owner>/design_handoff_fitness_viewer:
ref:refs/heads/main`) - no other repo, fork, or branch can assume it, and
there's no static credential sitting in GitHub's secret store that could
leak or need rotating. This is a different actor than the human-operated
`cadence-terraform` IAM user (which does use a static key, because a human
needs to run `aws` commands interactively) - CI is exactly the case OIDC
was built for.

The role's permissions, scoped tightly:
- `ecr:GetAuthorizationToken` (no resource-level scoping possible, same as
  the `ec2` module's own execution role)
- `ecr:BatchCheckLayerAvailability`/`PutImage`/`InitiateLayerUpload`/
  `UploadLayerPart`/`CompleteLayerUpload` scoped to the one repo
- `ssm:PutParameter` scoped to the one parameter's ARN
- `ssm:SendCommand`, `ssm:GetCommandInvocation` scoped to the instance (by
  ARN, or an IAM tag condition matching how `staging-env.sh` looks it up)

## Workflow shape

```yaml
name: Deploy backend
on:
  push:
    branches: [main]
    paths: ["backend_java/**"]
  workflow_dispatch: {}  # manual trigger too, for a re-deploy with no code change

permissions:
  id-token: write
  contents: read

jobs:
  deploy:
    runs-on: ubuntu-24.04-arm  # native ARM64 runner - avoids QEMU emulation of
                                # the Gradle build, which would otherwise be slow
    steps:
      - uses: actions/checkout@v4

      - uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::423351912929:role/github-actions-cadence-deploy
          aws-region: eu-west-2

      - run: aws ecr get-login-password --region eu-west-2 |
             docker login --username AWS --password-stdin
             423351912929.dkr.ecr.eu-west-2.amazonaws.com

      # --provenance=false --sbom=false: ECR rejects the default attestation
      # manifest with a bare 400, see infra/README.md's ECR section.
      - run: |
          docker buildx build --platform linux/arm64 \
            --provenance=false --sbom=false \
            -t 423351912929.dkr.ecr.eu-west-2.amazonaws.com/cadence-backend-java:sha-${GITHUB_SHA::7} \
            --push backend_java
          # No :latest push - IMMUTABLE tag mutability blocks reassigning it
          # after the first push anyway, already a dead end (see the ECR
          # section's documented snag). SHA tags only, same as every manual
          # push this session has done.

      - run: aws ssm put-parameter \
            --name /cadence/staging/backend-image-tag \
            --value "sha-${GITHUB_SHA::7}" --overwrite

      - name: Restart the service
        run: |
          INSTANCE_ID=$(aws ec2 describe-instances \
            --filters "Name=tag:Name,Values=cadence-staging-backend" "Name=instance-state-name,Values=running" \
            --query 'Reservations[0].Instances[0].InstanceId' --output text)
          CMD_ID=$(aws ssm send-command --instance-ids "$INSTANCE_ID" \
            --document-name AWS-RunShellScript \
            --parameters 'commands=["systemctl restart cadence-backend"]' \
            --query 'Command.CommandId' --output text)
          aws ssm wait command-executed --command-id "$CMD_ID" --instance-id "$INSTANCE_ID"

      - name: Smoke test
        run: |
          for i in $(seq 1 12); do
            curl -sf https://api.cadence.bioinform.co.uk/healthz && exit 0
            sleep 10
          done
          echo "::error::Backend did not come up healthy after deploy"
          exit 1
```

## What this does *not* solve - stated plainly

- **No zero-downtime cutover.** `systemctl restart` stops the old container
  before the new one starts (matches `docker run --rm`'s own model). The
  smoke-test step catches a bad deploy and fails the workflow loudly, but it
  doesn't roll back - there's no still-running old container to fall back
  to, the same limitation `EC2_BACKEND_SKETCH.md` already accepted for this
  architecture. A failed smoke test means manually fixing forward (revert
  the commit, re-run the workflow) or restoring the SSM parameter to the
  previous tag by hand.
- **No staged rollout.** One instance, one restart, all traffic affected at
  once. Acceptable for a single low-traffic environment; wouldn't be for
  anything with real concurrent users - the same ceiling
  `EC2_BACKEND_SKETCH.md` already flagged for this whole approach.
- **The frontend isn't covered here.** `infra/README.md`'s "Next" line
  tracks that separately (automating `npm run build` + `aws s3 sync` +
  `aws cloudfront create-invalidation`) - same OIDC role, same repo, just a
  different job with S3/CloudFront permissions instead of ECR/SSM ones. Not
  sketched here since it wasn't what was asked for, but it'd slot into the
  same workflow file as a parallel job with a different `paths` filter
  (`frontend/**`).

## Terraform changes this needs (not yet made)

- `aws_ssm_parameter.backend_image_tag` in the `ec2` module (or root),
  `lifecycle { ignore_changes = [value] }` so CI/CD-driven updates survive
  unrelated `terraform apply` runs.
- `run.sh` (via `user_data.sh.tpl`) changed to read the tag from that
  parameter instead of the `image_tag` Terraform variable - this *is* a
  `user_data` change, so it takes the same "gets baked in at next real
  instance replacement, doesn't retroactively apply to the running instance"
  path Step 7's own fixes did. Would need pushing to the live instance by
  hand once, same as the secret-exposure fix, to take effect immediately
  rather than waiting for some future replacement.
- New `aws_iam_openid_connect_provider` for
  `token.actions.githubusercontent.com` (one per AWS account, likely
  doesn't exist yet - check before assuming it needs creating) +
  `aws_iam_role` with the scoped trust policy and permissions above.
