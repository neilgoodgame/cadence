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

# CloudFront's certificate must live in us-east-1 regardless of where the
# distribution's other resources run - an AWS-wide CloudFront requirement,
# not a choice made here. Only used for the frontend's ACM cert.
provider "aws" {
  alias   = "us_east_1"
  region  = "us-east-1"
  profile = "cadence-terraform"

  default_tags {
    tags = {
      Project     = "cadence"
      Environment = "staging"
      ManagedBy   = "terraform"
    }
  }
}
