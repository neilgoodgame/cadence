output "dns_name" {
  description = "Raw ALB hostname - api.cadence.bioinform.co.uk (a Route 53 alias to this) is the real address once DNS is wired up."
  value       = aws_lb.this.dns_name
}

output "zone_id" {
  description = "For a future Route 53 ALIAS record pointing cadence.bioinform.co.uk at this ALB."
  value       = aws_lb.this.zone_id
}

output "security_group_id" {
  description = "Referenced by the ECS module's security group to add the one inbound rule ECS needs (ALB -> ECS on 8080)."
  value       = aws_security_group.alb.id
}

output "target_group_arn" {
  value = aws_lb_target_group.backend.arn
}

output "listener_arn" {
  description = "The HTTPS listener - the one that actually forwards to the target group now (HTTP just redirects), so this is what the ECS module's service should wait on."
  value       = aws_lb_listener.https.arn
}
