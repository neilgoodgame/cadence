# Single VM on Hetzner Cloud

Provider-specific steps only. Architecture, compose, Caddyfile, backups and
the AWS-to-VM data migration are in `COMMON.md`. Ranked against the others
in `COMPARISON.md`.

## Cost (checked 2026-10-21 via web search; secondary sources, verify on hetzner.com/cloud)

Hetzner raised prices about 30% on 2026-04-01, so older figures you may
have seen (CX22 at about €3) are stale.

| Plan | Spec | List price (ex VAT) | Notes |
|---|---|---|---|
| CX23 | 2 vCPU x86, 4 GB, 40 GB | ~€3.99 to 5.99 | Replaced CX22. Sources disagree on the exact figure. |
| CAX11 | 2 vCPU **ARM**, 4 GB, 40 GB | ~€4.49 to 5.99 | Same architecture as the current Graviton image. |
| CAX21 | 4 vCPU ARM, 8 GB, 80 GB | roughly double CAX11 | Headroom option. |
| IPv4 address | | +€0.50 | Per server; IPv6-only is free but not viable here (see below). |
| Backups (optional) | | +20% of server price | Provider-side convenience only. |
| Snapshots | | ~€0.01/GB/month | Before migrations and upgrades. |

**Estimated total: about €5 to 7 per month plus UK VAT (20%) if billed as a
UK consumer, so roughly £5 to 8.** Traffic: 20 TB included in EU regions.
Comfortably the cheapest provider here.

**Availability warning:** a late-August 2026 check reported the CX and CAX
"Cost-Optimized" plans as *currently not available* on Hetzner's own page,
with a limited-availability notice open since 2026-06-26. Check that the
plan can be ordered in your chosen location before planning around it. The
fallback is the next dedicated-vCPU (CCX) or regular-performance (CPX) tier,
which costs more (CPX21-class is typically in the €8 to 12 range) but would
still beat every other provider here.

## Location and architecture

- EU: Falkenstein (`fsn1`), Nuremberg (`nbg1`), Helsinki (`hel1`). US:
  Ashburn, Hillsboro. **No UK region.** Expect about 20 to 35 ms from the UK
  to Germany or Finland, which is irrelevant for this app. If you need UK
  data residency, use OVH or Vultr instead.
- Choose **CAX11 (ARM)** to reuse the `linux/arm64` image build that
  `deploy-backend.sh` already does. Choose **CX23 (x86)** if CAX stock is
  unavailable; then build `linux/amd64`. The Dockerfile's base images
  (`eclipse-temurin:25`, `postgres:16`, `caddy:2`) are all multi-arch.
- GDPR: EU-hosted, fine for an EU/UK audience; document it in your privacy
  notice. Hetzner is a German company with DPA available in the console.

## Setup

Console or the `hcloud` CLI (`brew install hcloud`; `hcloud context create cadence`).

```sh
hcloud ssh-key create --name neil --public-key-from-file ~/.ssh/id_ed25519.pub

hcloud firewall create --name cadence
hcloud firewall add-rule cadence --direction in --protocol tcp --port 80  --source-ips 0.0.0.0/0 --source-ips ::/0
hcloud firewall add-rule cadence --direction in --protocol tcp --port 443 --source-ips 0.0.0.0/0 --source-ips ::/0
hcloud firewall add-rule cadence --direction in --protocol tcp --port 22  --source-ips <YOUR_IP>/32

hcloud server create --name cadence --type cax11 --image ubuntu-24.04 \
    --location fsn1 --ssh-key neil --firewall cadence
```

Then, over SSH: create the deploy user, install Docker from Docker's apt
repo (`docker-ce`, `docker-compose-plugin`), add swap, enable
`unattended-upgrades`, and create `/opt/cadence/*` per `COMMON.md`.

Hetzner specifics:
- **Primary IP**: the server's IPv4 is a separate resource. Create it
  explicitly (`hcloud primary-ip create`) and keep "auto delete" off so the
  address survives if the server is rebuilt. That makes DNS stable across a
  rebuild.
- **Reverse DNS**: set rDNS on the IP to `api.cadence.bioinform.co.uk`
  (`hcloud server set-rdns`). Not needed for SES (it sends from AWS) but
  tidy.
- **IPv6**: the server gets a free /64. Publish AAAA records only if the
  firewall rules above include `::/0` (they do) and Caddy is reachable; test
  with `curl -6`.
- **Outbound port 25 is blocked** on new accounts. Irrelevant: SES uses 443.
- **Snapshots/backups** are in the console; use snapshots as a pre-change
  safety net, not as the backup plan (`COMMON.md`).
- DNS: Hetzner has free DNS, but staying on Route 53 means nothing else
  moves. Either works.

## Terraform

The `hetznercloud/hcloud` provider covers `hcloud_server`,
`hcloud_firewall`, `hcloud_primary_ip` and `hcloud_ssh_key`. A module of
about 40 lines replaces `modules/ec2` and `modules/vpc`. Not written.

## Provider-specific pros and cons

Pros
- By far the lowest price: roughly a quarter to a third of the others.
- ARM (CAX) matches the existing image; one fewer build-pipeline change.
- Generous transfer (20 TB), fast NVMe storage, good CLI and Terraform provider.
- Primary IPs, firewalls and snapshots are first-class.

Cons
- **No UK region**; EU only (or US).
- Recent price rises and reported limited availability of the cheap plans.
- Fraud-screening on new accounts can delay or reject signups, sometimes
  asking for ID; plan for it.
- Smaller managed-service ecosystem (irrelevant here since we self-manage).
- No provider-side object storage as cheap as Backblaze, so offsite backups
  go elsewhere (an S3 bucket is fine).
