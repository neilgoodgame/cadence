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

output "backend_instance_id" {
  value = module.ec2.instance_id
}

output "backend_public_ip" {
  description = "The Elastic IP - stable across stop/start. https://api.cadence.bioinform.co.uk is the real API URL (via CloudFront)."
  value       = module.ec2.public_ip
}

output "backend_log_group_name" {
  value = module.ec2.log_group_name
}

output "api_cdn_domain_name" {
  value = module.api_cdn.cloudfront_domain_name
}

output "frontend_bucket_name" {
  description = "aws s3 sync dist/ s3://<this> --delete, after building the frontend with VITE_API_BASE_URL=https://api.cadence.bioinform.co.uk."
  value       = module.frontend.bucket_name
}

output "frontend_distribution_id" {
  description = "For aws cloudfront create-invalidation --paths '/*' after each deploy."
  value       = module.frontend.distribution_id
}
