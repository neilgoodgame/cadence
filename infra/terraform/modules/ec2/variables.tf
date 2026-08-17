variable "environment" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_id" {
  description = "A single subnet is enough - one instance, not a fleet. Public, since NAT is off (see modules/vpc/variables.tf's enable_nat_gateway) - locked down by this module's own security group (CloudFront origin-facing IPs only), not by network topology."
  type        = string
}

variable "instance_type" {
  type    = string
  default = "t4g.micro" # ARM/Graviton, matches the ECR image. Cheaper per-hour than the Fargate task it replaces even running 24/7 - see infra/EC2_BACKEND_SKETCH.md.
}

variable "ecr_registry" {
  description = "e.g. 423351912929.dkr.ecr.eu-west-2.amazonaws.com - the registry host, without the repo name (needed separately for `docker login`)."
  type        = string
}

variable "ecr_repo_name" {
  type = string
}

variable "image_tag" {
  description = "Only the INITIAL value of the SSM parameter run.sh actually reads at container-start time (see aws_ssm_parameter.image_tag's ignore_changes) - changing this after first apply has no effect on a running instance. Deploys update the parameter directly instead, e.g. via infra/scripts/deploy-backend.sh."
  type        = string
}

variable "log_retention_days" {
  type    = number
  default = 30
}

variable "db_address" {
  type = string
}

variable "db_name" {
  type = string
}

variable "db_username" {
  type = string
}

variable "db_secret_arn" {
  description = "RDS-managed Secrets Manager secret - JSON with a \"password\" key."
  type        = string
}

variable "jwt_secret_arn" {
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

variable "oauth_secret_arn" {
  description = "cadence-staging-oauth-first-party-client-secret - a plain string secret, not JSON."
  type        = string
}

variable "cors_allowed_origins" {
  type = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
