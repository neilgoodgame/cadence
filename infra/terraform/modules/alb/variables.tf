variable "environment" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "certificate_arn" {
  description = "ACM certificate for the HTTPS listener - see modules/acm."
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
