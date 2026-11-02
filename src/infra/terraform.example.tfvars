# Copy to terraform.tfvars (git-ignored) and adjust.
#   cp terraform.example.tfvars terraform.tfvars
#
# Your public IP:  curl -s https://checkip.amazonaws.com
api_allowed_cidrs = ["203.0.113.10/32"]

# region             = "us-west-2"
# kubernetes_version = "1.36"
# enable_msk         = false
