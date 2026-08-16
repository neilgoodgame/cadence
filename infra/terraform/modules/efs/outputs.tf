output "file_system_id" {
  value = aws_efs_file_system.media.id
}

output "access_point_id" {
  value = aws_efs_access_point.media.id
}

output "security_group_id" {
  description = "Referenced by the ECS module's security group to add the one inbound rule EFS needs (ECS -> EFS on 2049)."
  value       = aws_security_group.efs.id
}
