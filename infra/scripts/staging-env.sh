#!/bin/sh
# Start/stop the always-on-billed pieces of staging (RDS + the ECS task) for
# a usage pattern of ~10 min/day - see infra/README.md's cost estimate. NAT
# Gateway and the ALB are NOT touched here: neither has a stop state (only
# delete-and-recreate), and recreating them per-session would churn DNS/ACM/
# target-group state for no real savings - they're accepted as fixed cost.
#
# Usage: infra/scripts/staging-env.sh start|stop|status
set -eu

CLUSTER="cadence-staging"
SERVICE="cadence-staging-backend"
DB_INSTANCE="cadence-staging"
PROFILE="cadence-terraform"
REGION="eu-west-2"
AWS="aws --profile $PROFILE --region $REGION"

usage() {
	echo "Usage: $0 start|stop|status" >&2
	exit 1
}

db_status() {
	$AWS rds describe-db-instances --db-instance-identifier "$DB_INSTANCE" \
		--query 'DBInstances[0].DBInstanceStatus' --output text
}

ecs_running_count() {
	$AWS ecs describe-services --cluster "$CLUSTER" --services "$SERVICE" \
		--query 'services[0].runningCount' --output text
}

cmd_status() {
	echo "RDS ($DB_INSTANCE): $(db_status)"
	echo "ECS ($SERVICE): runningCount=$(ecs_running_count)"
}

cmd_stop() {
	echo "Scaling ECS service to 0 tasks..."
	$AWS ecs update-service --cluster "$CLUSTER" --service "$SERVICE" \
		--desired-count 0 --query 'service.desiredCount' --output text

	STATUS=$(db_status)
	if [ "$STATUS" = "available" ]; then
		echo "Stopping RDS instance $DB_INSTANCE..."
		$AWS rds stop-db-instance --db-instance-identifier "$DB_INSTANCE" \
			--query 'DBInstance.DBInstanceStatus' --output text
		echo "Note: AWS auto-restarts a stopped RDS instance after 7 days -" \
			"if this environment goes untouched that long, it'll silently" \
			"start billing the instance-hours again until stopped once more."
	else
		echo "RDS is already '$STATUS' - not stopping."
	fi

	echo "Not touching NAT Gateway or ALB - both stay up (fixed cost, no stop state)."
	echo "ECS won't finish scaling down instantly - run '$0 status' to check."
}

cmd_start() {
	STATUS=$(db_status)
	if [ "$STATUS" = "stopped" ]; then
		echo "Starting RDS instance $DB_INSTANCE..."
		$AWS rds start-db-instance --db-instance-identifier "$DB_INSTANCE" \
			--query 'DBInstance.DBInstanceStatus' --output text
	elif [ "$STATUS" != "available" ]; then
		echo "RDS is '$STATUS' (not stopped, not available) - waiting for it to settle before continuing."
	else
		echo "RDS is already available."
	fi

	echo "Waiting for RDS to become available (this can take a few minutes from a cold start)..."
	$AWS rds wait db-instance-available --db-instance-identifier "$DB_INSTANCE"
	echo "RDS available."

	echo "Scaling ECS service to 1 task..."
	$AWS ecs update-service --cluster "$CLUSTER" --service "$SERVICE" \
		--desired-count 1 --query 'service.desiredCount' --output text

	echo "Waiting for the ECS service to reach steady state (grace period is 180s" \
		"for this app's cold boot - see infra/README.md's incident writeup)..."
	$AWS ecs wait services-stable --cluster "$CLUSTER" --services "$SERVICE"
	echo "Ready: https://api.cadence.bioinform.co.uk/healthz"
}

case "${1:-}" in
start) cmd_start ;;
stop) cmd_stop ;;
status) cmd_status ;;
*) usage ;;
esac
