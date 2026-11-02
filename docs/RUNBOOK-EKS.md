# Runbook — Day 8: deploy to EKS, test, destroy (≤ 60 min)

Goal: prove the service runs on AWS, take real numbers, and **tear everything down in the same session**.
Infrastructure: `src/infra` (VPC + EKS Auto Mode + ECR). Kubernetes: `src/k8s/eks` overlay.

> **Rule:** start the teardown (step 8) at **minute 40** at the latest, whatever state the demo is in.
> Expected cost of one session: roughly **$0.50–1.50** (EKS control plane, NAT gateway, NLB, a few EC2 nodes, EBS).

All commands run from the repo root inside the dev container.

## 0. Before starting (5 min, not billed)

```bash
docker desktop status                      # on Windows (PowerShell): must be "running"
aws sts get-caller-identity                # Arn must be ...:user/eduardo-admin (never :root)
cd src/infra
MYIP=$(curl -s https://checkip.amazonaws.com) && echo "$MYIP"
sed -i "s#\"[0-9.]*/32\"#\"${MYIP}/32\"#" terraform.tfvars && cat terraform.tfvars   # your current IP
terraform plan -out tfplan                 # review: "Plan: 57 to add, 0 to change, 0 to destroy"
cd ../..
kubectl kustomize src/k8s/eks > /dev/null && echo "overlay renders OK"
```

## 1. Apply the infrastructure (~15 min) — billing starts here

```bash
terraform -chdir=src/infra apply tfplan
```

While it runs (other terminal), build the image:

```bash
docker build --platform linux/amd64 -t streaming-service:dev src
```

## 2. Push the image to ECR (2 min)

```bash
ECR=$(terraform -chdir=src/infra output -raw ecr_repository_url)
aws ecr get-login-password --region us-west-2 | docker login --username AWS --password-stdin "${ECR%%/*}"
docker tag streaming-service:dev "${ECR}:v1"
docker push "${ECR}:v1"
```

## 3. Connect kubectl (1 min)

```bash
$(terraform -chdir=src/infra output -raw configure_kubectl)
kubectl get nodes          # may be empty: Auto Mode launches nodes when pods are pending
kubectl get storageclass
```

## 4. Deploy (5–8 min)

```bash
kubectl kustomize src/k8s/eks | sed "s#ECR_REPOSITORY_URL#${ECR}#g" | kubectl apply -f -
kubectl -n streaming get pods -w           # kafka-0, lgtm, 2× streaming-service → Running 1/1
kubectl get nodes -o wide                  # nodes launched by Auto Mode (instance types chosen from pod requests)
```

If a pod is stuck: `kubectl -n streaming describe pod <name>` (Events at the bottom).

## 5. Smoke test through the load balancer (3 min)

```bash
LB=$(kubectl -n streaming get svc streaming-service -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
echo "$LB"                                 # DNS can take 1–3 min to resolve after creation
curl -s -X POST "http://$LB/events" -H "Content-Type: application/json" \
  -d '{"matchId":"eks","type":"GOAL","minute":1,"team":"Tigres"}'
curl -s "http://$LB/stream/eks"
curl -N "http://$LB/stream/live?matchId=eks"   # Ctrl+C after seeing the snapshot / heartbeats
```

## 6. Load test from outside the cluster (4 min)

```bash
docker run --rm -i --network host --user "$(id -u)" -v "$PWD/loadtest:/loadtest" \
  -e PROFILE=baseline -e BASE_URL="http://$LB" grafana/k6:2.3.0 run /loadtest/k6.js
```

Latency now includes the internet round trip from the laptop to us-west-2 (tens of ms).

## 7. Evidence for the README (3 min)

```bash
kubectl -n streaming get pods,svc,hpa -o wide
kubectl get nodes -L node.kubernetes.io/instance-type,topology.kubernetes.io/zone
kubectl -n streaming port-forward svc/lgtm 3000:3000    # Grafana → import docs/grafana-dashboard.json
```

Screenshots: Grafana dashboard + a trace with `deployment.environment.name = "eks"`.

## 8. TEARDOWN — non-negotiable (10–15 min)

Delete the Kubernetes resources **first**: the NLB and the EBS volume are created by the cluster, not by
Terraform, and would block the VPC deletion (or keep billing) if left behind.

```bash
kubectl delete namespace streaming --wait=true      # removes the NLB, pods and the Kafka PVC/EBS volume
kubectl delete storageclass auto-ebs-sc
kubectl get svc -A | grep LoadBalancer || echo "no load balancers left"

terraform -chdir=src/infra destroy                  # ~10 min, confirm with "yes"
```

## 9. Verify nothing is left (2 min)

```bash
aws eks list-clusters --region us-west-2
aws ec2 describe-instances --region us-west-2 --filters Name=instance-state-name,Values=running \
  --query 'Reservations[].Instances[].InstanceId'
aws elbv2 describe-load-balancers --region us-west-2 --query 'LoadBalancers[].LoadBalancerName'
aws ec2 describe-nat-gateways --region us-west-2 --filter Name=state,Values=available --query 'NatGateways[].NatGatewayId'
aws ec2 describe-volumes --region us-west-2 --filters Name=status,Values=available --query 'Volumes[].VolumeId'
aws ec2 describe-addresses --region us-west-2 --query 'Addresses[].PublicIp'
```

All lists must be empty. The KMS key shows as **"Pending deletion"** (7–30 days): expected.

## If something goes wrong

| Symptom | Action |
| --- | --- |
| `kubectl` times out | Your IP changed: update `terraform.tfvars`, `terraform apply`, retry |
| Pod `ImagePullBackOff` | Check the image in `kubectl describe pod`; re-run step 2 |
| `kafka-0` Pending | `kubectl get pvc -n streaming`; the StorageClass must exist and be default |
| `kafka-0` CrashLoopBackOff, log says `lost+found` | Fixed in `src/k8s/base/kafka.yaml` (`KAFKA_LOG_DIRS=/var/lib/kafka/data/logs`); if you change the StatefulSet while it crash-loops, delete `kafka-0` so the new spec is applied |
| Pod keeps the old spec after `kubectl set env`/apply | StatefulSet rollouts wait for a Ready pod: `kubectl -n streaming delete pod kafka-0` |
| `destroy` stuck on subnets/VPC | A load balancer or ENI is still there: re-run step 8's `kubectl delete`, then `destroy` again |
| Running out of time | Skip steps 6–7 and go to step 8 |
