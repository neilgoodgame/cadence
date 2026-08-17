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

# Scoped to exactly the 3 secrets run.sh resolves - not a blanket grant.
data "aws_iam_policy_document" "secrets" {
  statement {
    effect  = "Allow"
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      var.db_secret_arn,
      var.jwt_secret_arn,
      var.oauth_secret_arn,
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
  instance_type           = var.instance_type
  subnet_id               = var.subnet_id
  iam_instance_profile    = aws_iam_instance_profile.this.name
  vpc_security_group_ids  = [aws_security_group.this.id]
  # No key_pair - SSM Session Manager is the only access path, same reasoning
  # as the old bastion.

  user_data = templatefile("${path.module}/user_data.sh.tpl", {
    region               = "eu-west-2"
    ecr_registry         = var.ecr_registry
    ecr_repo_name        = var.ecr_repo_name
    image_tag            = var.image_tag
    db_address            = var.db_address
    db_name               = var.db_name
    db_username           = var.db_username
    db_secret_arn         = var.db_secret_arn
    jwt_secret_arn        = var.jwt_secret_arn
    jwt_kid               = var.jwt_kid
    jwt_issuer            = var.jwt_issuer
    jwt_audience          = var.jwt_audience
    oauth_secret_arn      = var.oauth_secret_arn
    cors_allowed_origins  = var.cors_allowed_origins
    log_group_name        = aws_cloudwatch_log_group.backend.name
  })

  tags = merge(var.tags, { Name = "cadence-${var.environment}-backend" })
}

# Stays attached across stop/start - the whole point (see the module header
# comment). NOT the instance's automatic public IP, which would change on
# every replacement the same way Fargate's did.
resource "aws_eip" "this" {
  domain   = "vpc"
  instance = aws_instance.this.id

  tags = merge(var.tags, { Name = "cadence-${var.environment}-backend-eip" })
}
