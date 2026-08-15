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
