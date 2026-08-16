output "bucket_name" {
  description = "What the frontend build gets synced to (aws s3 sync dist/ s3://<this>)."
  value       = aws_s3_bucket.this.id
}

output "distribution_id" {
  description = "Needed for cache invalidation after each deploy (aws cloudfront create-invalidation)."
  value       = aws_cloudfront_distribution.this.id
}

output "cloudfront_domain_name" {
  value = aws_cloudfront_distribution.this.domain_name
}

output "cloudfront_hosted_zone_id" {
  description = "Fixed per CloudFront distribution (not per-account) - for a Route 53 alias record."
  value       = aws_cloudfront_distribution.this.hosted_zone_id
}
