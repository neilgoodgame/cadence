output "vpc_id" {
  value = module.vpc.vpc_id
}

output "public_subnet_ids" {
  value = module.vpc.public_subnet_ids
}

output "private_subnet_ids" {
  value = module.vpc.private_subnet_ids
}

output "db_endpoint" {
  value = module.rds.endpoint
}

output "db_master_user_secret_arn" {
  value = module.rds.master_user_secret_arn
}

output "db_security_group_id" {
  value = module.rds.security_group_id
}

output "ecr_repository_url" {
  value = module.ecr.repository_url
}

output "alb_dns_name" {
  description = "Raw ALB hostname - https://api.cadence.bioinform.co.uk is the real API URL."
  value       = module.alb.dns_name
}

output "ecs_cluster_name" {
  value = module.ecs.cluster_name
}

output "ecs_service_name" {
  value = module.ecs.service_name
}

output "ecs_log_group_name" {
  value = module.ecs.log_group_name
}

output "frontend_bucket_name" {
  description = "aws s3 sync dist/ s3://<this> --delete, after building the frontend with VITE_API_BASE_URL=https://api.cadence.bioinform.co.uk."
  value       = module.frontend.bucket_name
}

output "frontend_distribution_id" {
  description = "For aws cloudfront create-invalidation --paths '/*' after each deploy."
  value       = module.frontend.distribution_id
}
