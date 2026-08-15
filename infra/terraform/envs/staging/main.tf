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
