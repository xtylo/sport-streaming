output "region" {
  description = "AWS region"
  value       = var.region
}

output "cluster_name" {
  description = "EKS cluster name"
  value       = module.eks.cluster_name
}

output "configure_kubectl" {
  description = "Command to point kubectl at the new cluster"
  value       = "aws eks update-kubeconfig --region ${var.region} --name ${module.eks.cluster_name}"
}

output "ecr_repository_url" {
  description = "Push the streaming-service image here"
  value       = aws_ecr_repository.app.repository_url
}

output "vpc_id" {
  description = "VPC ID"
  value       = module.vpc.vpc_id
}

output "msk_bootstrap_brokers" {
  description = "MSK Serverless bootstrap brokers (SASL/IAM), when enable_msk = true"
  value       = try(aws_msk_serverless_cluster.this[0].bootstrap_brokers_sasl_iam, null)
}
