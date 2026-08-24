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

variable "oauth_mcp_secret_arn" {
  description = "cadence-staging-oauth-mcp-client-secret - a plain string secret, not JSON. Distinct from oauth_secret_arn: the MCP client (Claude.ai/Desktop's remote connector) is a separate, real third-party OAuth client from the first-party frontend client."
  type        = string
}

variable "oauth_issuer" {
  description = "Real deployed issuer (https://api.cadence.bioinform.co.uk), not the code's cadence.cc placeholder default - used in the MCP server's RFC 8414/9728 discovery documents. Mirrors jwt_issuer's existing override."
  type        = string
}

variable "cors_allowed_origins" {
  type = string
}

variable "ses_identity_arn" {
  description = "SES domain identity ARN (modules/ses's output) - the instance role gets ses:SendEmail scoped to exactly this, for EmailVerificationService's outgoing mail."
  type        = string
}

variable "email_from_address" {
  description = "Must be an address on the verified SES domain identity, e.g. no-reply@cadence.bioinform.co.uk."
  type        = string
}

variable "email_verification_base_url" {
  description = "Where the frontend's /verify-email screen lives - the token is appended as ?token=..."
  type        = string
}

variable "ses_region" {
  description = "Passed explicitly rather than left to SesV2Client's default region provider chain - see application.yml's cadence.email.ses-region comment for why."
  type        = string
  default     = "eu-west-2"
}

variable "tags" {
  type    = map(string)
  default = {}
}
