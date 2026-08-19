########################################################################
# Static frontend: private S3 bucket + CloudFront (Origin Access Control,
# not a public bucket or S3 website endpoint - CloudFront is the only thing
# allowed to read the bucket, enforced by the bucket policy below). SPA
# client-side routing handled via CloudFront custom error responses
# (403/404 -> /index.html, 200) rather than any S3-side redirect config.
########################################################################

data "aws_caller_identity" "current" {}

# Bucket names are globally unique across every AWS account, not just this
# one - the account ID suffix avoids a name collision with someone else's
# bucket that "cadence-staging-frontend" alone could hit.
resource "aws_s3_bucket" "this" {
  bucket = "cadence-${var.environment}-frontend-${data.aws_caller_identity.current.account_id}"

  tags = merge(var.tags, { Name = "cadence-${var.environment}-frontend" })
}

resource "aws_s3_bucket_public_access_block" "this" {
  bucket = aws_s3_bucket.this.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_cloudfront_origin_access_control" "this" {
  name                              = "cadence-${var.environment}-frontend"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

resource "aws_cloudfront_distribution" "this" {
  enabled             = true
  default_root_object = "index.html"
  aliases             = [var.domain_name]
  price_class         = "PriceClass_100" # staging: North America + Europe edge locations only, not the full global set

  origin {
    domain_name              = aws_s3_bucket.this.bucket_regional_domain_name
    origin_id                = "s3-frontend"
    origin_access_control_id = aws_cloudfront_origin_access_control.this.id
  }

  default_cache_behavior {
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    target_origin_id       = "s3-frontend"
    viewer_protocol_policy = "redirect-to-https"
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id
  }

  # A SPA route like /athletes/123 has no matching S3 object, so S3 (via
  # CloudFront) returns 403 (private bucket - no ListBucket rights to even
  # confirm absence) or 404. Both get rewritten to index.html so
  # react-router's client-side routing can take over, matching how any
  # other SPA static host (Vercel/Netlify/S3 website hosting) handles this.
  custom_error_response {
    error_code         = 403
    response_code      = 200
    response_page_path = "/index.html"
  }

  custom_error_response {
    error_code         = 404
    response_code      = 200
    response_page_path = "/index.html"
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

  tags = merge(var.tags, { Name = "cadence-${var.environment}-frontend" })
}

# Only CloudFront (this specific distribution, via SourceArn) can read the
# bucket - no public access, no other principal.
data "aws_iam_policy_document" "bucket" {
  statement {
    effect    = "Allow"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.this.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.this.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "this" {
  bucket = aws_s3_bucket.this.id
  policy = data.aws_iam_policy_document.bucket.json
}
