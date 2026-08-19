output "arn" {
  description = "The domain identity's ARN - IAM policies granting ses:SendEmail should scope to exactly this, not \"*\"."
  value       = aws_ses_domain_identity.this.arn
}

output "domain" {
  value = aws_ses_domain_identity.this.domain
}
