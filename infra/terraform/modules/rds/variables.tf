variable "environment" {
  description = "Short environment name, used in resource names/tags (e.g. staging, production)."
  type        = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  description = "Must span >= 2 AZs - required for the DB subnet group even in single-AZ mode, and a prerequisite for turning on Multi-AZ later without a network change."
  type        = list(string)
}

variable "instance_class" {
  type    = string
  default = "db.t4g.micro" # staging default - see AWS_MIGRATION_PLAN.md §11 cost table.
}

variable "allocated_storage" {
  description = "Initial storage in GiB. Autoscaling (max_allocated_storage) matters far more than this at our data volume - see AWS_MIGRATION_PLAN.md §4.1."
  type        = number
  default     = 20
}

variable "max_allocated_storage" {
  description = "RDS Storage Autoscaling ceiling in GiB - grows automatically as activities_record fills up, no manual resizing needed."
  type        = number
  default     = 100
}

variable "multi_az" {
  description = "Standby replica in a second AZ with automatic failover. Real cost driver (~doubles RDS spend) - off by default for staging, turn on for production."
  type        = bool
  default     = false
}

variable "backup_retention_period" {
  description = "Days of automated backups to retain (0-35). 0 disables automated backups entirely."
  type        = number
  default     = 7
}

variable "skip_final_snapshot" {
  description = "Staging default: true, so the instance can be torn down/recreated during learning iteration without a snapshot. Set false for production."
  type        = bool
  default     = true
}

variable "db_name" {
  type    = string
  default = "cadence"
}

variable "master_username" {
  type    = string
  default = "cadence"
}

variable "engine_version" {
  description = "Confirmed available on RDS as of writing (aws rds describe-db-engine-versions) - bump periodically, auto_minor_version_upgrade handles patch releases in between."
  type        = string
  default     = "16.14"
}

variable "tags" {
  type    = map(string)
  default = {}
}
