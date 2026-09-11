#!/bin/sh
# Build and deploy a new frontend build from this machine - the manual counterpart to
# deploy-backend.sh (see infra/RUNBOOK.md §7 for why the frontend isn't rolled into that script).
#
# Fetches VITE_OAUTH_CLIENT_SECRET from Secrets Manager itself so it can never silently be left
# unset again - a build missing it still logs in fine (login doesn't need the client secret) but
# fails every *silent token refresh* with invalid_client, forcing users back to the login screen
# on every full page load (a reload, a new tab, a pasted link). Confirmed live 2026-09-11.
#
# Usage: infra/scripts/deploy-frontend.sh
set -eu

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
FRONTEND_DIR="$REPO_ROOT/frontend"
API_BASE_URL="https://api.cadence.bioinform.co.uk"
SITE_URL="https://cadence.bioinform.co.uk"
S3_BUCKET="cadence-staging-frontend-423351912929"
DISTRIBUTION_ID="E20TKS0GHWLARX"
OAUTH_SECRET_ID="cadence-staging-oauth-first-party-client-secret"
PROFILE="cadence-terraform"
REGION="eu-west-2"
AWS="aws --profile $PROFILE --region $REGION"

if [ -n "$(cd "$REPO_ROOT" && git status --porcelain -- frontend)" ]; then
	echo "WARNING: frontend has uncommitted changes - deploying will ship them even though" >&2
	echo "they aren't committed. Ctrl-C now to abort, or wait 5s to continue anyway." >&2
	sleep 5
fi

echo "Fetching OAuth client secret ($OAUTH_SECRET_ID)..."
OAUTH_SECRET=$($AWS secretsmanager get-secret-value --secret-id "$OAUTH_SECRET_ID" \
	--query SecretString --output text)

cd "$FRONTEND_DIR"
echo "Installing dependencies..."
npm ci

echo "Building..."
VITE_API_BASE_URL="$API_BASE_URL" VITE_OAUTH_CLIENT_SECRET="$OAUTH_SECRET" npm run build

echo "Syncing dist/ to s3://$S3_BUCKET..."
$AWS s3 sync dist/ "s3://$S3_BUCKET" --delete

echo "Invalidating CloudFront ($DISTRIBUTION_ID)..."
INVALIDATION_ID=$($AWS cloudfront create-invalidation --distribution-id "$DISTRIBUTION_ID" \
	--paths '/*' --query 'Invalidation.Id' --output text)
$AWS cloudfront wait invalidation-completed --distribution-id "$DISTRIBUTION_ID" --id "$INVALIDATION_ID"

if curl -sf -m 10 "$SITE_URL" >/dev/null 2>&1; then
	echo "Deploy succeeded: $SITE_URL is live."
	exit 0
fi

echo "Deploy finished but the site didn't respond to a basic check - verify manually: $SITE_URL" >&2
exit 1
