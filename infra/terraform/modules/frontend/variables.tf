variable "environment" {
  type = string
}

variable "domain_name" {
  description = "e.g. cadence.bioinform.co.uk"
  type        = string
}

variable "certificate_arn" {
  description = "Must be an ACM certificate in us-east-1 - a CloudFront requirement regardless of this distribution's own region."
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
