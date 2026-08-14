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
  description = "If true, create one NAT Gateway shared by all private subnets (cheaper - the standard staging choice). If false, one NAT Gateway per AZ (higher cost, no single point of failure - the standard production choice)."
  type        = bool
  default     = true
}

variable "tags" {
  description = "Common tags applied to every resource this module creates."
  type        = map(string)
  default     = {}
}
