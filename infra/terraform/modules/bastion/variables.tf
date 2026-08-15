variable "environment" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_id" {
  description = "A single private subnet is enough - this is one instance, not a fleet."
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
