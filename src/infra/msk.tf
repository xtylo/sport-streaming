# OPTIONAL: Amazon MSK Serverless instead of the in-cluster Kafka. Disabled by default (enable_msk = false).
#
# Not used in the Day 8 demo because:
#   - provisioning takes far longer than an in-cluster broker and it is billed per cluster-hour,
#   - MSK Serverless only supports IAM authentication (SASL/IAM on port 9098): the app would need the
#     aws-msk-iam-auth library, SASL settings and an IAM role for the pods (EKS Pod Identity).
# Kept here to document the production path and to be validated by "terraform validate".

resource "aws_security_group" "msk" {
  count = var.enable_msk ? 1 : 0

  name        = "${local.name}-msk"
  description = "Kafka clients inside the VPC to MSK Serverless (IAM auth)"
  vpc_id      = module.vpc.vpc_id

  ingress {
    description = "Kafka SASL/IAM from the VPC"
    from_port   = 9098
    to_port     = 9098
    protocol    = "tcp"
    cidr_blocks = [local.vpc_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_msk_serverless_cluster" "this" {
  count = var.enable_msk ? 1 : 0

  cluster_name = "${local.name}-msk"

  vpc_config {
    subnet_ids         = module.vpc.private_subnets
    security_group_ids = [aws_security_group.msk[0].id]
  }

  client_authentication {
    sasl {
      iam {
        enabled = true
      }
    }
  }
}
