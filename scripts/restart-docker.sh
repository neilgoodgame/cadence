#!/usr/bin/env bash
# Force-restarts Docker Desktop on macOS when the daemon wedges: Docker processes are
# running but `docker info` / `docker compose ps` hang forever and containers are gone.
#
# A polite `osascript quit` is NOT enough in this state - the stale com.docker.backend
# keeps holding the socket - so this goes straight to SIGKILL, relaunches the app, and
# waits for the daemon to answer.
#
# Usage: scripts/restart-docker.sh
set -uo pipefail

DAEMON_WAIT_SECONDS=240

echo "Killing Docker Desktop processes..."
pkill -9 -f "Docker Desktop" 2>/dev/null
pkill -9 -f com.docker.backend 2>/dev/null
pkill -9 -f com.docker.virtualization 2>/dev/null
pkill -9 -f com.docker.build 2>/dev/null
sleep 3

# com.docker.vmnetd is a privileged launchd helper; it stays and that's fine.
if pgrep -f "com.docker.backend|Docker Desktop" >/dev/null 2>&1; then
  echo "ERROR: Docker processes survived SIGKILL:" >&2
  pgrep -fl "com.docker.backend|Docker Desktop" >&2
  exit 1
fi

echo "Relaunching Docker Desktop..."
open -a Docker

printf "Waiting for the daemon (up to %ss)" "$DAEMON_WAIT_SECONDS"
elapsed=0
until docker info >/dev/null 2>&1; do
  if [ "$elapsed" -ge "$DAEMON_WAIT_SECONDS" ]; then
    printf "\nERROR: daemon did not come up within %ss\n" "$DAEMON_WAIT_SECONDS" >&2
    exit 1
  fi
  sleep 5
  elapsed=$((elapsed + 5))
  printf "."
done
printf "\nDaemon is up: %s\n" "$(docker info --format '{{.ServerVersion}}')"

# Low host disk is the usual cause of the wedge in the first place (Docker's VM stalls
# when the Mac runs out of headroom), so surface it while we're here.
free_gb=$(df -g / | awk 'NR==2 {print $4}')
if [ "${free_gb:-0}" -lt 15 ]; then
  echo "WARNING: only ${free_gb}GB free on /. Docker tends to freeze below ~10GB."
  echo "Consider: docker builder prune -af   (reclaims image build cache)"
fi

echo "Done. Restart your containers with e.g.: cd backend_java && docker compose up -d"
