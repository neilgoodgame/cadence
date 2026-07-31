#!/usr/bin/env bash
# Reclaims disk space from Docker without touching anything running or any volume data.
# Low host disk is what wedges the daemon in the first place (see restart-docker.sh's
# comment on that), so this is worth running periodically, not just as a one-off fix.
#
# Only removes: dangling (untagged) images, unused networks, and build cache. Does NOT
# touch running containers, tagged images still in use, or volumes - volume data (e.g.
# a stopped project's Postgres data) is real, possibly-wanted data, not reclaimable junk,
# so pruning it is a separate, explicit decision this script deliberately doesn't make.
#
# Usage: scripts/docker-prune.sh
set -uo pipefail

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker daemon isn't responding. Try scripts/restart-docker.sh first." >&2
  exit 1
fi

echo "Before:"
docker system df

echo
echo "Pruning unused containers/networks/dangling images..."
docker system prune -f

echo
echo "Pruning build cache..."
docker builder prune -af

echo
echo "After:"
docker system df

echo
echo "Still running (untouched):"
docker ps --format "table {{.Names}}\t{{.Status}}"
