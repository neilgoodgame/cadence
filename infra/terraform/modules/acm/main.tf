########################################################################
# ACM cert for the ALB (api.cadence.bioinform.co.uk), DNS-validated via
# Route 53. NOT reusable for the frontend's CloudFront distribution later -
# CloudFront requires its certificate to exist in us-east-1 regardless of
# which region the distribution/other resources are in, so that's a
# separate cert requested by the frontend step, not a SAN added here.
########################################################################

resource "aws_acm_certificate" "this" {
  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = var.tags
}

# allow_overwrite so a re-apply (or a previous partial run) doesn't collide
# with a record that already exists from validating this same domain before.
resource "aws_route53_record" "validation" {
  for_each = {
    for dvo in aws_acm_certificate.this.domain_validation_options : dvo.domain_name => {
      name  = dvo.resource_record_name
      type  = dvo.resource_record_type
      value = dvo.resource_record_value
    }
  }

  zone_id         = var.zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.value]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "this" {
  certificate_arn         = aws_acm_certificate.this.arn
  validation_record_fqdns = [for r in aws_route53_record.validation : r.fqdn]
}
