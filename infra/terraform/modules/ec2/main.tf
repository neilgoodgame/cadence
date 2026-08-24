########################################################################
# The Java backend, running directly on one EC2 instance instead of ECS
# Fargate - see infra/EC2_BACKEND_SKETCH.md for the full reasoning. Also
# absorbs the old bastion's role (SSM-only DB access - same instance now
# serves traffic AND is the thing you SSM-tunnel through for ad-hoc RDS
# access), so there's no separate bastion module anymore.
#
# An Elastic IP (not the instance's default public IP) is what makes this
# work with CloudFront in front instead of an ALB: EIPs stay attached across
# stop/start, so the address never changes - CloudFront's origin config
# gets set once and never touched again. No dynamic-DNS automation needed.
########################################################################

data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/ec2/cadence-${var.environment}-backend"
  retention_in_days = var.log_retention_days

  tags = var.tags
}

resource "aws_iam_role" "this" {
  name = "cadence-${var.environment}-backend"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

# Absorbed bastion role - SSM agent registration/session access, same AWS-
# managed policy the old bastion module used.
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.this.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# Scoped to exactly the 4 secrets run.sh resolves - not a blanket grant.
data "aws_iam_policy_document" "secrets" {
  statement {
    effect  = "Allow"
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      var.db_secret_arn,
      var.jwt_secret_arn,
      var.oauth_secret_arn,
      var.oauth_mcp_secret_arn,
    ]
  }
}

resource "aws_iam_role_policy" "secrets" {
  name   = "cadence-${var.environment}-backend-secrets"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.secrets.json
}

# ECR pull, scoped to the one repo - ecr:GetAuthorizationToken has no
# resource-level permissions in AWS's IAM model (must be "*"), everything
# else is scoped.
data "aws_caller_identity" "current" {}

data "aws_iam_policy_document" "ecr" {
  statement {
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    effect = "Allow"
    actions = [
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchCheckLayerAvailability",
    ]
    resources = ["arn:aws:ecr:eu-west-2:${data.aws_caller_identity.current.account_id}:repository/${var.ecr_repo_name}"]
  }
}

resource "aws_iam_role_policy" "ecr" {
  name   = "cadence-${var.environment}-backend-ecr"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.ecr.json
}

data "aws_iam_policy_document" "logs" {
  statement {
    effect = "Allow"
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
    ]
    resources = ["${aws_cloudwatch_log_group.backend.arn}:*"]
  }
}

resource "aws_iam_role_policy" "logs" {
  name   = "cadence-${var.environment}-backend-logs"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.logs.json
}

# Scoped to exactly the one verified domain identity - not "*".
data "aws_iam_policy_document" "ses" {
  statement {
    effect    = "Allow"
    actions   = ["ses:SendEmail"]
    resources = [var.ses_identity_arn]
  }
}

resource "aws_iam_role_policy" "ses" {
  name   = "cadence-${var.environment}-backend-ses"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.ses.json
}

# The "which image tag is currently live" state, deliberately kept OUT of
# user_data/Terraform's own knowledge after this initial value - run.sh reads
# it fresh on every start, the same way it already re-reads secrets fresh.
# Routing new tags through Terraform instead (a user_data change) triggers a
# real instance reboot on every apply, even without forcing full replacement -
# learned the hard way right after this module first shipped, see
# infra/README.md's Step 7. ignore_changes so a deploy script's update
# survives whatever the next unrelated `terraform apply` does.
resource "aws_ssm_parameter" "image_tag" {
  name  = "/cadence/${var.environment}/backend-image-tag"
  type  = "String"
  value = var.image_tag

  tags = var.tags

  lifecycle {
    ignore_changes = [value]
  }
}

data "aws_iam_policy_document" "image_tag_param" {
  statement {
    effect    = "Allow"
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.image_tag.arn]
  }
}

resource "aws_iam_role_policy" "image_tag_param" {
  name   = "cadence-${var.environment}-backend-image-tag-param"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.image_tag_param.json
}

resource "aws_iam_instance_profile" "this" {
  name = "cadence-${var.environment}-backend"
  role = aws_iam_role.this.name
}

