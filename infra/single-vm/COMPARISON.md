# Single-VM providers compared

Companion to `COMMON.md` (design and migration steps) and the per-provider
docs. Scope: providers whose 4 GB plan costs **$20/month or under**:
Hetzner, OVHcloud, Scaleway and Vultr. Excluded by price: DigitalOcean
($24), Akamai/Linode ($24), AWS Lightsail ($24 for 4 GB). Excluded by
request: Oracle Cloud's free tier.

**All four providers' figures were read from their own pricing pages (and
OVH's configurator) on 2026-10-26.** Taxes are generally shown ex VAT.
A few smaller items (Scaleway's IPv4 rate, Vultr's London availability) are
noted as unconfirmed where they appear. Findings that matter more than the
numbers: all of Hetzner's Cost-Optimized plans are currently not available
(leaving CPX22 at €19.99), OVH's VPS-1 is about £4 to 5 a month, and
Scaleway and Vultr both land at or just over $20 once IPv4 or VAT is added.

## At a glance

| | Hetzner | OVHcloud | Scaleway | Vultr |
|---|---|---|---|---|
| Plan | CPX22 (CAX11/CX23 not available) | VPS-1 (2027 range) | DEV1-M | Regular 4 GB |
| vCPU / RAM | 2 / 4 GB | 2 / 4 GB | 3 / 4 GB | 2 / 4 GB |
| Architecture | x86 (ARM if CAX returns) | x86 | x86 | x86 |
| ~Monthly cost | €19.99 net (~€24 to 25 with IPv4 + UK VAT); €6.49 if CAX11 returns | **£3.31 ex VAT (12-mo) to £3.90 ex VAT (monthly)** | €14.74 + ~€2.92 IPv4 (€17.7 ex VAT) | $20.00 (ex tax) |
| ~Monthly in £ (incl. VAT where billed) | £17 to 21 (£6 to 8 if CAX returns) | **£4.0 to 4.7** (VPS-2, 8 GB: £7.6 to ~9) | £15 to 18 | £15 to 19 |
| **UK region** | No | **Yes (Erith, Greater London)** | No | **Yes (London)** |
| Nearest region to UK | DE / FI | UK (Erith) | Paris / Amsterdam | London |
| Cloud firewall | Yes | Host-level only | Yes (stateful) | Yes (stateful) |
| CLI | `hcloud` | None for ordering | `scw` | `vultr-cli` |
| Terraform | Yes | No (VPS ordering) | Yes | Yes |
| Transfer | 20 TB | Unmetered (500 Mbps) | Generous (verify) | 3 TB |
| Image build change from today | arm64 to amd64 (none if CAX) | arm64 to amd64 | arm64 to amd64 | arm64 to amd64 |
| Biggest risk | Cheap tier out of stock; at the ceiling | Spec and price uncertainty | Price | Plan may not exist |

## Cost over time (approximate, 4 GB single VM, before backups storage)

| | Monthly | 12 months |
|---|---|---|
| Hetzner CPX22 | €19.99 net (~€24 to 25 with IPv4 + VAT) | €240 to 300 |
| Hetzner CAX11 (if back in stock) | ~€6.49 to 8 | €78 to 96 |
| OVHcloud VPS-1 | £3.90 ex VAT (£3.31 on a 12-month term) | £40 to 47 ex VAT (£47 to 56 inc) |
| Scaleway DEV1-M + IPv4 | ~€17.7 ex VAT (~€21 inc) | ~€212 ex VAT (~€255 inc) |
| Vultr Regular 4 GB | $20 ex tax (~$24 with VAT) | $240 (~$288) |
| For reference: today's AWS staging, always-on | not measured; the repo's own estimate for stop/start usage is $10 to 12 | n/a |

Offsite backups add about £0 to 1 per month on every option (S3 or B2).
For reference, the single-Droplet DigitalOcean estimate discussed earlier
was $24 to 29.

## Pros and cons

### Hetzner
- Pros: 80 GB NVMe and 20 TB transfer on CPX22; excellent CLI and Terraform
  provider; first-class primary IPs and snapshots. The Cost-Optimized tier,
  if it returns, would be the cheapest by a wide margin and keeps the ARM
  image build.
- Cons: no UK region; **every Cost-Optimized plan is currently not
  available**, so today's option is CPX22 at €19.99 net (about 3x the
  planned price, at the ceiling and over it with IPv4 and VAT); April 2026
  price rise of about 30%; signup fraud-screening can delay or refuse new
  accounts.

### OVHcloud
- Pros: **by far the cheapest 4 GB plan (about £4 to 5/month inc VAT, read
  from the live configurator)**; UK datacentre (Erith); unmetered traffic;
  daily provider backup of the last 24 hours included; anti-DDoS included;
  no extra charge seen for IPv4 or the OS image; VPS-2 (8 GB) is only about
  £7.5 to 9.
