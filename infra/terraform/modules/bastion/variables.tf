variable "environment" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_id" {
  description = "A single subnet is enough - this is one instance, not a fleet. A public one, with NAT off - the SSM agent needs outbound internet to register/connect, same reason ECS moved to a public subnet too. Still unreachable from the internet: this module's security group has zero ingress rules, SSM's connection model is entirely outbound-initiated."
  type        = string
}

variable "instance_type" {
  type    = string
  default = "t4g.micro" # ARM/Graviton - cheaper than t3.micro for the same spec, and this is idle almost all the time anyway.
}

variable "tags" {
  type    = map(string)
  default = {}
}
