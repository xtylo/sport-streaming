variable "region" {
  description = "AWS region"
  type        = string
  default     = "us-west-2"
}

variable "cluster_name" {
  description = "Name of the EKS cluster (also used for the VPC and other resources)"
  type        = string
  default     = "sport-streaming"
}

variable "environment" {
  description = "Environment tag"
  type        = string
  default     = "demo"
}

variable "kubernetes_version" {
  description = "EKS Kubernetes version (1.36: standard support until Aug 2027)"
  type        = string
  default     = "1.36"
}

variable "api_allowed_cidrs" {
  description = "CIDRs allowed to reach the public EKS API endpoint. Set it to your public IP (x.x.x.x/32)."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "enable_msk" {
  description = "Create an Amazon MSK Serverless cluster (not used by the Day 8 demo; see msk.tf)"
  type        = bool
  default     = false
}
