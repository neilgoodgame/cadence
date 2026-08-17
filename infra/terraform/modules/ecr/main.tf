########################################################################
# One ECR repo per backend, NOT per environment - the same built image
# (tagged by git SHA) gets deployed to staging then production, rather
# than rebuilding per environment. Created from envs/staging's state for
# now since it's the only environment that exists; if/when a production
# environment is added, it should reference this repo via a data source
# rather than recreating it (ECR repository names must be unique per
# account/region).
########################################################################

resource "aws_ecr_repository" "this" {
  name                 = "cadence-${var.name}"
  image_tag_mutability = "IMMUTABLE" # a given tag (git SHA) always points at the same image - no silent overwrites.

  image_scanning_configuration {
    scan_on_push = true # Well-Architected: catch known CVEs in base images/dependencies before deploying.
  }

  tags = var.tags
}

# Keep the last 10 tagged images (covers a reasonable rollback window) and expire
# anything untagged after 1 day (build failures, superseded manifests) - unbounded
# image accumulation is a real, silent cost creep otherwise.
resource "aws_ecr_lifecycle_policy" "this" {
  repository = aws_ecr_repository.this.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep last 10 tagged images"
        selection = {
          tagStatus     = "tagged"
          tagPrefixList = ["v", "sha-"]
          countType     = "imageCountMoreThan"
          countNumber   = 10
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Expire untagged images after 1 day"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      }
    ]
  })
}
