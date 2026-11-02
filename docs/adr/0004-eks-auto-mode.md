# ADR 0004 — EKS Auto Mode instead of a classic managed node group

- **Status:** Accepted
- **Date:** 2026-11-02

## Context

The cloud deployment must fit a one-hour session (apply, deploy, test, destroy). On kind (Day 5) the
HPA scaled pods 2 → 6 but all of them shared one node: scaling pods without scaling nodes adds no
capacity. A classic EKS setup needs, besides the cluster and a managed node group with a fixed
instance type, three add-ons with their own IAM: Cluster Autoscaler/Karpenter (node scaling), the EBS CSI
driver (Kafka's volume) and the AWS Load Balancer Controller (internet access).

## Decision

Use **EKS Auto Mode** (`compute_config.enabled = true`, built-in `general-purpose` node pool), via
`terraform-aws-modules/eks/aws ~> 21.0`, Kubernetes 1.36. Pods are sized from the Day 6 measurements
(500m CPU / 768 Mi request); Auto Mode chooses instance types and node count from those requests.
The Kubernetes manifests get a small overlay (`src/k8s/eks`): default gp3 StorageClass, NLB Service,
in-cluster LGTM, image from ECR, amd64 node selector.

## Consequences

- Node autoscaling, block storage and load balancers work without extra installs or IAM wiring:
  less to go wrong in a one-hour window.
- Node sizes follow the measured pod requests — the reason for sizing pods from data (Day 6).
- Extra management fee on top of EC2 (cents for a one-hour demo); less control over instance types
  than a hand-picked node group (custom NodePools are possible if needed).
- Less hands-on practice with classic node groups and controller installation; documented here as the alternative.
- Auto Mode provides no default StorageClass: the overlay must define one, or PVCs stay Pending.
- Resources created by the cluster (NLB, EBS volumes) are outside Terraform's state: the runbook deletes
  them with kubectl before `terraform destroy`.
