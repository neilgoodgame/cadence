output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "service_name" {
  value = aws_ecs_service.backend.name
}

output "security_group_id" {
  description = "Referenced at the root module level to add the two inbound rules ECS needs (ALB -> ECS on 8080 is added there; this SG's own egress already covers ECS -> RDS/EFS)."
  value       = aws_security_group.ecs.id
}

output "log_group_name" {
  value = aws_cloudwatch_log_group.backend.name
}
