# VPC: nodes run in private subnets; load balancers go in public subnets.
#   10.0.0.0/20, 10.0.16.0/20   private (nodes, pods: VPC CNI gives pods VPC IPs, so keep them large)
#   10.0.48.0/24, 10.0.49.0/24  public  (load balancers, NAT gateway)
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 6.0"

  name = local.name
  cidr = local.vpc_cidr

  azs             = local.azs
  private_subnets = [for k, v in local.azs : cidrsubnet(local.vpc_cidr, 4, k)]
  public_subnets  = [for k, v in local.azs : cidrsubnet(local.vpc_cidr, 8, k + 48)]

  # Private nodes reach the internet (ECR, Docker Hub, AWS APIs) through NAT.
  # One NAT gateway instead of one per AZ: cheaper, not highly available (fine for a demo).
  enable_nat_gateway = true
  single_nat_gateway = true

  # Tags that tell EKS where to place public (internet-facing) and internal load balancers.
  public_subnet_tags = {
    "kubernetes.io/role/elb" = 1
  }
  private_subnet_tags = {
    "kubernetes.io/role/internal-elb" = 1
  }
}
