########################################################################
# CloudFront in front of the backend EC2 instance instead of an ALB -
# see infra/EC2_BACKEND_SKETCH.md. Custom HTTP origin (not S3+OAC like the
# frontend module), CachingDisabled (API responses aren't static assets),
# Managed-AllViewer origin request policy (forwards every header/cookie/
# query string through untouched - this is a thin proxy, not a real cache).
########################################################################

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewer"
}

resource "aws_cloudfront_distribution" "this" {
  enabled     = true
  aliases     = [var.domain_name]
  price_class = "PriceClass_100"

  origin {
    domain_name = var.origin_domain_name
    origin_id   = "backend"

    custom_origin_config {
      http_port              = var.origin_port
      https_port             = 443
      origin_protocol_policy = "http-only" # CloudFront terminates client TLS; the origin's own SG only accepts traffic from CloudFront's IPs - see modules/ec2's security group.
      origin_ssl_protocols   = ["TLSv1.2"]
    }
  }

  default_cache_behavior {
    allowed_methods          = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
    cached_methods           = ["GET", "HEAD"]
    target_origin_id         = "backend"
    viewer_protocol_policy   = "redirect-to-https"
    cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
    origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = var.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  tags = merge(var.tags, { Name = "cadence-${var.environment}-api" })
}
