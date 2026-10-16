#!/bin/sh
# Dump staging's RDS Postgres (through the SSM-tunneled EC2 bastion - see
# infra/RUNBOOK.md §5) and/or restore that dump into the local Java backend's
# `db` container, so local profiling (see backend_java/CLAUDE.md's
# "Memory-constrained profiling" section) can run against realistic data
# instead of an empty dev DB.
#
# Usage:
#   infra/scripts/sync-staging-db.sh dump [-c|--compressed] [file]   # staging -> local file only
#   infra/scripts/sync-staging-db.sh import [file]                   # local file -> local db only
#   infra/scripts/sync-staging-db.sh sync [-c|--compressed] [file]   # dump, then import
#
# [file] defaults to backend_java/.pgdumps/staging-<timestamp>.sql (or .dump, if
# -c/--compressed) for dump/sync, or the most recently created file in that
# directory for import.
#
# -c/--compressed uses pg_dump's custom format (-Fc) instead of plain SQL: it
# compresses inline as it writes rather than dumping text and compressing it
# as a separate pass, so it's both smaller on disk and quicker to produce for
# a DB this size. `import` auto-detects which format a file is (plain SQL vs.
# custom-format, by sniffing for the latter's "PGDMP" magic bytes) and
# restores via psql or pg_restore accordingly - no flag needed on import.
#
# `import` always drops and recreates the local `db` container's volume first -
# the dump's own CREATE TABLE statements collide with the already-Flyway-
# migrated empty schema otherwise. Local dev data in that volume is trivially
# recreated (`docker compose up -d` re-migrates from scratch), so this isn't
# treated as a destructive operation needing confirmation - same reasoning as
# deploy-backend.sh's own uncommitted-changes warning.
#
# Dumps land in backend_java/.pgdumps/, gitignored - this is a full copy of
# whatever's in staging (real emails, activity data, ...), never meant to be
# committed or shared.
set -eu

PROFILE="cadence-terraform"
REGION="eu-west-2"
AWS="aws --profile $PROFILE --region $REGION"
DB_INSTANCE="cadence-staging"
STAGING_DB_NAME="cadence"
LOCAL_PORT="15432"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_DIR="$(cd "$SCRIPT_DIR/../../backend_java" && pwd)"
DUMP_DIR="$COMPOSE_DIR/.pgdumps"
DB_CONTAINER="backend_java-db-1"
LOCAL_DB_NAME="cadence_java"
LOCAL_DB_USER="cadence"

usage() {
	echo "Usage: $0 dump|sync [-c|--compressed] [file]" >&2
	echo "       $0 import [file]" >&2
	exit 1
}

default_dump_file() {
	mkdir -p "$DUMP_DIR"
	if [ "$1" = "compressed" ]; then
		echo "$DUMP_DIR/staging-$(date +%Y%m%d-%H%M%S).dump"
	else
		echo "$DUMP_DIR/staging-$(date +%Y%m%d-%H%M%S).sql"
	fi
}

