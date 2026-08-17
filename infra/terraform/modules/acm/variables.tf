variable "domain_name" {
  type = string
}

variable "zone_id" {
  description = "Route 53 hosted zone to write the DNS validation record(s) into."
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
