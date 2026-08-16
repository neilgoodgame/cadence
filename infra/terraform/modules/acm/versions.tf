# Needed because this module gets called twice with two different provider
# configurations (default eu-west-2 for the API cert, aws.us_east_1 for the
# frontend/CloudFront cert) - without this, Terraform only warns, but this
# makes the module's provider contract explicit instead of implicit.
terraform {
  required_providers {
    aws = {
      source                = "hashicorp/aws"
      configuration_aliases = [aws]
    }
  }
}
