########################################################################
# RDS PostgreSQL - plain Postgres 16, no custom parameter group. The app
# uses native table partitioning (activities_record), not TimescaleDB, so
# nothing here needs shared_preload_libraries or any other non-default
# server config - see AWS_MIGRATION_PLAN.md §4 for why.
########################################################################

resource "aws_db_subnet_group" "this" {
  name       = "cadence-${var.environment}-db"
  subnet_ids = var.private_subnet_ids
  tags       = merge(var.tags, { Name = "cadence-${var.environment}-db-subnet-group" })
}

# No ingress rules yet - nothing should be able to reach RDS until the ECS service
# exists. Its one rule (ECS -> RDS on 5432) gets added at the root module level once
# both this module's security group and the ECS module's security group exist - see
# infra/README.md's Step 3 notes.
resource "aws_security_group" "rds" {
  name_prefix = "cadence-${var.environment}-rds-"
  description = "RDS PostgreSQL - ingress added once the ECS service exists"
  vpc_id      = var.vpc_id

  egress {
    description = "Allow all outbound (RDS itself rarely initiates outbound traffic, but this is standard SG hygiene, not a real attack surface)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(var.tags, { Name = "cadence-${var.environment}-rds-sg" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "this" {
  identifier     = "cadence-${var.environment}"
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  allocated_storage     = var.allocated_storage
  max_allocated_storage = var.max_allocated_storage
  storage_type          = "gp3"

  db_name  = var.db_name
  username = var.master_username
  # No `password` - RDS creates and rotates the master password in Secrets Manager
  # instead, so the actual secret value never appears in this config or in state.
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  multi_az               = var.multi_az

  backup_retention_period = var.backup_retention_period
  skip_final_snapshot     = var.skip_final_snapshot
  # Fixed, not timestamped - a live timestamp() here would show a diff on every single
  # plan. Only used if this instance is ever actually destroyed with skip_final_snapshot
  # = false; on the rare chance a same-named snapshot already exists from a prior
  # destroy, AWS errors clearly at that point rather than silently overwriting anything.
  final_snapshot_identifier = var.skip_final_snapshot ? null : "cadence-${var.environment}-final-snapshot"

  auto_minor_version_upgrade = true
  deletion_protection        = !var.skip_final_snapshot # matches: production (final snapshot required) also gets deletion protection.

  tags = merge(var.tags, { Name = "cadence-${var.environment}-db" })
}
