# Single VM on OVHcloud VPS

Provider-specific steps only. Architecture, compose, Caddyfile, backups and
the AWS-to-VM data migration are in `COMMON.md`. Ranked against the others
in `COMPARISON.md`.

## Cost (read from ovhcloud.com/en-gb/vps and its configurator on 2026-10-26)

OVH's current UK range is the "VPS 2027" line, and it is far cheaper than my
earlier web-search estimate (which assumed about €10 for 4 GB). Prices are
shown ex VAT, with the VAT-inclusive figure alongside on the plan page.

| Plan | Spec | 12-month commitment (per month) | No commitment (per month) |
|---|---|---|---|
| **VPS-1** | **2 vCores, 4 GB RAM, 40 GB NVMe**, 500 Mbps, unlimited traffic | **£3.31 ex VAT (£3.97 inc)** | **£3.90 ex VAT (about £4.68 inc)** |
| VPS-2 | 4 vCores, 8 GB RAM, 75 GB NVMe, 1 Gbps | £6.29 ex VAT (£7.55 inc) | not read (about £7.40 ex VAT if the same 15% discount applies) |
| VPS-3 | 6 vCores, 12 GB RAM, 100 GB NVMe, 2 Gbps | £9.01 ex VAT | not read |
| VPS-4 | 8 vCores, 24 GB RAM, 200 GB NVMe, 3 Gbps | £17.34 ex VAT | not read |
| Commitment discounts | 6 months: 5% off; 12 months: 15% off | | |
| Snapshot backup (optional add-on) | on-demand image of the server | price not read | |

The configurator showed no extra charge for the public IPv4 address, the
OS image (Ubuntu 26.04, Debian 13 and others are free) or storage ("included").

**Estimated total for the VPS-1 plan: about £4 to 5 per month including
VAT** (£3.97 on a 12-month term, about £4.68 month to month). VPS-2 at about
£7.5 to 9 is the headroom option and has double the disk and RAM.
By a wide margin this is the cheapest provider with a 4 GB plan here.

Both plans have **daily backup of the previous 24 hours included** and
unlimited traffic. That provider-side backup is a convenience only, not
the plan: `COMMON.md`'s encrypted offsite dumps still apply (a same-provider
backup does not survive an account or provider incident, and it covers
only the last 24 hours).

Sizing check: VPS-1 has exactly the 4 GB RAM `COMMON.md` budgets
(about 3.0 GB used), leaving about 1 GB of headroom, with 40 GB of disk. Check
`du -sh /opt/cadence/media` and the database size on staging first; if
they are more than a few GB, or you want RAM headroom, take VPS-2.

## Location and architecture

- The configurator offers **Europe (United Kingdom, Erith, Greater
  London)** and Europe (France, Strasbourg), both "Available now" at the same
  price for VPS-1. More locations need another VPS offer. Choose the UK one.
  Erith is a London-area datacentre, so UK latency and UK data residency.
- x86 only. Build `linux/amd64` images (`COMMON.md` deploy snippet).
- OVH is French; DPA and GDPR tooling are standard. UK location keeps data
  in the UK.

## Setup

OVH VPS is ordered and managed through the OVHcloud Control Panel; there is
no first-class CLI for ordering and Terraform cannot order a VPS (it can
manage DNS and some existing resources). Treat the server as hand-made and
document it.

1. Order VPS-1 (or VPS-2 for headroom), choose **Ubuntu 26.04** (or
   Debian 13; both are free images), location **UK (Erith)**, upload your SSH public key at order time (otherwise OVH
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
- **UK datacentre (Erith, Greater London)**, with unmetered traffic.
- Lowest price here by far: about £4 to 5/month for 4 GB, roughly a fifth
  of Hetzner CPX22 and a third of Scaleway.
- Daily provider backup of the previous 24 hours included.
- Anti-DDoS included; mature, large operator.
- Long-standing VPS product with simple fixed pricing and optional
  12-month discounts.

Cons
- VPS-1 has a 40 GB disk and a 500 Mbps port; check data and media size
  first, or use VPS-2 (75 GB, 1 Gbps).
- The lowest prices need a 12-month commitment (15% off); month to month is
  £3.90 ex VAT.
- No CLI/Terraform ordering, so the server is a hand-built pet; document it
  in a runbook.
- Control panel and support are rougher than Hetzner or Scaleway; account
  verification delays are common.
- x86 only, so the image build must switch from arm64 to amd64.
- Docker + `ufw` interaction (above) is a footgun.
