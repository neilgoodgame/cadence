#!/bin/bash
# Recomputes duration/tss for every workout on your account, picking up the new
# distance+pace duration inference (backend_java PR #292) for any workout that was
# created/last-saved before that fix shipped. Safe to re-run - it's a no-op for
# workouts that don't need it.
#
# Usage:
#   1. Create a Personal Access Token: cadence.bioinform.co.uk -> Preferences -> Tokens
#      -> check "workouts:write" -> Generate.
#   2. PAT=cad_pat_... ./backfill_workout_durations.sh
#
# Your token is only ever read from the PAT env var and passed straight to curl - it's
# never printed, logged, or sent anywhere else.
set -euo pipefail

API_BASE="${API_BASE:-https://api.cadence.bioinform.co.uk}"

if [ -z "${PAT:-}" ]; then
  echo "Usage: PAT=cad_pat_... $0" >&2
  exit 1
fi

ids=$(curl -sf "$API_BASE/v1/workouts" -H "Authorization: Bearer $PAT" | python3 -c "import json,sys; print('\n'.join(w['id'] for w in json.load(sys.stdin)['data']))")

total=$(echo "$ids" | grep -c . || true)
echo "Found $total workout(s)."

i=0
for id in $ids; do
  i=$((i + 1))
  before=$(curl -sf "$API_BASE/v1/workouts/$id" -H "Authorization: Bearer $PAT")
  steps=$(echo "$before" | python3 -c "import json,sys; print(json.dumps(json.load(sys.stdin)['steps']))")
  name=$(echo "$before" | python3 -c "import json,sys; print(json.load(sys.stdin)['name'])")
  before_dur=$(echo "$before" | python3 -c "import json,sys; print(json.load(sys.stdin)['duration'])")
  before_tss=$(echo "$before" | python3 -c "import json,sys; print(json.load(sys.stdin)['tss'])")

  after=$(curl -sf -X PATCH "$API_BASE/v1/workouts/$id" \
    -H "Authorization: Bearer $PAT" -H "Content-Type: application/json" \
    -d "{\"steps\": $steps}")
  after_dur=$(echo "$after" | python3 -c "import json,sys; print(json.load(sys.stdin)['duration'])")
  after_tss=$(echo "$after" | python3 -c "import json,sys; print(json.load(sys.stdin)['tss'])")

  if [ "$before_dur" != "$after_dur" ] || [ "$before_tss" != "$after_tss" ]; then
    echo "[$i/$total] CHANGED  $name ($id): duration $before_dur -> $after_dur, tss $before_tss -> $after_tss"
  else
    echo "[$i/$total] unchanged $name ($id)"
  fi
done

echo "Done."
