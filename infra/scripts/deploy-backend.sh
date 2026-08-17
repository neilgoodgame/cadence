#!/bin/sh
# Build, push, and deploy a new backend image from this machine - the local
# stand-in for infra/CICD_DEPLOY_SKETCH.md's GitHub Actions workflow, using
# the exact same mechanism (SSM parameter + SSM Run Command restart, no
# Terraform/reboot involved - see that sketch for why).
#
# Usage: infra/scripts/deploy-backend.sh
set -eu

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
BACKEND_DIR="$REPO_ROOT/backend_java"
ECR_REPO="423351912929.dkr.ecr.eu-west-2.amazonaws.com/cadence-backend-java"
PARAM_NAME="/cadence/staging/backend-image-tag"
PROFILE="cadence-terraform"
REGION="eu-west-2"
AWS="aws --profile $PROFILE --region $REGION"

GIT_SHA=$(cd "$REPO_ROOT" && git rev-parse --short=7 HEAD)
TAG="sha-$GIT_SHA"
IMAGE="$ECR_REPO:$TAG"

if [ -n "$(cd "$REPO_ROOT" && git status --porcelain -- backend_java)" ]; then
	echo "WARNING: backend_java has uncommitted changes - deploying will tag" >&2
	echo "the image sha-$GIT_SHA even though its content won't match that" >&2
	echo "commit. Ctrl-C now to abort, or wait 5s to continue anyway." >&2
	sleep 5
fi

instance_id() {
	$AWS ec2 describe-instances \
		--filters "Name=tag:Name,Values=cadence-staging-backend" "Name=instance-state-name,Values=running" \
		--query 'Reservations[0].Instances[0].InstanceId' --output text
}

echo "Deploying $IMAGE"

if $AWS ecr describe-images --repository-name cadence-backend-java --image-ids imageTag="$TAG" >/dev/null 2>&1; then
	echo "Tag $TAG already exists in ECR - skipping build/push (idempotent: nothing" \
		"in backend_java has changed since the last time this exact commit was deployed)."
else
	echo "Building (linux/arm64, matches the instance's Graviton architecture)..."
	$AWS ecr get-login-password | docker login --username AWS --password-stdin "$ECR_REPO"
	# --provenance=false --sbom=false: ECR rejects the default attestation
	# manifest with a bare 400 - see infra/README.md's ECR section.
	docker buildx build --platform linux/arm64 --provenance=false --sbom=false \
		-t "$IMAGE" --push "$BACKEND_DIR"
fi

echo "Updating $PARAM_NAME to $TAG..."
$AWS ssm put-parameter --name "$PARAM_NAME" --value "$TAG" --overwrite --query 'Version' --output text

ID=$(instance_id)
echo "Restarting cadence-backend.service on $ID..."
CMD_ID=$($AWS ssm send-command --instance-ids "$ID" \
	--document-name AWS-RunShellScript \
	--parameters 'commands=["systemctl restart cadence-backend"]' \
	--query 'Command.CommandId' --output text)
$AWS ssm wait command-executed --command-id "$CMD_ID" --instance-id "$ID"

echo "Waiting for the app to come back up (fresh boot typically takes 50-70s)..."
i=0
while [ "$i" -lt 18 ]; do
	if curl -sf -m 5 https://api.cadence.bioinform.co.uk/healthz >/dev/null 2>&1; then
		echo "Deploy succeeded: $IMAGE is live."
		exit 0
	fi
	i=$((i + 1))
	sleep 10
done

echo "Deploy did NOT come up healthy within 3 minutes - check:" >&2
echo "  aws ssm start-session --target $ID (or CloudWatch Logs: /ec2/cadence-staging-backend)" >&2
exit 1
