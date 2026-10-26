# Single VM on Scaleway

Provider-specific steps only. Architecture, compose, Caddyfile, backups and
the AWS-to-VM data migration are in `COMMON.md`. Ranked against the others
in `COMPARISON.md`.

## Cost (read from scaleway.com/en/pricing/virtual-instances on 2026-10-26)

The page's own note: list prices include egress and IPv6 addresses;
**storage (local, block) and attached public IPv4 addresses are billed
separately.** Prices are in euros, shown ex VAT, for zone PAR-1.

| Line | Instance | Spec | €/hour | ~€/month |
|---|---|---|---|---|
| Development | DEV1-S | 2 vCPU, 2 GB, 200 Mbps | 0.00898 | 6.55 (too small) |
| Development | **DEV1-M** | **3 vCPU, 4 GB, 300 Mbps** | **0.0202** | **14.74** |
| Development | DEV1-L | 4 vCPU, 8 GB, 400 Mbps | 0.04284 | 31.27 |
| Development | PLAY2-NANO | 2 vCPU, 4 GB, 200 Mbps | 0.02754 | 20.10 |
| General Purpose | BASIC3-X2C-4G | 2 vCPU, 4 GB, 350 Mbps | 0.03945 | 28.79 |
| Add-on | Flexible public IPv4 | per Scaleway docs, not shown on the page I read | ~0.004 | ~2.92 |

I found no ARM instance with 4 GB under about €20 on this page (I only
read the Development and the start of the General Purpose tables, so a
cost-optimized ARM type further down the list could exist; check the filtered
list with RAM = 4 GB).

**Estimated total for DEV1-M: about €17.7 per month ex VAT** (€14.74 plus
about €2.92 for the IPv4), roughly **€21 with 20% UK VAT** (about £18).
**That is slightly over a $20 ceiling.** I could not confirm from the
pricing page whether DEV1-M's 40 GB local SSD is included in the €14.74 or
shows up as a separate storage line; the earlier research said it is
included. Check the console's cost estimate at server creation. Hourly
billing; no commitment.

The older Stardust instances (about €0.43 per month) are far too small for
a JVM + Postgres.

## Location and architecture

- Regions: Paris (`fr-par`), Amsterdam (`nl-ams`), Warsaw (`pl-waw`). **No
  UK region.** Paris and Amsterdam are 10 to 20 ms from London.
- x86: build `linux/amd64` images.
- Scaleway is French; EU data residency, GDPR DPA available.

## Setup

Console, or the `scw` CLI (`brew install scaleway-cli`, then `scw init`).

```sh
scw instance server create type=DEV1-M image=ubuntu_noble \
    name=cadence zone=fr-par-1 ip=new
# attach your SSH key in the console (Project > SSH keys) before first boot,
# or add it afterwards via cloud-init / the user's authorized_keys
```

Security group (stateful firewall; create before the server and attach at
creation):

```sh
scw instance security-group create name=cadence inbound-default-policy=drop \
    outbound-default-policy=accept stateful=true zone=fr-par-1
# then add rules for TCP 22 (your IP), 80, 443 via console or
# `scw instance security-group create-rule ...`
```

Then over SSH: deploy user, Docker from Docker's apt repo, swap,
`unattended-upgrades`, `/opt/cadence/*` per `COMMON.md`.

Scaleway specifics:
- **Local SSD**: DEV1 uses local storage tied to the instance. Deleting the
  server deletes the data unless you detach/snapshot, so the `COMMON.md`
  offsite backups matter more here than on a network-volume provider.
- **Flexible IP** is a separate billable resource; keep it reserved so DNS
  survives a rebuild, but delete it when decommissioning or it keeps billing.
- **Reverse DNS** is set on the flexible IP in the console.
- **Outbound SMTP** ports are restricted by default; irrelevant (SES uses 443).
- **Stop vs delete**: a stopped instance with a local volume still bills for
  storage and, depending on type, may keep billing compute; read the current
  rules before relying on "stop to save money".

## Terraform

The official `scaleway/scaleway` provider supports
`scaleway_instance_server`, `scaleway_instance_ip`,
`scaleway_instance_security_group` and `scaleway_instance_ip_reverse_dns`.
A module of about 50 lines replaces `modules/ec2`. Not written.

## Provider-specific pros and cons

Pros
- Clean console, good CLI and Terraform provider; closest to AWS ergonomics
  of the budget options.
- Hourly billing with transparent pricing; no commitment.
- EU-owned, mature, with object storage (S3-compatible) on the same account
  if you want an offsite backup target for a few cents.
- Decent default network and a real stateful security-group firewall.

Cons
- **About 4x OVH and slightly over a $20 ceiling** once the IPv4
  address and VAT are added (about €21), and it costs more than Hetzner's
  cost-optimized tier would.
- **No UK region**.
- Older DEV1 hardware (AMD EPYC 7281-era) is slower per core than Hetzner's
  or Vultr's current CPUs; fine for this load, not generous.
- Local-SSD storage: data is lost if the instance is deleted; backups are
  essential.
- Price rose on 2026-06-01 (DEV1-M from €0.0198 to €0.0202 an hour);
  storage and IPv4 are separate line items.
