variable "environment" {
  description = "Short environment name, used in resource names/tags (e.g. staging, production)."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block for the whole VPC."
  type        = string
  default     = "10.20.0.0/16"
}

variable "az_count" {
  description = "Number of Availability Zones to span. 2 is the practical minimum for anything that wants Multi-AZ later (RDS, ALB)."
  type        = number
  default     = 2
}

variable "single_nat_gateway" {
  description = "If true, create one NAT Gateway shared by all private subnets (cheaper - the standard staging choice). If false, one NAT Gateway per AZ (higher cost, no single point of failure - the standard production choice). Ignored entirely if enable_nat_gateway is false."
  type        = bool
  default     = true
}

variable "enable_nat_gateway" {
  description = "RDS and EFS never need outbound internet - only ECS tasks and the bastion do (ECR/Secrets Manager/CloudWatch Logs, and SSM respectively), and both of those can instead get a public IP directly (still locked down by their security groups - a public subnet means \"has an internet route,\" not \"open to the internet\") rather than paying a NAT Gateway's flat ~$36.50/mo just to relay that same traffic. False is the staging choice for this reason - see infra/README.md's cost section. Kept as a toggle (not deleted) for a future environment that genuinely needs private-subnet-only egress."
  type        = bool
  default     = true
}

variable "tags" {
  description = "Common tags applied to every resource this module creates."
  type        = map(string)
  default     = {}
}
