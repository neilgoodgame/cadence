#!/bin/sh
# Generic concurrent load/stress test against a running backend instance. By
# default it self-registers a throwaway account (no setup needed) and hits a
# handful of representative authenticated endpoints concurrently for a
# duration - useful for driving sustained request-rate load while watching
# the JVM (see backend_java/CLAUDE.md's "Memory-constrained profiling"
# section) without needing a human to click through the UI. Also pulls every
# course and race up front and fans out to each one's detail endpoints -
# course sections/climbs and the race training block are the heaviest reads
# in the whole API - read-only throughout.
#
# For data-weighted stress against a real, populated account (e.g. after
# infra/scripts/sync-staging-db.sh), set PAT to a Personal Access Token for
# that account instead (Preferences -> Tokens in the running frontend) - the
# script skips registration and uses it directly, so list/calendar endpoints
# return real-sized result sets instead of an empty throwaway account's.
#
# Usage:
#   infra/scripts/stress-test-backend.sh [base_url] [concurrency] [duration_seconds]
#
# Defaults: base_url=http://localhost:8080, concurrency=10, duration=60
set -eu

BASE_URL="${1:-http://localhost:8080}"
CONCURRENCY="${2:-10}"
DURATION="${3:-60}"

WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

if [ -n "${PAT:-}" ]; then
	echo "Using provided PAT against $BASE_URL..."
	TOKEN="$PAT"
else
	EMAIL="stress-$(date +%s)-$$@example.com"
	PASSWORD="stress-test-password-1"
	echo "Registering throwaway account $EMAIL against $BASE_URL..."
	REGISTER=$(curl -sf -X POST "$BASE_URL/v1/auth/register" \
		-H "Content-Type: application/json" \
		-d "{\"name\": \"Stress Test\", \"email\": \"$EMAIL\", \"password\": \"$PASSWORD\"}")
	TOKEN=$(echo "$REGISTER" | python3 -c "import json,sys; print(json.load(sys.stdin)['tokens']['access_token'])")
fi

# calendar's from/to are required - a wide fixed range needs no date math and
# covers any real account's history.
ENDPOINTS="/v1/activities /v1/workouts /v1/calendar?from=2000-01-01&to=2035-01-01 /v1/courses /v1/races"

# Pull every course once up front and add its detail/GPX endpoints to the
# rotation - getCourse rebuilds sections/climbs from the stored elevation
# series on every read, so it's a meaningfully heavier endpoint than the
# flat list ones above. Read-only: deliberately not including
# recompute-elevation/rename/delete, which would mutate real course data.
echo "Fetching course ids from $BASE_URL..."
COURSES=$(curl -sf -H "Authorization: Bearer $TOKEN" "$BASE_URL/v1/courses")
COURSE_IDS=$(echo "$COURSES" | python3 -c "import json,sys; print('\n'.join(c['id'] for c in json.load(sys.stdin)['data']))")
COURSE_COUNT=$(echo "$COURSE_IDS" | grep -c . || true)
echo "Found $COURSE_COUNT course(s) - adding their detail/GPX endpoints to the rotation."

for cid in $COURSE_IDS; do
	ENDPOINTS="$ENDPOINTS /v1/courses/$cid /v1/courses/$cid/gpx"
done

# Same for races - block is the heaviest read in the whole API (full training-block
# computation: CTL/ATL, key sessions, durability, prediction). weather calls an
# external upstream (Open-Meteo) but only for a race within 10 days out; every other
# race short-circuits to available=false without the network call. Read-only: not
# including create/update/delete/comment-post.
echo "Fetching race ids from $BASE_URL..."
RACES=$(curl -sf -H "Authorization: Bearer $TOKEN" "$BASE_URL/v1/races")
RACE_IDS=$(echo "$RACES" | python3 -c "import json,sys; print('\n'.join(r['id'] for r in json.load(sys.stdin)['data']))")
RACE_COUNT=$(echo "$RACE_IDS" | grep -c . || true)
echo "Found $RACE_COUNT race(s) - adding their detail/block/splits/weather/comments endpoints to the rotation."

for rid in $RACE_IDS; do
	ENDPOINTS="$ENDPOINTS /v1/races/$rid /v1/races/$rid/block /v1/races/$rid/splits /v1/races/$rid/weather /v1/races/$rid/comments"
done

