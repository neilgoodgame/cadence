########################################################################
# Bootstrap: creates the S3 bucket that every other Terraform config's
# backend points at. This is the one config in the whole project that
# uses LOCAL state - it can't store its state in the bucket it's the
# one creating. Apply this once, then never touch it again unless the
# bucket itself needs to change.
########################################################################

terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # Deliberately no `backend` block here - see the file header.
}

provider "aws" {
  region  = "eu-west-2"
  profile = "neil"
}

resource "aws_s3_bucket" "tf_state" {
  bucket = "cadence-terraform-state-423351912929"

  # Safety net against `terraform destroy` (or a stray console click)
  # nuking every environment's state in one shot.
  lifecycle {
    prevent_destroy = true
  }
}

# Every write to the state file creates a new version instead of
# overwriting - this is the actual backup mechanism (recover a corrupted
# or bad state by rolling back to a prior version).
resource "aws_s3_bucket_versioning" "tf_state" {
  bucket = aws_s3_bucket.tf_state.id
  versioning_configuration {
    status = "Enabled"
  }
}

# State files routinely contain secrets in plain text (DB passwords,
# API keys) once real resources reference them - encrypt at rest.
resource "aws_s3_bucket_server_side_encryption_configuration" "tf_state" {
  bucket = aws_s3_bucket.tf_state.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
    bucket_key_enabled = true
  }
}

# Belt-and-braces: this bucket must never be reachable except via
# authenticated AWS API calls from Terraform/CI, never a public website
# or anonymous read.
resource "aws_s3_bucket_public_access_block" "tf_state" {
  bucket                  = aws_s3_bucket.tf_state.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

output "state_bucket_name" {
  value = aws_s3_bucket.tf_state.id
}
