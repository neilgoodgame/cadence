#!/bin/bash
# Fixes the display name of shoes whose catalog version was synthesized to "1" by the pre-fix
# CSV importer (gear PR fixing "Nike Vomero Plus 1") for shoe lines Nike/Adidas never actually
# numbered (e.g. "Vomero Plus", "Evo SL") - the importer used to invent a version "1" for any
# row with no Version column, so a shoe like that got "1" baked permanently into its name. The
# importer no longer does this, but a shoe imported/created before the fix keeps its old name
# until resaved - this renames exactly those. Safe to re-run: a shoe whose name no longer
# matches the old synthesized pattern (already fixed, or renamed by the athlete since) is left
# untouched.
#
# The catalog's own version string ("1") is NOT touched here - there's no API to edit an
# existing catalog version in place, only the athlete-visible shoe name. Importing another shoe
# of one of these models without a version will create a second, blank-version catalog entry
# alongside the old "1" one; ask an admin to clean that up by hand on the catalog screen if it
# happens.
#
# Also only reaches active shoes - GET /v1/gear/shoes excludes retired ones, and there's no
# "include retired" option on that endpoint.
#
# Usage:
#   1. Create a Personal Access Token: cadence.bioinform.co.uk -> Preferences -> Tokens
#      -> check "gear:write" -> Generate.
#   2. PAT=cad_pat_... ./backfill_shoe_version_names.sh
#
# Your token is only ever read from the PAT env var and passed straight to curl - it's never
# printed, logged, or sent anywhere else.
set -euo pipefail

API_BASE="${API_BASE:-https://api.cadence.bioinform.co.uk}"

if [ -z "${PAT:-}" ]; then
  echo "Usage: PAT=cad_pat_... $0" >&2
  exit 1
fi

# manufacturer+model pairs known to have had a version synthesized by the old importer - these
# product lines were never actually numbered by Nike/Adidas, so "1" is always fake here. Not a
# generic "any shoe with version 1" heuristic: a real first-generation numbered shoe must never
# be touched by this.
ALLOWLIST='[
  ["Nike", "Pegasus Plus"],
  ["Nike", "Pegasus Premium"],
  ["Nike", "Vomero Plus"],
  ["Nike", "Vomero Premium"],
  ["Adidas", "Evo SL"]
]'

shoes=$(curl -sf "$API_BASE/v1/gear/shoes" -H "Authorization: Bearer $PAT")

# Written to a temp file rather than fed in as a heredoc, because the heredoc's own stdin
# redirect would otherwise clobber the `<<<` used below to pipe $shoes into this script.
plan_script=$(mktemp)
trap 'rm -f "$plan_script"' EXIT
cat > "$plan_script" <<'PY'
import json, sys

allowlist = {tuple(pair) for pair in json.loads(sys.argv[1])}
shoes = json.load(sys.stdin)["data"]

for shoe in shoes:
    key = (shoe["manufacturer"], shoe["model"])
    if key not in allowlist or shoe["version"] != "1":
        continue

    parts_old = [shoe["manufacturer"], shoe["model"], "1"]
    parts_new = [shoe["manufacturer"], shoe["model"]]
    if shoe["colourway"]:
        parts_old.append(shoe["colourway"])
        parts_new.append(shoe["colourway"])
    old_name = " ".join(parts_old)
    new_name = " ".join(parts_new)

    if shoe["name"] != old_name:
        print(f"SKIP\t{shoe['id']}\t{shoe['name']}")
    else:
        print(f"RENAME\t{shoe['id']}\t{old_name}\t{new_name}")
PY

plan=$(python3 "$plan_script" "$ALLOWLIST" <<< "$shoes")

if [ -z "$plan" ]; then
  echo "No shoes matched the known-affected models. Nothing to do."
  exit 0
fi

total=$(echo "$plan" | grep -c "^RENAME" || true)
echo "Found $total shoe(s) to rename."

i=0
while IFS=$'\t' read -r action id a b; do
  if [ "$action" = "SKIP" ]; then
    echo "skip      $id: \"$a\" (already fixed, or renamed by the athlete)"
    continue
  fi
  i=$((i + 1))
  old_name="$a"
  new_name="$b"
  curl -sf -X PATCH "$API_BASE/v1/gear/shoes/$id" \
    -H "Authorization: Bearer $PAT" -H "Content-Type: application/json" \
    -d "$(python3 -c 'import json,sys; print(json.dumps({"name": sys.argv[1]}))' "$new_name")" \
    > /dev/null
  echo "[$i/$total] renamed   $id: \"$old_name\" -> \"$new_name\""
done <<< "$plan"

echo "Done."
