terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # State is kept locally (terraform.tfstate, git-ignored): fine for a personal, short-lived demo.
  # For a team: an S3 backend with state locking, e.g.
  #   backend "s3" { bucket = "...", key = "sport-streaming/terraform.tfstate", region = "us-west-2", use_lockfile = true }
}
