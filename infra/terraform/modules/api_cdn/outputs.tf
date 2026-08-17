output "cloudfront_domain_name" {
  value = aws_cloudfront_distribution.this.domain_name
}

output "cloudfront_hosted_zone_id" {
  description = "Fixed per CloudFront distribution (not per-account) - for a Route 53 alias record."
  value       = aws_cloudfront_distribution.this.hosted_zone_id
}
