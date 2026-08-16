variable "environment" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "ecr_repository_url" {
  type = string
}

variable "image_tag" {
  description = "Git-SHA tag (e.g. sha-b2831b1) or \"latest\" - see infra/README.md's ECR section for the tagging convention."
  type        = string
  default     = "latest"
}

variable "cpu" {
  description = "Fargate task-level vCPU units (256 = .25 vCPU). Small default - this is staging, bump per AWS_MIGRATION_PLAN.md's cost table if real load shows up."
  type        = string
  default     = "512"
}

variable "memory" {
  type    = string
  default = "1024"
}

variable "desired_count" {
  type    = number
  default = 1
}

variable "log_retention_days" {
  type    = number
  default = 30
}

variable "target_group_arn" {
  type = string
}

# Not used directly in any resource argument - only in depends_on, so the ECS
# service definitely waits for the ALB listener to exist before Terraform
# tries to register targets against it, not just for the target group itself.
variable "listener_arn" {
  type = string
}

variable "alb_security_group_id" {
  description = "Referenced to add the one inbound rule this module's ECS tasks SG needs (ALB -> ECS on 8080)."
  type        = string
}

variable "db_address" {
  type = string
}

variable "db_port" {
  type    = number
  default = 5432
}

variable "db_name" {
  type = string
}

variable "db_master_username" {
  type = string
}

variable "db_master_user_secret_arn" {
  description = "RDS-managed Secrets Manager secret - JSON with a \"password\" key, referenced as a task secret via :password:: below."
  type        = string
}

variable "jwt_keys_secret_arn" {
  description = "cadence-staging-jwt-keys - JSON with jwt_private_key_pem/jwt_public_key_pem keys."
  type        = string
}

variable "jwt_kid" {
  type    = string
  default = "801"
}

variable "jwt_issuer" {
  type = string
}

variable "jwt_audience" {
  type    = string
  default = "cadence-api"
}

variable "oauth_client_secret_arn" {
  description = "cadence-staging-oauth-first-party-client-secret - a plain string secret, not JSON."
  type        = string
}

variable "cors_allowed_origins" {
  type = string
}

variable "efs_file_system_id" {
  type = string
}

variable "efs_access_point_id" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
