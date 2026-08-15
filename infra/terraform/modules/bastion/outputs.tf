output "instance_id" {
  value = aws_instance.bastion.id
}

output "security_group_id" {
  description = "Referenced by the RDS module's security group to add the one inbound rule it needs from this bastion (5432)."
  value       = aws_security_group.bastion.id
}