latest_dump_file() {
	ls -t "$DUMP_DIR"/*.sql "$DUMP_DIR"/*.dump 2>/dev/null | head -1
}

# dump/sync take an optional -c/--compressed flag and an optional file,
# in either order - this sets $FORMAT and $FILE from "$@".
parse_dump_args() {
	FORMAT="plain"
	FILE=""
	for arg in "$@"; do
		case "$arg" in
		-c | --compressed) FORMAT="compressed" ;;
		*) FILE="$arg" ;;
		esac
	done
}

TUNNEL_PID=""

start_tunnel() {
	if lsof -nP -iTCP:"$LOCAL_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
		echo "Port $LOCAL_PORT already has a listener - reusing it instead of opening a second tunnel."
		return
	fi

	INSTANCE_ID=$($AWS ec2 describe-instances \
		--filters "Name=tag:Name,Values=cadence-staging-backend" "Name=instance-state-name,Values=running" \
		--query 'Reservations[0].Instances[0].InstanceId' --output text)
	RDS_ENDPOINT=$($AWS rds describe-db-instances --db-instance-identifier "$DB_INSTANCE" \
		--query 'DBInstances[0].Endpoint.Address' --output text)

	echo "Opening SSM tunnel to $RDS_ENDPOINT via $INSTANCE_ID on localhost:$LOCAL_PORT..."
	$AWS ssm start-session \
		--target "$INSTANCE_ID" \
		--document-name AWS-StartPortForwardingSessionToRemoteHost \
		--parameters "{\"host\":[\"$RDS_ENDPOINT\"],\"portNumber\":[\"5432\"],\"localPortNumber\":[\"$LOCAL_PORT\"]}" \
		>/tmp/ssm-tunnel-staging-db.log 2>&1 &
	TUNNEL_PID=$!
	trap 'stop_tunnel' EXIT

	i=0
	while [ "$i" -lt 20 ]; do
		lsof -nP -iTCP:"$LOCAL_PORT" -sTCP:LISTEN >/dev/null 2>&1 && break
		sleep 1
		i=$((i + 1))
	done
	echo "Tunnel ready."
}

stop_tunnel() {
	if [ -n "$TUNNEL_PID" ]; then
		kill "$TUNNEL_PID" 2>/dev/null || true
	fi
}

cmd_dump() {
	FORMAT="$1"
	OUT="${2:-$(default_dump_file "$FORMAT")}"
	start_tunnel

	SECRET_ARN=$($AWS rds describe-db-instances --db-instance-identifier "$DB_INSTANCE" \
		--query 'DBInstances[0].MasterUserSecret.SecretArn' --output text)

	# --no-owner/--no-acl are plain-format-only in pg_dump; for -Fc the
	# equivalent flags go on pg_restore at import time instead.
	if [ "$FORMAT" = "compressed" ]; then
		echo "Dumping staging's '$STAGING_DB_NAME' database (compressed) to $OUT..."
		PGPASSWORD=$($AWS secretsmanager get-secret-value --secret-id "$SECRET_ARN" \
			--query SecretString --output text | jq -r .password) \
			pg_dump -h localhost -p "$LOCAL_PORT" -U cadence -d "$STAGING_DB_NAME" -Fc -f "$OUT"
	else
		echo "Dumping staging's '$STAGING_DB_NAME' database to $OUT..."
		PGPASSWORD=$($AWS secretsmanager get-secret-value --secret-id "$SECRET_ARN" \
			--query SecretString --output text | jq -r .password) \
			pg_dump -h localhost -p "$LOCAL_PORT" -U cadence -d "$STAGING_DB_NAME" --no-owner --no-acl -f "$OUT"
	fi

	echo "Dump written to $OUT"
}

cmd_import() {
	FILE="${1:-$(latest_dump_file)}"
	if [ -z "$FILE" ] || [ ! -f "$FILE" ]; then
		echo "No dump file found (looked in $DUMP_DIR) and none given - run 'dump' first or pass a path." >&2
		exit 1
	fi

	echo "Resetting the local db container/volume so the dump's schema has a clean target..."
	(cd "$COMPOSE_DIR" && docker compose --profile memcapped down)
	docker volume rm backend_java_postgres_data >/dev/null 2>&1 || true
	(cd "$COMPOSE_DIR" && docker compose up -d db)

	echo "Waiting for local Postgres to accept connections..."
	i=0
	while [ "$i" -lt 20 ]; do
		docker exec "$DB_CONTAINER" pg_isready -U "$LOCAL_DB_USER" >/dev/null 2>&1 && break
		sleep 1
		i=$((i + 1))
	done

	echo "Importing $FILE into local '$LOCAL_DB_NAME'..."
	if [ "$(head -c 5 "$FILE" 2>/dev/null)" = "PGDMP" ]; then
		docker exec -i "$DB_CONTAINER" pg_restore -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" \
			--no-owner --no-acl <"$FILE" >/dev/null
	else
		docker exec -i "$DB_CONTAINER" psql -q -U "$LOCAL_DB_USER" -d "$LOCAL_DB_NAME" <"$FILE" >/dev/null
	fi
	echo "Import complete."
}

cmd_sync() {
	FORMAT="$1"
	FILE="${2:-$(default_dump_file "$FORMAT")}"
	cmd_dump "$FORMAT" "$FILE"
	cmd_import "$FILE"
}

CMD="${1:-}"
if [ -n "$CMD" ]; then
	shift
fi

case "$CMD" in
dump)
	parse_dump_args "$@"
	cmd_dump "$FORMAT" "$FILE"
	;;
import) cmd_import "${1:-}" ;;
sync)
	parse_dump_args "$@"
	cmd_sync "$FORMAT" "$FILE"
	;;
*) usage ;;
esac
