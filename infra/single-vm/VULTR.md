# Single VM on Vultr

Provider-specific steps only. Architecture, compose, Caddyfile, backups and
the AWS-to-VM data migration are in `COMMON.md`. Ranked against the others
in `COMPARISON.md`.

## Cost (checked 2026-10-21 via web search; verify on vultr.com/pricing)

| Plan | Spec | List price | Notes |
|---|---|---|---|
| **Cloud Compute, Regular Performance 4 GB** | 2 vCPU, 4 GB, 80 GB SSD, 3 TB transfer | **$20/month** | **A price-change tracker reports this plan as removed from the catalogue; Vultr's product page still describes Regular Performance as "from $2.50".** Confirm it can be ordered before relying on this document. |
| High Frequency 4 GB | 2 vCPU, 4 GB, NVMe | ~$24 | Above the $20 ceiling; included only as the fallback. |
| Optimized Cloud Compute 4 GB | | ~$28 | Above the ceiling. |
| Automatic backups | | +20% of plan | Optional. |
| IPv4 | | included | |

**Estimated total: $20/month (about £15 to 16), if the Regular 4 GB plan is
still orderable.** If not, Vultr falls outside the $20 envelope and this
option should be dropped from the shortlist: the next 4 GB tier is $24+.
Other plans of 2 GB or less cannot run this workload (see `COMMON.md`
sizing).

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
- **The $20 plan may no longer exist**; on the evidence found it is the
  most uncertain entry on the shortlist, and the next tier is $24+.
- About 3x Hetzner's price, 2x OVH's, for similar hardware.
- Regular Performance is older Intel hardware on regular SSDs, slower than
  Hetzner's NVMe offering.
- x86 only; image build must switch from arm64.
