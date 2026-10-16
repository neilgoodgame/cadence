########################################################################
# RDS PostgreSQL 16. The app uses native table partitioning
# (activities_record), not TimescaleDB, so nothing here needs a custom
# engine extension for the app's own sake - see AWS_MIGRATION_PLAN.md §4.
#
# The parameter group below exists purely for query observability:
# pg_stat_statements (already preloaded by RDS's own default parameter
# group, just needed `CREATE EXTENSION` to expose it - no Terraform change
# for that part) plus auto_explain, which isn't preloaded by default and
# needs this custom group. Loading a new shared library is a *static*
# parameter, so attaching this group requires one DB reboot to take
# effect - do that deliberately, not as a surprise side effect of an
# unrelated apply.
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

# Family must track engine_version's major version (16.x -> "postgres16"), not the
# full version string - RDS parameter group families are major-version-scoped.
resource "aws_db_parameter_group" "this" {
  name   = "cadence-${var.environment}-postgres16"
  family = "postgres16"

  # Static: only takes effect after a reboot (see the module header comment).
  # pg_stat_statements,pg_tle match RDS's own default-parameter-group preload
  # list exactly - only auto_explain is actually new here. Dropping
  # pg_stat_statements/pg_tle instead of carrying them forward would silently
  # unload them on reboot.
  parameter {
    name         = "shared_preload_libraries"
    value        = "pg_stat_statements,pg_tle,auto_explain"
    apply_method = "pending-reboot"
  }

  # Dynamic - only logs plans for genuinely slow outliers (500ms+), not every
  # query, to keep this cheap enough for a db.t4g.micro. log_analyze/log_buffers
  # add real overhead per logged statement, but that's fine when it's gated by
  # log_min_duration to just the slow tail.
  parameter {
    name  = "auto_explain.log_min_duration"
    value = "500"
  }

  parameter {
    name  = "auto_explain.log_analyze"
    value = "1"
  }

  parameter {
    name  = "auto_explain.log_buffers"
    value = "1"
  }

  parameter {
    name  = "auto_explain.log_nested_statements"
    value = "1"
  }

  tags = var.tags
}

# Auto-created by RDS once enabled_cloudwatch_logs_exports includes "postgresql"
# below, but managed here too so retention isn't left at the CloudWatch default
# of "Never expire" - same reasoning as the backend's own log group in the ec2
# module.
resource "aws_cloudwatch_log_group" "postgresql" {
  name              = "/aws/rds/instance/cadence-${var.environment}/postgresql"
  retention_in_days = var.log_retention_days

  tags = var.tags
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
  parameter_group_name   = aws_db_parameter_group.this.name

  # Ships auto_explain's captured slow-query plans (and anything else Postgres
  # logs) to the CloudWatch log group above instead of only RDS's own
  # describe-db-log-files API.
  enabled_cloudwatch_logs_exports = ["postgresql"]

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

  depends_on = [aws_cloudwatch_log_group.postgresql]
}
