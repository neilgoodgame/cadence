# Single VM on Scaleway

Provider-specific steps only. Architecture, compose, Caddyfile, backups and
the AWS-to-VM data migration are in `COMMON.md`. Ranked against the others
in `COMPARISON.md`.

## Cost (checked 2026-10-21 via web search; verify on scaleway.com/en/pricing/virtual-instances)

| Item | Spec | List price (ex VAT) | Notes |
|---|---|---|---|
| **DEV1-M** | 3 vCPU x86 (AMD), 4 GB RAM, 40 GB local SSD | €0.0202/h, **~€14.74/month** | Rate rose from €0.0198/h on 2026-06-01 per Scaleway's pricing blog. |
| Flexible public IPv4 | | roughly €3/month (€0.004/h) | Third-party listings show ~€20.8 all-in; confirm. |
| Snapshots | | per GB-month, small | Before migrations/upgrades. |

**Estimated total: about €15 to 18 per month plus VAT (roughly £13 to 17).**
Traffic is not metered on the instance side for typical use; check current
egress terms. DEV1 is Scaleway's older "development" line; newer
general-purpose lines (PRO2, STARDUST for tiny sizes) cost more per GB of RAM
or are too small for this workload. Stardust instances (about €0.4/month)
are far too small for a JVM + Postgres.

Scaleway's ARM instances are in a different (more expensive) family and I did
not find a 4 GB ARM size inside the $20 envelope; assume **x86**.

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
- **~3x the Hetzner price** for the same 4 GB, and about 50% above OVH.
- **No UK region**.
- Older DEV1 hardware (AMD EPYC 7281-era) is slower per core than Hetzner's
  or Vultr's current CPUs; fine for this load, not generous.
- Local-SSD storage: data is lost if the instance is deleted; backups are
  essential.
- Price rose on 2026-06-01 and the lineup is in flux; check before committing.
