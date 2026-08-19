module "vpc" {
  source = "../../modules/vpc"

  environment        = "staging"
  enable_nat_gateway = false # the backend EC2 instance gets a public IP instead - see modules/vpc/variables.tf and infra/README.md's cost section
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

data "aws_route53_zone" "this" {
  name         = "bioinform.co.uk."
  private_zone = false
}

# CloudFront's certificate must live in us-east-1 regardless of where
# everything else runs - see providers.tf's aws.us_east_1 alias. The API's
# TLS termination moved here from the ALB (which used a eu-west-2 cert) when
# the ALB was replaced by CloudFront-direct-to-EC2 - see
# infra/EC2_BACKEND_SKETCH.md.
module "acm_api" {
  source = "../../modules/acm"
  providers = {
    aws = aws.us_east_1
  }

  domain_name = "api.cadence.bioinform.co.uk"
  zone_id     = data.aws_route53_zone.this.zone_id
}

module "acm_frontend" {
  source = "../../modules/acm"
  providers = {
    aws = aws.us_east_1
  }

  domain_name = "cadence.bioinform.co.uk"
  zone_id     = data.aws_route53_zone.this.zone_id
}

# Same subdomain as the frontend, not the apex - bioinform.co.uk's own MX
# records (Google Workspace) are untouched by this; DKIM verification is
# CNAME-only. See modules/ses/variables.tf.
module "ses" {
  source = "../../modules/ses"

  domain_name = "cadence.bioinform.co.uk"
  zone_id     = data.aws_route53_zone.this.zone_id
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

locals {
  # module.ecr.repository_url is "registry/repo-name" as one string (e.g.
  # 423351912929.dkr.ecr.eu-west-2.amazonaws.com/cadence-backend-java) -
  # `docker login` needs just the registry host.
  ecr_parts = split("/", module.ecr.repository_url)
}

module "ec2" {
  source = "../../modules/ec2"

  environment = "staging"
  vpc_id      = module.vpc.vpc_id
  subnet_id   = module.vpc.public_subnet_ids[0] # public subnet, NAT is off - see modules/vpc/variables.tf's enable_nat_gateway

  ecr_registry  = local.ecr_parts[0]
  ecr_repo_name = local.ecr_parts[1]
  image_tag     = "sha-359a20b" # main's tip incl. PR #228 (JWT keys fix) - see infra/README.md's Step 3 continued section.

  db_address    = module.rds.address
  db_name       = module.rds.db_name
  db_username   = module.rds.master_username
  db_secret_arn = module.rds.master_user_secret_arn

  jwt_secret_arn = data.aws_secretsmanager_secret.jwt_keys.arn
  jwt_issuer     = "https://api.cadence.bioinform.co.uk" # real issuer, not the .env.example placeholder - see infra/README.md.

  oauth_secret_arn = data.aws_secretsmanager_secret.oauth_client_secret.arn

  # Real deployed frontend origin + local dev (kept, so the local frontend can still
  # be pointed at the staging backend for testing). NOT "*" - SecurityConfig.java sets
  # allowCredentials(true), and Spring throws at request time if allowedOrigins
  # contains "*" together with credentials enabled.
  cors_allowed_origins = "https://cadence.bioinform.co.uk,http://localhost:5173,http://localhost:3000"

  ses_identity_arn            = module.ses.arn
  email_from_address          = "no-reply@cadence.bioinform.co.uk"
  email_verification_base_url = "https://cadence.bioinform.co.uk/verify-email"
}

# The one ingress rule RDS's security group needs for the backend instance -
# defined here, not inside either module, since it's the composition of two
# modules' resources (see modules/rds/main.tf's comment on why RDS itself
# starts with zero ingress). Covers both the app's own DB traffic and the
# SSM-tunnel DB access this instance also serves (absorbed bastion role).
resource "aws_security_group_rule" "rds_from_ec2" {
  type                     = "ingress"
  from_port                = 5432
  to_port                  = 5432
  protocol                 = "tcp"
  security_group_id        = module.rds.security_group_id
  source_security_group_id = module.ec2.security_group_id
  description              = "Backend EC2 to RDS"
}

# CloudFront requires a DNS name for its origin, not a raw IP - this record
# exists purely for that. Set once, never touched again: the whole point of
# the Elastic IP in modules/ec2 is that it doesn't change across stop/start.
resource "aws_route53_record" "backend_origin" {
  zone_id = data.aws_route53_zone.this.zone_id
  name    = "backend-origin.cadence.bioinform.co.uk"
  type    = "A"
  ttl     = 300
  records = [module.ec2.public_ip]
}

module "api_cdn" {
  source = "../../modules/api_cdn"

  environment        = "staging"
  domain_name        = "api.cadence.bioinform.co.uk"
  origin_domain_name = aws_route53_record.backend_origin.fqdn
  certificate_arn    = module.acm_api.certificate_arn
}

# ALIAS records are Route 53's own extension (not a real DNS record type) -
# CloudFront has no fixed IP to point a normal A record at.
resource "aws_route53_record" "api" {
  zone_id = data.aws_route53_zone.this.zone_id
  name    = "api.cadence.bioinform.co.uk"
  type    = "A"

  alias {
    name                   = module.api_cdn.cloudfront_domain_name
    zone_id                = module.api_cdn.cloudfront_hosted_zone_id
    evaluate_target_health = false
  }
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
