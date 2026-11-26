#!/usr/bin/env bash
# Dynamic DNS updater for Route 53: keeps host.bioinform.co.uk pointing at
# this machine's public IP. Run it on the home machine every few minutes, e.g.
#   */5 * * * * DDNS_ZONE_ID=Z... /usr/local/bin/ddns-update >> ~/.cache/ddns-update.log 2>&1
# It only calls Route 53 when the IP has changed.
#
# Needs: aws CLI v2, curl, and an AWS profile whose IAM permissions are:
#   route53:ChangeResourceRecordSets  on arn:aws:route53:::hostedzone/<ZONE_ID>
#     (can be limited with conditions
#      route53:ChangeResourceRecordSetsNormalizedRecordNames = host.bioinform.co.uk,
#      route53:ChangeResourceRecordSetsActions = UPSERT,
#      route53:ChangeResourceRecordSetsRecordTypes = A, AAAA)
#   route53:ListResourceRecordSets    on arn:aws:route53:::hostedzone/<ZONE_ID>
#   route53:GetChange                 on arn:aws:route53:::change/*
#
# Env:
#   DDNS_ZONE_ID      (required) Route 53 hosted zone id for bioinform.co.uk
#   DDNS_HOSTNAME     default host.bioinform.co.uk
#   DDNS_TTL          default 60
#   DDNS_AWS_PROFILE  default bioinform-ddns
#   DDNS_IPV6         set to 1 to also maintain an AAAA record
set -euo pipefail

ZONE_ID="${DDNS_ZONE_ID:?set DDNS_ZONE_ID to the bioinform.co.uk hosted zone id}"
NAME="${DDNS_HOSTNAME:-host.bioinform.co.uk}"
TTL="${DDNS_TTL:-60}"
export AWS_PROFILE="${DDNS_AWS_PROFILE:-bioinform-ddns}"

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }

public_ip() {
  local family="$1" ip=""
  if [ "$family" = 4 ]; then
    ip="$(curl -4 -fsS --max-time 10 https://checkip.amazonaws.com 2>/dev/null || true)"
    [ -n "$ip" ] || ip="$(curl -4 -fsS --max-time 10 https://api.ipify.org 2>/dev/null || true)"
    ip="$(printf '%s' "$ip" | tr -d '[:space:]')"
    [[ "$ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] || return 1
  else
    ip="$(curl -6 -fsS --max-time 10 https://api64.ipify.org 2>/dev/null || true)"
    ip="$(printf '%s' "$ip" | tr -d '[:space:]')"
    [[ "$ip" =~ ^[0-9a-fA-F:]+$ && "$ip" == *:* ]] || return 1
  fi
  printf '%s' "$ip"
}

current_value() {
  local type="$1"
  aws route53 list-resource-record-sets \
    --hosted-zone-id "$ZONE_ID" \
    --start-record-name "$NAME" --start-record-type "$type" --max-items 1 \
    --query "ResourceRecordSets[?Name=='${NAME}.' && Type=='${type}'].ResourceRecords[0].Value | [0]" \
    --output text
}

update() {
  local type="$1" ip="$2" current
  current="$(current_value "$type")"
  if [ "$current" = "$ip" ]; then
    return 0
  fi
  log "$NAME $type: ${current:-<none>} -> $ip"
  local change_id
  change_id="$(aws route53 change-resource-record-sets \
    --hosted-zone-id "$ZONE_ID" \
    --change-batch "{\"Comment\":\"ddns-update\",\"Changes\":[{\"Action\":\"UPSERT\",\"ResourceRecordSet\":{\"Name\":\"$NAME\",\"Type\":\"$type\",\"TTL\":$TTL,\"ResourceRecords\":[{\"Value\":\"$ip\"}]}}]}" \
    --query ChangeInfo.Id --output text)"
  aws route53 wait resource-record-sets-changed --id "$change_id"
  log "$NAME $type: in sync"
}

if ip4="$(public_ip 4)"; then
  update A "$ip4"
else
  log "could not determine public IPv4 address; skipping A record" >&2
  status=1
fi

if [ "${DDNS_IPV6:-0}" = 1 ]; then
  if ip6="$(public_ip 6)"; then
    update AAAA "$ip6"
  else
    log "could not determine public IPv6 address; skipping AAAA record" >&2
    status=1
  fi
fi

exit "${status:-0}"
