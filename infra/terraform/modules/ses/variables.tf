variable "domain_name" {
  description = "The domain to verify as an SES sending identity, e.g. cadence.bioinform.co.uk. A subdomain of the zone, not the apex - so the DKIM CNAMEs added here never touch the apex's own (Google Workspace) MX/mail records. SES sending and inbound Workspace mail are fully independent - domain verification is DKIM (CNAME) only, no MX involved."
  type        = string
}

variable "zone_id" {
  type = string
}
