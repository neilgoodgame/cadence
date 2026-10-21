# Single VM on OVHcloud VPS

Provider-specific steps only. Architecture, compose, Caddyfile, backups and
the AWS-to-VM data migration are in `COMMON.md`. Ranked against the others
in `COMPARISON.md`.

## Cost (checked 2026-10-21 via web search; secondary sources, verify on ovhcloud.com)

OVH repositioned its VPS range in 2025/26 (VPS-1 to VPS-6 tiers), with a
price rise of roughly 30% on the entry tier. I could not confirm a London
price directly.

| Plan | Spec | List price | Notes |
|---|---|---|---|
| VPS-1 | entry tier | ~$6.5 | Too small (RAM); do not use. |
| **VPS-2** | listed variously as 4+ GB RAM; one listing shows 6 vCores / 12 GB | ~€9.99/month (€8.49 with a 12-month term) | **Confirm RAM, disk and location before ordering**; sources conflict. |
| Snapshot / backup add-ons | | extra | Optional. |

**Estimated total: about €10 per month (about £9), possibly €8.49 on a
12-month commitment.** Traffic is unmetered at a fixed port speed.
Need at least 4 GB RAM; if VPS-2 turns out smaller, use the next tier up
(check it is still $20 or under).

## Location and architecture

- Regions include **London (UK)**, Gravelines (FR), Strasbourg, Frankfurt,
  Warsaw and others; availability varies by plan, so London must be selected
  at order time and confirmed on the plan you pick. OVH's UK VPS has been
  sold from London for years, but verify for the current range.
- x86 only. Build `linux/amd64` images (`COMMON.md` deploy snippet).
- OVH is French; DPA and GDPR tooling are standard. UK location keeps data
  in the UK.

## Setup

OVH VPS is ordered and managed through the OVHcloud Control Panel; there is
no first-class CLI for ordering and Terraform cannot order a VPS (it can
manage DNS and some existing resources). Treat the server as hand-made and
document it.

1. Order VPS-2 (or the confirmed 4 GB+ tier), choose **Ubuntu 24.04**,
   location London, upload your SSH public key at order time (otherwise OVH
   emails a root password; change it immediately and disable password auth).
2. Wait for the "ready" email (usually minutes, sometimes longer on a new
   account).
3. SSH in as the default user (`ubuntu` or `root` depending on image). Create
   the deploy user, install Docker from Docker's apt repo, add swap, enable
   `unattended-upgrades`, create `/opt/cadence/*` per `COMMON.md`.

OVH specifics:
- **Firewall**: the VPS has no stateful cloud firewall by default (the
  Edge Network Firewall belongs to the IP and is separate). Use `ufw`
  on the host: allow 22 (from your IP), 80, 443; default deny inbound.
  Remember Docker publishes ports by editing iptables and bypasses `ufw`
  for published ports. Only Caddy publishes 80/443 in the compose file, so
  this is safe as written; do **not** add `ports:` to `db` or `backend`.
- **Anti-DDoS** is included at no cost, which Hetzner and Scaleway do not
  match by default.
- **IP**: one IPv4 and IPv6 included; reverse DNS settable in the panel.
- **Backups**: OVH sells automated backups and snapshots; a snapshot before
  upgrades is worthwhile, but the plan is `COMMON.md`'s.
- **Rescue mode** (netboot rescue from the panel) is the recovery route if a
  bad config locks you out of SSH. There is no web console of the same
  quality as Hetzner's, so test SSH changes in a second session.
- **Account verification**: new OVH accounts often get a manual ID check
  before provisioning; expect a delay of hours to days.

## Terraform

Not applicable to ordering. The `ovh/ovh` provider can manage DNS zones if
you move DNS to OVH, which is not required.

## Provider-specific pros and cons

Pros
- **UK (London) region available**, with unmetered traffic.
- Low price: about half the Vultr and Scaleway cost.
- Anti-DDoS included; mature, large operator.
- Long-standing VPS product with simple fixed pricing and optional
  12-month discounts.

Cons
- **Sources conflict on plan specs and London pricing**; verify before ordering.
- No CLI/Terraform ordering, so the server is a hand-built pet; document it
  in a runbook.
- Control panel and support are rougher than Hetzner or Scaleway; account
  verification delays are common.
- x86 only, so the image build must switch from arm64 to amd64.
- Docker + `ufw` interaction (above) is a footgun.
