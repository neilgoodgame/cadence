module "vpc" {
  source = "../../modules/vpc"

  environment        = "staging"
  single_nat_gateway = true # staging: cost over redundancy - see infra/README.md Step 2
}