# A race's linked activity (if any) gets its 1Hz stream pulled too - parallel
# time/position/power/HR/etc. arrays at full resolution, by far the heaviest
# single-request payload in the API and a good proxy for the kind of large
# result-set processing that mattered to the original OOM. Its elevation
# recompute is also fair game here even though it's a write: it deterministically
# rebuilds the same stored series from the same source records every time, so
# hammering it doesn't drift real data, just redoes the same computation -
# unlike recompute-elevation on a course, which isn't in this rotation.
ACTIVITY_IDS=$(echo "$RACES" | python3 -c "import json,sys; print('\n'.join(r['activity_id'] for r in json.load(sys.stdin)['data'] if r.get('activity_id')))")
ACTIVITY_COUNT=$(echo "$ACTIVITY_IDS" | grep -c . || true)
echo "Found $ACTIVITY_COUNT race(s) with a linked activity - adding their stream/recompute-elevation endpoints to the rotation."

POST_ENDPOINTS=""
for aid in $ACTIVITY_IDS; do
	ENDPOINTS="$ENDPOINTS /v1/activities/$aid/streams"
	POST_ENDPOINTS="$POST_ENDPOINTS /v1/activities/$aid/recompute-elevation"
done

# Non-2xx responses get logged here (status + method + path) instead of just
# counted, so a run with errors is self-diagnosing - concurrent workers appending
# short lines to one file is good enough for this, no locking needed.
ERROR_LOG="/tmp/stress-test-errors-$$.log"
: >"$ERROR_LOG"

worker() {
	ID="$1"
	COUNT=0
	ERRORS=0
	END=$(($(date +%s) + DURATION))
	while [ "$(date +%s)" -lt "$END" ]; do
		# `|| STATUS=000`: curl itself exits nonzero on a transport failure (connection
		# refused, timeout) even without -f - without this fallback, set -e would kill
		# this whole worker on the first transient blip and it'd never run again for the
		# rest of the test. 000 isn't a real HTTP status, so it always falls into the
		# error branch below and gets logged/counted like any other failure.
		STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_URL/healthz") || STATUS=000
		case "$STATUS" in
		2*) ;;
		*)
			ERRORS=$((ERRORS + 1))
			echo "$STATUS GET /healthz" >>"$ERROR_LOG"
			;;
		esac
		COUNT=$((COUNT + 1))
		for ep in $ENDPOINTS; do
			STATUS=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "$BASE_URL$ep") || STATUS=000
			case "$STATUS" in
			2*) ;;
			*)
				ERRORS=$((ERRORS + 1))
				echo "$STATUS GET $ep" >>"$ERROR_LOG"
				;;
			esac
			COUNT=$((COUNT + 1))
		done
		# 422 here just means the linked activity isn't a run/walk/ride - expected
		# for some real accounts, not counted as a real error.
		for ep in $POST_ENDPOINTS; do
			STATUS=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $TOKEN" "$BASE_URL$ep") || STATUS=000
			case "$STATUS" in
			2* | 422) ;;
			*)
				ERRORS=$((ERRORS + 1))
				echo "$STATUS POST $ep" >>"$ERROR_LOG"
				;;
			esac
			COUNT=$((COUNT + 1))
		done
	done
	echo "$COUNT $ERRORS" >"$WORKDIR/worker-$ID"
}

echo "Running $CONCURRENCY workers for ${DURATION}s against $BASE_URL..."
START=$(date +%s)
i=0
while [ "$i" -lt "$CONCURRENCY" ]; do
	worker "$i" &
	i=$((i + 1))
done
wait
ELAPSED=$(($(date +%s) - START))
[ "$ELAPSED" -gt 0 ] || ELAPSED=1

TOTAL=0
ERR_TOTAL=0
for f in "$WORKDIR"/worker-*; do
	[ -f "$f" ] || continue
	read -r c e <"$f"
	TOTAL=$((TOTAL + c))
	ERR_TOTAL=$((ERR_TOTAL + e))
done

echo "Done. $TOTAL requests ($ERR_TOTAL errors) in ${ELAPSED}s (~$((TOTAL / ELAPSED)) req/s)."
if [ "$ERR_TOTAL" -gt 0 ]; then
	echo "Error breakdown (status method path):"
	sort "$ERROR_LOG" | uniq -c | sort -rn
	echo "Full log: $ERROR_LOG"
fi
