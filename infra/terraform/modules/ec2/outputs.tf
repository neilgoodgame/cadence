output "instance_id" {
  value = aws_instance.this.id
}

output "security_group_id" {
  description = "Referenced at the root module level for the one inbound rule RDS needs (this SG -> RDS on 5432)."
  value       = aws_security_group.this.id
}

output "public_ip" {
  description = "The Elastic IP - stable across stop/start. What the api_cdn module's origin DNS record points at."
  value       = aws_eip.this.public_ip
}

output "log_group_name" {
  value = aws_cloudwatch_log_group.backend.name
}
