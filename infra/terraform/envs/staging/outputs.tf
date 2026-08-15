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
