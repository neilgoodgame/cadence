terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  backend "s3" {
    bucket       = "cadence-terraform-state-423351912929"
    key          = "staging/terraform.tfstate"
    region       = "eu-west-2"
    profile      = "cadence-terraform"
    use_lockfile = true
    encrypt      = true
  }
}
