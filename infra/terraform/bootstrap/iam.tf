########################################################################
# A dedicated IAM user for Terraform to run as, instead of your personal
# `neil` login. Created here (using your currently-authenticated `neil`
# session) because it's the same kind of one-off, bootstrapping-only
# resource as the state bucket in main.tf - everything after this uses
# this user's credentials instead.
#
# Deliberately does NOT create an access key here: `aws_iam_access_key`
# would store the secret value in Terraform state in plain text (a
# well-known anti-pattern - state files are a common secret-leak vector).
# The access key is created out-of-band via the AWS CLI/console instead -
# see infra/README.md Step 2.5.
########################################################################

resource "aws_iam_user" "terraform" {
  name = "cadence-terraform"
  tags = { Purpose = "terraform-automation" }
}

# PowerUserAccess: full access to AWS services, EXCEPT IAM user/group
# management - the standard starting point for an infra-as-code operator
# in a single-owner account. IAMFullAccess is layered on top because
# Terraform itself needs to create IAM roles (ECS task roles, and later
# a GitHub Actions OIDC role) - PowerUserAccess alone blocks that.
#
# This is broader than a hand-scoped policy would be - acceptable here
# because this is a personal, single-operator account (no other tenants
# to isolate from) and a narrower custom policy would need constant
# iteration as new AWS services get used through the rest of this
# migration, which costs more in friction than it buys in safety at
# this scale. Revisit if this account ever stops being single-owner.
resource "aws_iam_user_policy_attachment" "terraform_power_user" {
  user       = aws_iam_user.terraform.name
  policy_arn = "arn:aws:iam::aws:policy/PowerUserAccess"
}

resource "aws_iam_user_policy_attachment" "terraform_iam_full" {
  user       = aws_iam_user.terraform.name
  policy_arn = "arn:aws:iam::aws:policy/IAMFullAccess"
}

output "terraform_iam_user_name" {
  value = aws_iam_user.terraform.name
}