- Cons: only 40 GB disk on VPS-1 (check media and DB size first); no
  CLI or Terraform ordering (hand-built server); roughest console and
  support; account verification delays; Docker + `ufw` footgun; x86 only;
  500 Mbps port.

### Scaleway
- Pros: best developer experience among the budget providers; real
  stateful firewall; EU-owned; S3-compatible storage available for backups.
- Cons: about 4x OVH and slightly over a $20 ceiling once the IPv4 address
  (about €2.92) and VAT are added; no UK region; older CPUs; local SSD means
  deleting the instance deletes the data; storage and IPv4 are separate
  line items; DEV1-M rose in price on 2026-06-01.

### Vultr
- Pros: London region; friction-free signup; good API and Terraform;
  80 GB disk and simple hourly billing.
- Cons: London availability of the $20 plan is unconfirmed (the plan is
  listed; next tier is $24); about five times OVH; older Intel and regular
  SSD hardware at this tier; x86 only; VAT would bring it to about $24.

## Recommendation

1. **OVH VPS-1 in the UK (Erith)**: 2 vCores, 4 GB, 40 GB NVMe for about
   £4 to 5 a month including VAT, a UK datacentre, and prices read from the
   live configurator. About a fifth of Hetzner CPX22's cost. Take VPS-2
   (8 GB, 75 GB, about £7.5 to 9) if the data or media exceed a few GB or
   you want RAM headroom.
2. **Hetzner CPX22 (Falkenstein or Helsinki)** if you want Hetzner's
   tooling, NVMe and 80 GB disk, and EU rather than UK hosting is fine. At
   €19.99 net it is at the ceiling and over it once IPv4 and VAT are added.
   If the Cost-Optimized CAX11 (€6.49) returns to stock, it jumps to first
   place on price and needs no image build change.
3. **Scaleway DEV1-M** if developer experience, a real firewall and
   Terraform-managed infrastructure matter more than saving about £10 to 14
   per month over OVH.
4. **Vultr Regular 4 GB ($20)** only if it is orderable in London; it buys
   nothing over OVH except an easier signup, at about five times the price.

The deciding questions, in order:
1. Do you need UK-hosted data? If yes: OVH or Vultr. If no: OVH or Hetzner.
2. Is Hetzner's Cost-Optimized tier (CAX11/CX23) orderable? If yes, it is
   the cheapest. If not, Hetzner is only a tooling preference.
3. Do you want Terraform-managed infrastructure? Rules out OVH.

## Variant: frontend stays on AWS

Any provider above can be combined with keeping the SPA on S3 + CloudFront
(see "Variant: keep the frontend on AWS" in `COMMON.md`). Add about $1 to 2 a
month: Hetzner CPX22 roughly €21 to 26 (CAX11, if back: €8 to 10), OVH about £5 to 6 (VPS-1), Scaleway about €19 to 23, Vultr
about $21 to 22 before VAT (over the $20 ceiling). It keeps `deploy-frontend.sh`
and the frontend Terraform modules, gives a global CDN and keeps the site up
during a VM outage, and makes rollback trivial. It costs you a permanent
AWS footprint. It is the better first step if you want to migrate in two
stages; the all-on-VM design is the better end state if you want a single
provider and the lowest price.

## What these all share (and what none solve)

- One host is one failure domain: a provider hardware fault or a reboot
  takes the frontend, API and DB down together. Provider SLAs on single VMs
  are weak and none of these gives a failover. The backup and restore plan
  in `COMMON.md` is the only recovery path; test a restore before cutover.
- A noisy recompute can starve Postgres on the same VM; the memory limits in
  `COMMON.md` bound the damage.
- SES stays on AWS in every option, so AWS keeps a small footprint (SES,
  possibly Route 53 and a backup bucket).
- There is no managed Postgres, point-in-time recovery or read replica.
  The nightly `pg_dump` has a recovery point objective of up to 24 hours;
  tighten that with WAL archiving if the data gets precious.

## Operational differences worth knowing before you pick

| Concern | Hetzner | OVH | Scaleway | Vultr |
|---|---|---|---|---|
| Recover from lock-out | Web console + rescue | Rescue mode netboot | Console serial | Web console |
| Pre-change safety net | Snapshot | Snapshot | Snapshot | Snapshot |
| Static IP survives rebuild | Primary IP resource | Fixed to VPS | Flexible IP (billed) | Reserved IP ($3) |
| Data lost if instance deleted | Disk deleted too | Yes | Yes (local SSD) | Yes |
| Rebuild effort | Terraform module | Manual | Terraform module | Terraform module |

## Next steps if one is chosen

1. Confirm price, specs, region and availability on the provider's own page.
2. Write the Terraform module (all but OVH) and a `deploy-backend-vm.sh`.
3. Do the dry-run migration in `COMMON.md` step 3 on a short-lived server
   (hourly billing makes this cost pennies on Hetzner, Scaleway and Vultr).
4. Test a full restore from a backup, then cut over.
