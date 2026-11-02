# Infrastructure as code for the sport-streaming demo on AWS:
#   network.tf  VPC (2 AZs, public + private subnets, single NAT gateway)
#   eks.tf      EKS cluster in Auto Mode (managed compute, storage and load balancing)
#   ecr.tf      Container registry for the streaming-service image
#   msk.tf      Optional Amazon MSK Serverless (disabled by default)
#
# Lifecycle: plan on Day 7; apply, test and DESTROY in the same session on Day 8.

provider "aws" {
  region = var.region

  # Every resource gets these tags: easy to find (and to account for) in the console and the bill.
  default_tags {
    tags = local.tags
  }
}

data "aws_availability_zones" "available" {
  # Only regular AZs (no Local Zones / Wavelength)
  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

locals {
  name     = var.cluster_name
  vpc_cidr = "10.0.0.0/16"

  # EKS needs subnets in at least 2 AZs; 2 keeps the demo small.
  azs = slice(data.aws_availability_zones.available.names, 0, 2)

  tags = {
    Project     = "sport-streaming"
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}
