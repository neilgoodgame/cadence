variable "environment" {
  type = string
}

variable "domain_name" {
  description = "e.g. api.cadence.bioinform.co.uk"
  type        = string
}

variable "origin_domain_name" {
  description = "DNS name resolving to the backend EC2 instance's Elastic IP - CloudFront requires a domain name for its origin, not a raw IP."
  type        = string
}

variable "origin_port" {
  type    = number
  default = 8080
}

variable "certificate_arn" {
  description = "Must be an ACM certificate in us-east-1 - a CloudFront requirement regardless of this distribution's own region."
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
