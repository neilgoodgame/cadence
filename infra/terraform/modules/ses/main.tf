########################################################################
# SES domain identity for transactional email - currently just the
# email-verification link sent on password signup (see
# EmailVerificationService/SesEmailService in backend_java). Verified via
# Easy DKIM alone: once all 3 CNAME records below resolve, SES marks the
# identity Verified - no separate TXT ownership-verification record needed.
#
# The account starts in the SES sandbox (200 sends/day, verified
# recipients only) regardless of this module - production access is a
# separate manual/CLI step (`aws sesv2 put-account-details`), not something
# Terraform can request on your behalf.
########################################################################

resource "aws_ses_domain_identity" "this" {
  domain = var.domain_name
}

resource "aws_ses_domain_dkim" "this" {
  domain = aws_ses_domain_identity.this.domain
}

resource "aws_route53_record" "dkim" {
  count = 3

  zone_id = var.zone_id
  name    = "${aws_ses_domain_dkim.this.dkim_tokens[count.index]}._domainkey.${var.domain_name}"
  type    = "CNAME"
  ttl     = 600
  records = ["${aws_ses_domain_dkim.this.dkim_tokens[count.index]}.dkim.amazonses.com"]
}
