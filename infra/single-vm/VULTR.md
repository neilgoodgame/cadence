# Single VM on Vultr

Provider-specific steps only. Architecture, compose, Caddyfile, backups and
the AWS-to-VM data migration are in `COMMON.md`. Ranked against the others
in `COMPARISON.md`.

## Cost (read from vultr.com/pricing on 2026-10-26)

| Family | Plan | Spec | Price |
|---|---|---|---|
| Cloud Compute, Regular Performance | 2 vCPU, 4 GB | 80 GB SSD, 3 TB transfer | **$20.00/month** ($0.03/hour) |
| Regular Performance | 4 vCPU, 8 GB | 160 GB SSD, 4 TB | $40.00 |
| High Performance (AMD) | 2 vCPU, 4 GB | 100 GB NVMe, 5 TB | $24.00 ($0.036/hour) |
| VX1 | 2 vCPU, 8 GB | block storage boot, 5 TB | $0.060/hour (about $44) |

Regular Performance is "powered by previous generation Intel CPUs and
regular SSD". The $20 4 GB plan **is still listed** on the pricing page,
which settles the earlier doubt (a third-party price-change tracker had
reported it removed). The page does not say which regions offer it, so
**confirm that Regular Performance 4 GB is orderable in London** on the
deploy page, which needs an account. The smallest plans also come in
IPv6-only variants ($3.50 for the 0.5 GB size), which implies the other
plans include an IPv4 address; confirm at checkout.

**Estimated total: $20 per month (about £15 to 16)** as listed. Vultr
prices appear to be shown before tax; UK VAT at checkout would bring it to
about $24 (about £18 to 19). Automatic backups are an optional +20%.

## Location and architecture

- Regions include **London (`lhr`)**, Amsterdam, Frankfurt, Paris, many
  others. Regular Performance is not available in every region; confirm
  London offers the plan.
- x86: build `linux/amd64` images.
- US company with EU/UK regions; DPA available.

## Setup

Console, or `vultr-cli` (`brew install vultr/vultr-cli/vultr-cli`,
`VULTR_API_KEY` set; note Vultr API keys are IP-restricted by default, so
allow your IP in the API settings).

```sh
vultr-cli ssh-key create --name neil --key "$(cat ~/.ssh/id_ed25519.pub)"
vultr-cli firewall group create --description cadence
# add rules: tcp 22 (your IP), 80, 443 (v4 and v6) to the group
vultr-cli os list | grep -i 'ubuntu 24'     # find the OS id
vultr-cli plans list                        # confirm the 4 GB regular plan id
vultr-cli instance create --region lhr --plan <4gb-regular-plan-id> \
    --os <ubuntu-24.04-id> --label cadence --ssh-keys <key-id> \
    --firewall-group-id <group-id>
```

Plan and OS ids are looked up rather than hard-coded here because they
change; verify with the two list commands above.

Then over SSH: deploy user, Docker from Docker's apt repo, swap,
`unattended-upgrades`, `/opt/cadence/*` per `COMMON.md`.

Vultr specifics:
- **Firewall groups** are provider-side and stateful but apply to IPv4 and
  IPv6 separately; add both rule sets.
- **Reserved IP** (about $3/month) is optional; the default instance IP
  persists across reboots but not a destroy/recreate.
- **Reverse DNS** in the console.
- **Snapshots** are free up to a small allowance, then billed per GB; good
  for pre-change safety.
- **Outbound SMTP** is blocked by default on new accounts; irrelevant here.
- **Vultr DNS** is free if you want to move off Route 53 (not required).

## Terraform

The official `vultr/vultr` provider supports `vultr_instance`,
`vultr_firewall_group`, `vultr_firewall_rule` and `vultr_reserved_ip`.
A module of about 50 lines replaces `modules/ec2`. Not written.

## Provider-specific pros and cons

Pros
- **London region**; also many other regions, so latency to any user base
  is covered.
- Straightforward UX, good API/CLI/Terraform, no signup friction compared
  with OVH or Hetzner.
- Includes 80 GB SSD and 3 TB transfer on the 4 GB plan.
- Hourly billing, easy snapshot-and-clone for testing the migration.

Cons
- **London availability of the $20 plan is unconfirmed**; the next tier
  (High Performance AMD, 2 vCPU, 4 GB, 100 GB NVMe) is $24.
- About 3x Hetzner's price, 2x OVH's, for similar hardware.
- Regular Performance is older Intel hardware on regular SSDs, slower than
  Hetzner's NVMe offering.
- x86 only; image build must switch from arm64.
