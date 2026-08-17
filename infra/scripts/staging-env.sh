#!/bin/sh
# Start/stop the always-on-billed pieces of staging (RDS + the backend EC2
# instance) for a usage pattern of ~10 min/day - see infra/README.md's cost
# estimate. The ALB is gone entirely (replaced by CloudFront-direct-to-EC2,
# see infra/EC2_BACKEND_SKETCH.md) and NAT Gateway is off (see Step 6) - the
# ALB's replacement, CloudFront, is the one remaining fixed cost with no
# stop state; not touched here.
#
# Usage: infra/scripts/staging-env.sh start|stop|status
set -eu

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

# Looked up by tag rather than hardcoded - this instance gets replaced
# (new instance ID) whenever something forces it, e.g. a subnet change.
instance_id() {
	$AWS ec2 describe-instances \
		--filters "Name=tag:Name,Values=cadence-staging-backend" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
		--query 'Reservations[0].Instances[0].InstanceId' --output text
}

instance_status() {
	$AWS ec2 describe-instances --instance-ids "$1" \
		--query 'Reservations[0].Instances[0].State.Name' --output text
}

cmd_status() {
	ID=$(instance_id)
	echo "RDS ($DB_INSTANCE): $(db_status)"
	echo "Backend EC2 ($ID): $(instance_status "$ID")"
}

cmd_stop() {
	ID=$(instance_id)
	echo "Stopping backend EC2 instance $ID..."
	$AWS ec2 stop-instances --instance-ids "$ID" --query 'StoppingInstances[0].CurrentState.Name' --output text

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

	echo "Not touching CloudFront - it's the ALB's replacement, fixed cost, no stop state."
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

	ID=$(instance_id)
	echo "Starting backend EC2 instance $ID..."
	$AWS ec2 start-instances --instance-ids "$ID" --query 'StartingInstances[0].CurrentState.Name' --output text

	echo "Waiting for the instance to pass its status checks..."
	$AWS ec2 wait instance-status-ok --instance-ids "$ID"

	# The Elastic IP re-attaches automatically on start (it's associated with
	# the instance, not something this script manages), but the container
	# itself (systemd's cadence-backend.service, Restart=always) still needs
	# a beat to actually boot - Spring Boot's cold start here runs 50-70s.
	echo "Instance up - the app itself typically takes another 50-70s to finish booting."
	echo "Check: https://api.cadence.bioinform.co.uk/healthz"
}

case "${1:-}" in
start) cmd_start ;;
stop) cmd_stop ;;
status) cmd_status ;;
*) usage ;;
esac
