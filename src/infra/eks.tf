# EKS cluster in Auto Mode.
#
# Auto Mode manages the data plane: it launches and right-sizes EC2 nodes from the pods' resource
# requests (Karpenter under the hood), and includes the EBS CSI driver (Kafka's volume) and load
# balancer provisioning (Service type LoadBalancer / Ingress). This is the node autoscaling the
# Day 5 HPA test showed was missing: the HPA adds pods, Auto Mode adds nodes for them.
#
# Pod sizing that drives node choice (src/k8s/base/deployment.yaml): 500m CPU / 768Mi per app pod.
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 21.0"

  name               = local.name
  kubernetes_version = var.kubernetes_version

  # kubectl from the dev container reaches the API over the internet; restrict it to your IP.
  endpoint_public_access       = true
  endpoint_public_access_cidrs = var.api_allowed_cidrs

  # The IAM identity running Terraform becomes cluster admin (kubectl works right after apply).
  enable_cluster_creator_admin_permissions = true

  compute_config = {
    enabled    = true
    node_pools = ["general-purpose"]
  }

  # Not part of Auto Mode; the HPA needs it (kubectl top / CPU metrics).
  addons = {
    metrics-server = {}
  }

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets
}
