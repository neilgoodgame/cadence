output "certificate_arn" {
  description = "The validated certificate - safe to reference in an aws_lb_listener, since this resource doesn't return until validation actually completes."
  value       = aws_acm_certificate_validation.this.certificate_arn
}
