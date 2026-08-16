module "vpc" {
  source = "../../modules/vpc"

  environment        = "staging"
  single_nat_gateway = true # staging: cost over redundancy - see infra/README.md Step 2
}

module "rds" {
  source = "../../modules/rds"

  environment        = "staging"
  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids
  # Every other argument left at its staging-appropriate default - see
  # modules/rds/variables.tf (db.t4g.micro, single-AZ, skip_final_snapshot = true).
}

module "ecr" {
  source = "../../modules/ecr"

  name = "backend-java"
}

module "bastion" {
  source = "../../modules/bastion"

  environment       = "staging"
  vpc_id            = module.vpc.vpc_id
  private_subnet_id = module.vpc.private_subnet_ids[0]
}

# The one ingress rule RDS's security group needs for the bastion - defined here,
# not inside either module, since it's the composition of two modules' resources
# (see modules/rds/main.tf's comment on why RDS itself starts with zero ingress).
resource "aws_security_group_rule" "rds_from_bastion" {
  type                     = "ingress"
  from_port                = 5432
  to_port                  = 5432
  protocol                 = "tcp"
  security_group_id        = module.rds.security_group_id
  source_security_group_id = module.bastion.security_group_id
  description              = "Bastion SSM to RDS"
}

module "efs" {
  source = "../../modules/efs"

  environment        = "staging"
  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids
}

data "aws_route53_zone" "this" {
  name         = "bioinform.co.uk."
  private_zone = false
}

module "acm" {
  source = "../../modules/acm"
  providers = {
    aws = aws
  }

  domain_name = "api.cadence.bioinform.co.uk"
  zone_id     = data.aws_route53_zone.this.zone_id
}

module "alb" {
  source = "../../modules/alb"

  environment       = "staging"
  vpc_id            = module.vpc.vpc_id
  public_subnet_ids = module.vpc.public_subnet_ids
  certificate_arn   = module.acm.certificate_arn
}

# ALIAS records are Route 53's own extension (not a real DNS record type) - the
# ALB has no fixed IP to point a normal A record at, and this avoids the extra
# lookup + TTL/caching issues a CNAME-at-apex-adjacent setup would have.
resource "aws_route53_record" "api" {
  zone_id = data.aws_route53_zone.this.zone_id
  name    = "api.cadence.bioinform.co.uk"
  type    = "A"

  alias {
    name                   = module.alb.dns_name
    zone_id                = module.alb.zone_id
    evaluate_target_health = true
  }
}

# Created by hand (see infra/README.md's Step 3 continued section) rather than
# by Terraform, same reasoning as the RDS master password: the actual secret
# value should never pass through this config or state. Looked up by name so
# nobody needs to paste an ARN back into Terraform.
data "aws_secretsmanager_secret" "jwt_keys" {
  name = "cadence-staging-jwt-keys"
}

data "aws_secretsmanager_secret" "oauth_client_secret" {
  name = "cadence-staging-oauth-first-party-client-secret"
}

module "ecs" {
  source = "../../modules/ecs"

  environment        = "staging"
  vpc_id             = module.vpc.vpc_id
  private_subnet_ids = module.vpc.private_subnet_ids

  ecr_repository_url = module.ecr.repository_url
  image_tag          = "sha-359a20b" # main's tip incl. PR #228 (JWT keys fix) - see infra/README.md's Step 3 continued section.

  target_group_arn      = module.alb.target_group_arn
  listener_arn          = module.alb.listener_arn
  alb_security_group_id = module.alb.security_group_id

  db_address                = module.rds.address
  db_name                   = module.rds.db_name
  db_master_username        = module.rds.master_username
  db_master_user_secret_arn = module.rds.master_user_secret_arn

  jwt_keys_secret_arn = data.aws_secretsmanager_secret.jwt_keys.arn
  jwt_issuer          = "https://api.cadence.bioinform.co.uk" # real issuer, not the .env.example placeholder - see infra/README.md.

  oauth_client_secret_arn = data.aws_secretsmanager_secret.oauth_client_secret.arn

  # Real deployed frontend origin + local dev (kept, so the local frontend can still
  # be pointed at the staging backend for testing). NOT "*" - SecurityConfig.java sets
  # allowCredentials(true), and Spring throws at request time if allowedOrigins
  # contains "*" together with credentials enabled.
  cors_allowed_origins = "https://cadence.bioinform.co.uk,http://localhost:5173,http://localhost:3000"

  efs_file_system_id  = module.efs.file_system_id
  efs_access_point_id = module.efs.access_point_id
}

# ALB -> ECS tasks on 8080 - the port the container listens on.
resource "aws_security_group_rule" "ecs_from_alb" {
  type                     = "ingress"
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  security_group_id        = module.ecs.security_group_id
  source_security_group_id = module.alb.security_group_id
  description              = "ALB to ECS tasks"
}

# ECS tasks -> RDS on 5432 - mirrors the existing bastion rule above.
resource "aws_security_group_rule" "rds_from_ecs" {
  type                     = "ingress"
  from_port                = 5432
  to_port                  = 5432
  protocol                 = "tcp"
  security_group_id        = module.rds.security_group_id
  source_security_group_id = module.ecs.security_group_id
  description              = "ECS tasks to RDS"
}

# ECS tasks -> EFS on 2049 (NFS) - the /app/media mount.
resource "aws_security_group_rule" "efs_from_ecs" {
  type                     = "ingress"
  from_port                = 2049
  to_port                  = 2049
  protocol                 = "tcp"
  security_group_id        = module.efs.security_group_id
  source_security_group_id = module.ecs.security_group_id
  description              = "ECS tasks to EFS"
}

# CloudFront needs its cert in us-east-1 - see providers.tf's aws.us_east_1
# alias. Separate cert from the API's (module.acm), not a SAN on it.
module "acm_frontend" {
  source = "../../modules/acm"
  providers = {
    aws = aws.us_east_1
  }

  domain_name = "cadence.bioinform.co.uk"
  zone_id     = data.aws_route53_zone.this.zone_id
}

module "frontend" {
  source = "../../modules/frontend"

  environment     = "staging"
  domain_name     = "cadence.bioinform.co.uk"
  certificate_arn = module.acm_frontend.certificate_arn
}

resource "aws_route53_record" "frontend" {
  zone_id = data.aws_route53_zone.this.zone_id
  name    = "cadence.bioinform.co.uk"
  type    = "A"

  alias {
    name                   = module.frontend.cloudfront_domain_name
    zone_id                = module.frontend.cloudfront_hosted_zone_id
    evaluate_target_health = false
  }
}
