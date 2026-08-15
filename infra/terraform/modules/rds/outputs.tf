output "endpoint" {
  description = "host:port - what the app's DB connection string points at."
  value       = aws_db_instance.this.endpoint
}

output "address" {
  description = "Hostname only, no port."
  value       = aws_db_instance.this.address
}

output "db_name" {
  value = aws_db_instance.this.db_name
}

output "master_username" {
  value = aws_db_instance.this.username
}

output "master_user_secret_arn" {
  description = "Secrets Manager ARN holding the auto-managed master password - the ECS task definition references this directly as a task secret, the actual password value never needs to pass through Terraform state or this app's own config."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "security_group_id" {
  description = "Referenced by the ECS module's security group to add the one inbound rule RDS needs (ECS -> RDS on 5432)."
  value       = aws_security_group.rds.id
}
