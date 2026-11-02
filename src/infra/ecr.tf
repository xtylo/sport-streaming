# Private container registry for the streaming-service image (Azure equivalent: ACR).
resource "aws_ecr_repository" "app" {
  name                 = "streaming-service"
  image_tag_mutability = "MUTABLE" # demo uses a moving tag; production: IMMUTABLE + unique tags

  # Allow "terraform destroy" even when images are still in the repository.
  force_delete = true

  image_scanning_configuration {
    scan_on_push = true
  }
}

# Keep storage (and cost) bounded: only the 5 most recent images.
resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 5 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 5
      }
      action = { type = "expire" }
    }]
  })
}