# Only CloudFront's own edge IPs can reach the app port - not the open
# internet. The plaintext CloudFront-to-origin hop this implies is a
# documented, accepted AWS pattern given that restriction - see
# infra/EC2_BACKEND_SKETCH.md. SSM needs zero ingress rules regardless
# (outbound-initiated connection model), same as the old bastion.
resource "aws_security_group" "this" {
  name_prefix = "cadence-${var.environment}-backend-"
  description = "Backend EC2 instance - ingress only from CloudFront origin-facing IPs"
  vpc_id      = var.vpc_id

  ingress {
    description     = "CloudFront origin-facing traffic only"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    prefix_list_ids = [data.aws_ec2_managed_prefix_list.cloudfront.id]
  }

  egress {
    description = "ECR/Secrets Manager/CloudWatch Logs/SSM (own public IP, no NAT), plus RDS within the VPC"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "cadence-${var.environment}-backend-sg" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_instance" "this" {
  ami                    = data.aws_ssm_parameter.al2023_arm64.value
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id
  iam_instance_profile   = aws_iam_instance_profile.this.name
  vpc_security_group_ids = [aws_security_group.this.id]
  # No key_pair - SSM Session Manager is the only access path, same reasoning
  # as the old bastion.

  # Without this, a user_data change modifies the EC2 attribute in place and
  # reboots the SAME instance - which looks like it works (Terraform reports
  # success, the app comes back up) but cloud-init's AWS datasource keys its
  # "have I already run user-data" semaphore off the instance ID, which a
  # stop/start never changes. The reboot silently does nothing: the new
  # script is staged on disk but never executed, and the OLD run.sh (and
  # whatever image tag/env vars were baked into it at first boot) keeps
  # running indefinitely. Found the hard way: Step 8's SSM-parameter change
  # and this step's SES env vars both "succeeded" per Terraform/healthz
  # while actually still running the instance's very first user_data. A real
  # replace (new instance ID) is the only way cloud-init genuinely re-runs
  # it - acceptable here since /opt/cadence/media isn't a separate
  # persistent volume anyway (see the ami ignore_changes comment below) and
  # is confirmed empty on this environment as of 2026-08-19.
  user_data_replace_on_change = true

  user_data = templatefile("${path.module}/user_data.sh.tpl", {
    region                      = "eu-west-2"
    ecr_registry                = var.ecr_registry
    ecr_repo_name               = var.ecr_repo_name
    image_tag_parameter_name    = aws_ssm_parameter.image_tag.name
    db_address                  = var.db_address
    db_name                     = var.db_name
    db_username                 = var.db_username
    db_secret_arn               = var.db_secret_arn
    jwt_secret_arn              = var.jwt_secret_arn
    jwt_kid                     = var.jwt_kid
    jwt_issuer                  = var.jwt_issuer
    jwt_audience                = var.jwt_audience
    oauth_secret_arn            = var.oauth_secret_arn
    oauth_mcp_secret_arn        = var.oauth_mcp_secret_arn
    oauth_issuer                = var.oauth_issuer
    cors_allowed_origins        = var.cors_allowed_origins
    log_group_name              = aws_cloudwatch_log_group.backend.name
    email_from_address          = var.email_from_address
    email_verification_base_url = var.email_verification_base_url
    ses_region                  = var.ses_region
  })

  tags = merge(var.tags, { Name = "cadence-${var.environment}-backend" })

  lifecycle {
    # The "latest AL2023 arm64" SSM parameter drifts as AWS publishes new
    # AMI builds - without this, any apply (even one unrelated to the AMI)
    # would force a full destroy/recreate the moment that parameter moves,
    # losing /opt/cadence/media (it lives on the root EBS volume, not a
    # separate persistent one - nothing preserves it across a real replace).
    # AMI upgrades are a deliberate future step, not a surprise side effect.
    ignore_changes = [ami]
  }
}

# Stays attached across stop/start - the whole point (see the module header
# comment). NOT the instance's automatic public IP, which would change on
# every replacement the same way Fargate's did.
resource "aws_eip" "this" {
  domain   = "vpc"
  instance = aws_instance.this.id

  tags = merge(var.tags, { Name = "cadence-${var.environment}-backend-eip" })
}
