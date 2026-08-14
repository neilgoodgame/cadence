provider "aws" {
  region  = "eu-west-2"
  profile = "cadence-terraform"

  default_tags {
    tags = {
      Project     = "cadence"
      Environment = "staging"
      ManagedBy   = "terraform"
    }
  }
}
