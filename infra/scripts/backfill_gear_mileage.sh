#!/bin/bash
# One-time backfill: adds up every activity already linked to a bike/shoe (bike_id/shoe_id)
# and adds that total onto the gear's current distance_km/km - mileage accrual on link/unlink/
# delete only shipped going forward (backend PR adding GearMileageService/gear/mileage.py), so
# every link that existed *before* that deploy never contributed its distance.
#
# Run this ONCE, immediately after deploying that fix, before linking any NEW gear to an
# activity - a link made after the fix is already correctly counted, so backfilling after that
# point would double-count just that one link.
#
# Dry-run by default (prints what it would change); set APPLY=1 to actually PATCH.
#
# Usage:
#   1. Create a Personal Access Token: cadence.bioinform.co.uk -> Preferences -> Tokens
#      -> check "gear:write" and "activities:read" -> Generate.
#   2. PAT=cad_pat_... ./backfill_gear_mileage.sh          # dry run
#      PAT=cad_pat_... APPLY=1 ./backfill_gear_mileage.sh  # applies it
#
# Your token is only ever read from the PAT env var and passed straight to curl - it's never
# printed, logged, or sent anywhere else.
set -euo pipefail

API_BASE="${API_BASE:-https://api.cadence.bioinform.co.uk}"

if [ -z "${PAT:-}" ]; then
  echo "Usage: PAT=cad_pat_... $0" >&2
  exit 1
fi

bikes=$(curl -sf "$API_BASE/v1/gear/bikes" -H "Authorization: Bearer $PAT")
shoes=$(curl -sf "$API_BASE/v1/gear/shoes" -H "Authorization: Bearer $PAT")

activities="[]"
cursor=""
while :; do
  url="$API_BASE/v1/activities?limit=200"
  [ -n "$cursor" ] && url="$url&cursor=$cursor"
  page=$(curl -sf "$url" -H "Authorization: Bearer $PAT")
  activities=$(python3 -c 'import json,sys; a=json.loads(sys.argv[1]); b=json.load(sys.stdin)["data"]; print(json.dumps(a+b))' "$activities" <<< "$page")
  cursor=$(python3 -c 'import json,sys; c=json.load(sys.stdin).get("next_cursor"); print(c or "")' <<< "$page")
  [ -z "$cursor" ] && break
done

# Written to a temp file rather than fed in as a heredoc, because the heredoc's own stdin
# redirect would otherwise clobber the `<<<` used below to pipe the fetched JSON into this script.
plan_script=$(mktemp)
trap 'rm -f "$plan_script"' EXIT
cat > "$plan_script" <<'PY'
import json, sys
from collections import defaultdict

bikes = json.loads(sys.argv[1])["data"]
shoes = json.loads(sys.argv[2])["data"]
activities = json.loads(sys.argv[3])

bike_sums, shoe_sums = defaultdict(int), defaultdict(int)
for a in activities:
    if a.get("bike_id"):
        bike_sums[a["bike_id"]] += round(a["distance_km"])
    if a.get("shoe_id"):
        shoe_sums[a["shoe_id"]] += round(a["distance_km"])

bikes_by_id = {b["id"]: b for b in bikes}
shoes_by_id = {s["id"]: s for s in shoes}

for bike_id, total in bike_sums.items():
    bike = bikes_by_id.get(bike_id)
    if bike is None or total == 0:
        continue
    print(f"BIKE\t{bike_id}\t{bike['name']}\t{bike['distance_km']}\t{bike['distance_km'] + total}")

for shoe_id, total in shoe_sums.items():
    shoe = shoes_by_id.get(shoe_id)
    if shoe is None or total == 0:
        continue
    print(f"SHOE\t{shoe_id}\t{shoe['name']}\t{shoe['km']}\t{shoe['km'] + total}")
PY

plan=$(python3 "$plan_script" "$bikes" "$shoes" "$activities")

if [ -z "$plan" ]; then
  echo "Nothing to backfill - no bike/shoe has any currently-linked activity distance to add."
  exit 0
fi

echo "$plan" | while IFS=$'\t' read -r kind id name before after; do
  echo "$kind $id \"$name\": $before -> $after km"
done

if [ "${APPLY:-0}" != "1" ]; then
  echo
  echo "Dry run only - re-run with APPLY=1 to apply the above."
  exit 0
fi

echo
while IFS=$'\t' read -r kind id name before after; do
  if [ "$kind" = "BIKE" ]; then
    curl -sf -X PATCH "$API_BASE/v1/gear/bikes/$id" \
      -H "Authorization: Bearer $PAT" -H "Content-Type: application/json" \
      -d "{\"distance_km\": $after}" > /dev/null
  else
    curl -sf -X PATCH "$API_BASE/v1/gear/shoes/$id" \
      -H "Authorization: Bearer $PAT" -H "Content-Type: application/json" \
      -d "{\"km\": $after}" > /dev/null
  fi
  echo "applied   $kind $id \"$name\": $before -> $after km"
done <<< "$plan"

echo "Done."
