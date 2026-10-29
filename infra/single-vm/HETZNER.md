# Single VM on Hetzner Cloud

Provider-specific steps only. Architecture, compose, Caddyfile, backups and
the AWS-to-VM data migration are in `COMMON.md`. Ranked against the others
in `COMPARISON.md`.

## Cost (read from hetzner.com/cloud plan pages on 2026-10-26)

Hetzner raised prices about 30% on 2026-04-01. The site's VAT selector was
set to 0%, so the prices below are net; the "from" price probably excludes
the IPv4 address, so confirm the total in the console's order summary.

| Line | Plan | Spec | List price | Status |
|---|---|---|---|---|
| Cost-Optimized | CX23 | 2 vCPU Intel/AMD, 4 GB, 40 GB | €5.99 | **not available** |
| Cost-Optimized | CAX11 (ARM) | 2 vCPU, 4 GB, 40 GB | €6.49 | **not available** |
| Cost-Optimized | CX33 | 4 vCPU, 8 GB, 80 GB | €8.99 | **not available** |
| Cost-Optimized | CAX21 (ARM) | 4 vCPU, 8 GB, 80 GB | €10.99 | **not available** |
| Regular Performance | CPX12 | 1 vCPU AMD, 2 GB, 40 GB | €11.99 | orderable; too small |
| **Regular Performance** | **CPX22** | **2 vCPU AMD, 4 GB, 80 GB NVMe** | **€19.99** | **orderable; the option to use** |
| Regular Performance | CPX32 | 4 vCPU AMD, 8 GB, 160 GB | €35.99 | orderable; over budget |
| General Purpose | CCX13 | 2 dedicated vCPU, 8 GB | €43.49 | over budget |
| IPv4 address | | | ~€0.50 | Per server; IPv6-only is not viable here |
| Backups (optional) | | | +20% of server price | Provider-side convenience only |
| Snapshots | | | ~€0.01/GB/month | Before migrations and upgrades |

**Today's choice is CPX22 at €19.99 per month**, net of VAT and probably
before the IPv4 charge. With IPv4 and 20% UK VAT (if billed as a UK
consumer) expect roughly €24 to 25, which is **over a $20 ceiling**.
Traffic: 20 TB included in EU regions.

**Availability:** every Cost-Optimized plan, including all the ARM ones, was
marked "not available" on Hetzner's own page. Earlier web searches reported
a limited-availability notice open since 2026-06-26, so this is not a
short blip. That tier would have been the cheapest provider here (CAX11 at
€6.49, about a third of CPX22). Re-check the page before ordering; if stock
returns, CAX11 (ARM, reuses the arm64 image build) is the better choice and
the rest of this document applies unchanged.

## Location and architecture

- EU: Falkenstein (`fsn1`), Nuremberg (`nbg1`), Helsinki (`hel1`). US:
  Ashburn, Hillsboro. **No UK region.** Expect about 20 to 35 ms from the UK
  to Germany or Finland, which is irrelevant for this app. If you need UK
  data residency, use OVH or Vultr instead.
- **CPX22 is AMD x86**, so build `linux/amd64` images (`COMMON.md`
  deploy snippet). If CAX11 (ARM) comes back in stock, choose it to reuse the
  `linux/arm64` build that `deploy-backend.sh` already does. The Dockerfile's base images
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

hcloud server create --name cadence --type cpx22 --image ubuntu-24.04 \
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
- The cheap tier (CAX11 at €6.49) would be roughly a third of the others'
  price if it returns to stock. CPX22 is merely in line with them.
- If CAX returns, ARM matches the existing image; one fewer build change.
- Generous transfer (20 TB), fast NVMe storage, good CLI and Terraform provider.
- Primary IPs, firewalls and snapshots are first-class.

Cons
- **No UK region**; EU only (or US).
- **All Cost-Optimized plans are currently not available**, leaving CPX22 at
  €19.99 (net), about 3x the planned price and at the budget ceiling.
- A price rise of about 30% in April 2026; x86 only while CAX is out.
- Fraud-screening on new accounts can delay or reject signups, sometimes
  asking for ID; plan for it.
- Smaller managed-service ecosystem (irrelevant here since we self-manage).
- No provider-side object storage as cheap as Backblaze, so offsite backups
  go elsewhere (an S3 bucket is fine).
