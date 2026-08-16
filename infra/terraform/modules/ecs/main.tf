########################################################################
# ECS Fargate service running the Java backend. ARM64/Graviton, matching
# the bastion and the ECR image already pushed - see infra/README.md's
# ECR section. No ingress rules on this module's own security group -
# added at the root module level once the ALB's security group exists,
# same cross-module pattern as RDS <- bastion.
########################################################################

resource "aws_ecs_cluster" "this" {
  name = "cadence-${var.environment}"

  setting {
    name  = "containerInsights"
    value = "disabled" # extra CloudWatch cost with no real traffic to observe yet - turn on if/when it's worth watching.
  }

  tags = merge(var.tags, { Name = "cadence-${var.environment}" })
}

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/ecs/cadence-${var.environment}-backend"
  retention_in_days = var.log_retention_days

  tags = var.tags
}

########################################################################
# IAM - two distinct roles, standard ECS split:
# - execution role: what the ECS agent itself needs (pull from ECR, write
#   logs, resolve task-definition `secrets` from Secrets Manager before
#   the container even starts).
# - task role: what the running application needs at runtime (here, just
#   EFS client mount/write via IAM auth - the app makes no other AWS API
#   calls today).
########################################################################

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "cadence-${var.environment}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = var.tags
}

# AWS-managed policy: ECR pull + CloudWatch Logs write - the baseline every
# Fargate task needs regardless of what secrets it uses.
resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Scoped to exactly the 3 secrets this task definition references - not a
# blanket secretsmanager:GetSecretValue on "*".
data "aws_iam_policy_document" "execution_secrets" {
  statement {
    effect  = "Allow"
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      var.db_master_user_secret_arn,
      var.jwt_keys_secret_arn,
      var.oauth_client_secret_arn,
    ]
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "cadence-${var.environment}-ecs-execution-secrets"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

resource "aws_iam_role" "task" {
  name               = "cadence-${var.environment}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = var.tags
}

# IAM-authorized EFS access (in addition to the security-group-level NFS
# rule) - matches the `iam = "ENABLED"` authorization_config on the volume
# below. Scoped to this one filesystem + access point, not all of EFS.
data "aws_iam_policy_document" "task_efs" {
  statement {
    effect = "Allow"
    actions = [
      "elasticfilesystem:ClientMount",
      "elasticfilesystem:ClientWrite",
    ]
    resources = ["arn:aws:elasticfilesystem:eu-west-2:*:file-system/${var.efs_file_system_id}"]
    condition {
      test     = "StringEquals"
      variable = "elasticfilesystem:AccessPointArn"
      values   = ["arn:aws:elasticfilesystem:eu-west-2:*:access-point/${var.efs_access_point_id}"]
    }
  }
}

resource "aws_iam_role_policy" "task_efs" {
  name   = "cadence-${var.environment}-ecs-task-efs"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task_efs.json
}

resource "aws_security_group" "ecs" {
  name_prefix = "cadence-${var.environment}-ecs-"
  description = "ECS tasks - ingress added once the ALB exists"
  vpc_id      = var.vpc_id

  egress {
    description = "Allow all outbound (ECR/CloudWatch/Secrets Manager directly over the internet - this task has its own public IP, no NAT gateway - plus RDS/EFS within the VPC)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "cadence-${var.environment}-ecs-sg" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_ecs_task_definition" "backend" {
  family                   = "cadence-${var.environment}-backend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  runtime_platform {
    cpu_architecture        = "ARM64"
    operating_system_family = "LINUX"
  }

  volume {
    name = "media"

    efs_volume_configuration {
      file_system_id     = var.efs_file_system_id
      transit_encryption = "ENABLED"

      authorization_config {
        access_point_id = var.efs_access_point_id
        iam             = "ENABLED"
      }
    }
  }

  # See backend_java/.env.example for the full env var list this mirrors, and
  # entrypoint.sh for how JWT_PRIVATE_KEY_PEM/JWT_PUBLIC_KEY_PEM get consumed
  # (materialized to /app/keys on every boot - PR #228).
  container_definitions = jsonencode([
    {
      name      = "backend"
      image     = "${var.ecr_repository_url}:${var.image_tag}"
      essential = true

      portMappings = [
        { containerPort = 8080, protocol = "tcp" }
      ]

      environment = [
        { name = "POSTGRES_HOST", value = var.db_address },
        { name = "POSTGRES_PORT", value = tostring(var.db_port) },
        { name = "POSTGRES_DB", value = var.db_name },
        { name = "POSTGRES_USER", value = var.db_master_username },
        { name = "JWT_KID", value = var.jwt_kid },
        { name = "JWT_ISSUER", value = var.jwt_issuer },
        { name = "JWT_AUDIENCE", value = var.jwt_audience },
        { name = "CORS_ALLOWED_ORIGINS", value = var.cors_allowed_origins },
      ]

      secrets = [
        { name = "POSTGRES_PASSWORD", valueFrom = "${var.db_master_user_secret_arn}:password::" },
        { name = "JWT_PRIVATE_KEY_PEM", valueFrom = "${var.jwt_keys_secret_arn}:jwt_private_key_pem::" },
        { name = "JWT_PUBLIC_KEY_PEM", valueFrom = "${var.jwt_keys_secret_arn}:jwt_public_key_pem::" },
        { name = "OAUTH_FIRST_PARTY_CLIENT_SECRET", valueFrom = var.oauth_client_secret_arn },
      ]

      mountPoints = [
        { sourceVolume = "media", containerPath = "/app/media", readOnly = false }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.backend.name
          "awslogs-region"        = "eu-west-2"
          "awslogs-stream-prefix" = "backend"
        }
      }
    }
  ])

  tags = var.tags
}

resource "aws_ecs_service" "backend" {
  name            = "cadence-${var.environment}-backend"
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.backend.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = [aws_security_group.ecs.id]
    assign_public_ip = var.assign_public_ip
  }

  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = "backend"
    container_port   = 8080
  }

  # Without this, ECS starts counting ALB health-check failures against a task the
  # instant it's registered (default grace period is 0). This Spring Boot app's cold
  # boot (Flyway + Hibernate + Spring Security all initializing) took 50-72s across
  # two real observed deploys - comfortably past the ALB target group's own
  # unhealthy_threshold window, so without a grace period ECS kills every fresh task
  # for "failing" health checks it was never actually given time to pass. Found live:
  # the second deployment (a force-new-deployment after rotating the RDS password)
  # got killed this way even though the app booted and connected to the DB just fine.
  health_check_grace_period_seconds = 180

  # Not referenced in any argument above - this is here purely so Terraform
  # waits for the ALB listener to exist before creating the service, not just
  # the target group (see variables.tf's comment on listener_arn).
  depends_on = [var.listener_arn]

  tags = var.tags
}
