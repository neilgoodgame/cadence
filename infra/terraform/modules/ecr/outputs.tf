output "repository_url" {
  description = "e.g. 423351912929.dkr.ecr.eu-west-2.amazonaws.com/cadence-backend-java - what `docker push`/the ECS task definition's image field point at."
  value       = aws_ecr_repository.this.repository_url
}

output "repository_arn" {
  value = aws_ecr_repository.this.arn
}
