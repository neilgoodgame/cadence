variable "name" {
  description = "Repository name suffix, e.g. \"backend-java\"."
  type        = string
}

variable "tags" {
  type    = map(string)
  default = {}
}
