########################################################################
# EFS for /app/media - the Java backend has no S3 storage code at all
# (UploadIngestService/ExportService/ImportController all resolve plain
# local filesystem paths via CadenceProperties.uploads().mediaRoot()), so
# on Fargate's ephemeral disk every deploy/restart would silently lose
# uploaded activity files and pending export/import artifacts. Mounting
# EFS at the same /app/media path needs zero app code changes - it's
# still a POSIX path underneath - and survives task replacement, and is
# shared if the service is ever scaled beyond one task. A real S3-backed
# storage abstraction (matching AWS_MIGRATION_PLAN.md's long-term
# architecture) can still land later as its own PR; see infra/README.md.
########################################################################

resource "aws_efs_file_system" "media" {
  creation_token = "cadence-${var.environment}-media"
  encrypted      = true

  tags = merge(var.tags, { Name = "cadence-${var.environment}-media" })
}

# One mount target per private subnet/AZ, matching how ECS tasks can land in
# any of them. No ingress rules yet - ECS's security group gets the one rule
# this needs (2049 from the ECS tasks SG) at the root module level, same
# pattern as RDS's security group.
resource "aws_security_group" "efs" {
  name_prefix = "cadence-${var.environment}-efs-"
  description = "EFS for app media - ingress added once the ECS service exists"
  vpc_id      = var.vpc_id

  egress {
    description = "Allow all outbound (standard SG hygiene, EFS itself rarely initiates outbound traffic)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "cadence-${var.environment}-efs-sg" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_efs_mount_target" "media" {
  for_each = toset(var.private_subnet_ids)

  file_system_id  = aws_efs_file_system.media.id
  subnet_id       = each.value
  security_groups = [aws_security_group.efs.id]
}

# Scopes the mount to /media within the filesystem (not its root) and enforces
# a fixed POSIX owner - the container runs as root (no USER in the Dockerfile,
# matching how `mkdir -p /app/media` runs at build time), so 0:0 matches what
# the app already expects on local/Docker Compose.
resource "aws_efs_access_point" "media" {
  file_system_id = aws_efs_file_system.media.id

  posix_user {
    uid = 0
    gid = 0
  }

  root_directory {
    path = "/media"
    creation_info {
      owner_uid   = 0
      owner_gid   = 0
      permissions = "755"
    }
  }

  tags = merge(var.tags, { Name = "cadence-${var.environment}-media-ap" })
}
