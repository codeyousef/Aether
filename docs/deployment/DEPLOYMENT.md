# Aether Framework - Production Deployment Guide

This guide covers deploying Aether applications in production environments using Docker, Kubernetes, and major cloud providers.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Docker Deployment](#docker-deployment)
3. [Kubernetes Deployment](#kubernetes-deployment)
4. [Cloud Provider Guides](#cloud-provider-guides)
5. [Environment Variables](#environment-variables)
6. [Security Best Practices](#security-best-practices)
7. [Monitoring & Observability](#monitoring--observability)
8. [Scaling Guidelines](#scaling-guidelines)

---

## Prerequisites

### System Requirements

- **JVM Runtime**: Eclipse Temurin 21 or later
- **Memory**: Minimum 512MB RAM per instance (1GB recommended)
- **CPU**: 0.5 vCPU minimum (1 vCPU recommended)
- **Disk**: 500MB for application + database storage

### Build Requirements

- Kotlin 2.1.0+
- Gradle 8.5+
- Docker 24.0+ (for containerized deployments)
- kubectl 1.28+ (for Kubernetes deployments)

---

## Docker Deployment

### Quick Start

```bash
# Clone and build
git clone https://github.com/your-org/aether-app.git
cd aether-app

# Start all services (PostgreSQL, Redis, Aether)
docker compose -f docs/deployment/docker-compose.yml up -d

# Check status
docker compose -f docs/deployment/docker-compose.yml ps

# View logs
docker compose -f docs/deployment/docker-compose.yml logs -f aether-app
```

### Building the Docker Image

```bash
# Build with multi-stage Dockerfile
docker build -t aether-app:latest -f docs/deployment/Dockerfile .

# Tag for registry
docker tag aether-app:latest your-registry.com/aether-app:v1.0.0

# Push to registry
docker push your-registry.com/aether-app:v1.0.0
```

### Configuration

Create a `.env` file in the deployment directory:

```env
# Database
POSTGRES_USER=aether
POSTGRES_PASSWORD=your_secure_password
POSTGRES_DB=aether_db

# Redis
REDIS_PASSWORD=your_redis_password

# Application
JWT_SECRET=your_jwt_secret_min_32_characters_long
CSRF_SECRET=your_csrf_secret_min_32_characters
SESSION_SECRET=your_session_secret_min_32_chars
LOG_LEVEL=INFO

# Ports
APP_PORT=8080
POSTGRES_PORT=5432
REDIS_PORT=6379
```

### Production Mode with Nginx

```bash
# Start with nginx reverse proxy
docker compose -f docs/deployment/docker-compose.yml --profile production up -d
```

---

## Kubernetes Deployment

### Prerequisites

1. A Kubernetes cluster (1.28+)
2. `kubectl` configured for your cluster
3. Ingress controller (nginx-ingress recommended)
4. cert-manager (for TLS)

### Deployment Steps

```bash
# Apply all manifests
kubectl apply -f docs/deployment/kubernetes/

# Or apply individually
kubectl apply -f docs/deployment/kubernetes/postgres-deployment.yaml
kubectl apply -f docs/deployment/kubernetes/redis-deployment.yaml
kubectl apply -f docs/deployment/kubernetes/aether-deployment.yaml

# Check deployment status
kubectl -n aether get pods
kubectl -n aether get services
kubectl -n aether get ingress
```

### Secrets Management

**Important**: Replace placeholder secrets before deploying!

```bash
# Create secrets from literal values
kubectl -n aether create secret generic aether-secrets \
  --from-literal=DATABASE_URL='jdbc:postgresql://postgres-service:5432/aether_db' \
  --from-literal=DATABASE_USER='aether' \
  --from-literal=DATABASE_PASSWORD='your_secure_password' \
  --from-literal=JWT_SECRET='your_32_char_jwt_secret_here' \
  --from-literal=CSRF_SECRET='your_32_char_csrf_secret_here' \
  --from-literal=SESSION_SECRET='your_32_char_session_secret'

# Or use external secrets manager (recommended)
# - AWS Secrets Manager with External Secrets Operator
# - HashiCorp Vault
# - Azure Key Vault
```

### Scaling

The deployment includes HorizontalPodAutoscaler for automatic scaling:

```bash
# Manual scaling
kubectl -n aether scale deployment aether-app --replicas=5

# View HPA status
kubectl -n aether get hpa aether-hpa
```

---

## Cloud Provider Guides

### AWS (EKS)

```bash
# Create EKS cluster
eksctl create cluster \
  --name aether-cluster \
  --region us-east-1 \
  --node-type t3.medium \
  --nodes 3

# Install ALB Ingress Controller
kubectl apply -k "github.com/aws/eks-charts/stable/aws-load-balancer-controller//crds?ref=master"

# Deploy application
kubectl apply -f docs/deployment/kubernetes/

# Use RDS for PostgreSQL (recommended)
# Use ElastiCache for Redis (recommended)
```

### Google Cloud (GKE)

```bash
# Create GKE cluster
gcloud container clusters create aether-cluster \
  --zone us-central1-a \
  --machine-type e2-medium \
  --num-nodes 3 \
  --enable-autoscaling \
  --min-nodes 2 \
  --max-nodes 10

# Get credentials
gcloud container clusters get-credentials aether-cluster --zone us-central1-a

# Deploy application
kubectl apply -f docs/deployment/kubernetes/

# Use Cloud SQL for PostgreSQL (recommended)
# Use Memorystore for Redis (recommended)
```

### Azure (AKS)

```bash
# Create resource group
az group create --name aether-rg --location eastus

# Create AKS cluster
az aks create \
  --resource-group aether-rg \
  --name aether-cluster \
  --node-count 3 \
  --node-vm-size Standard_DS2_v2 \
  --enable-cluster-autoscaler \
  --min-count 2 \
  --max-count 10

# Get credentials
az aks get-credentials --resource-group aether-rg --name aether-cluster

# Deploy application
kubectl apply -f docs/deployment/kubernetes/

# Use Azure Database for PostgreSQL (recommended)
# Use Azure Cache for Redis (recommended)
```

### DigitalOcean (DOKS)

```bash
# Create Kubernetes cluster
doctl kubernetes cluster create aether-cluster \
  --region nyc1 \
  --size s-2vcpu-4gb \
  --count 3

# Get credentials
doctl kubernetes cluster kubeconfig save aether-cluster

# Deploy application
kubectl apply -f docs/deployment/kubernetes/

# Use Managed PostgreSQL (recommended)
# Use Managed Redis (recommended)
```

---

## Environment Variables

### Required Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `DATABASE_URL` | JDBC connection string | `jdbc:postgresql://host:5432/db` |
| `DATABASE_USER` | Database username | `aether` |
| `DATABASE_PASSWORD` | Database password | (secure value) |
| `JWT_SECRET` | JWT signing key (32+ chars) | (secure value) |
| `CSRF_SECRET` | CSRF token key (32+ chars) | (secure value) |
| `SESSION_SECRET` | Session encryption key | (secure value) |

### Optional Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SERVER_HOST` | Bind address | `0.0.0.0` |
| `SERVER_PORT` | HTTP port | `8080` |
| `LOG_LEVEL` | Logging level | `INFO` |
| `DATABASE_POOL_SIZE` | Connection pool size | `10` |
| `REDIS_HOST` | Redis hostname | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis password | (none) |

### JVM Options

```bash
JAVA_OPTS="-XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -Djava.security.egd=file:/dev/./urandom"
```

---

## Security Best Practices

### 1. Secrets Management

- **Never** commit secrets to version control
- Use external secrets managers (Vault, AWS Secrets Manager, etc.)
- Rotate secrets regularly
- Use different secrets per environment

### 2. Network Security

- Enable TLS for all external traffic
- Use network policies to restrict pod communication
- Place database behind private subnet
- Use VPN or bastion for administrative access

### 3. Application Security

- Enable CSRF protection for all forms
- Use secure session cookies (`HttpOnly`, `Secure`, `SameSite`)
- Implement rate limiting
- Validate all user input
- Use parameterized queries (Aether ORM does this automatically)

### 4. Container Security

- Run as non-root user
- Use read-only root filesystem
- Drop all capabilities
- Scan images for vulnerabilities
- Use minimal base images (Alpine)

### 5. Kubernetes Security

- Enable RBAC
- Use PodSecurityPolicies/Standards
- Enable audit logging
- Regularly update cluster components

---

## Monitoring & Observability

### Health Endpoints

Aether exposes these endpoints by default:

```
GET /health      - Liveness check
GET /health/live - Liveness probe
GET /health/ready - Readiness probe
GET /metrics     - Prometheus metrics (if enabled)
```

### Prometheus Integration

Add to your Prometheus configuration:

```yaml
scrape_configs:
  - job_name: 'aether'
    kubernetes_sd_configs:
      - role: pod
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: true
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_port]
        action: replace
        target_label: __address__
        regex: (.+)
        replacement: $1:8080
```

### Grafana Dashboard

Import the Aether dashboard (ID: TBD) or create custom dashboards monitoring:

- Request rate & latency
- Error rates
- JVM metrics (heap, GC, threads)
- Database connection pool
- Session count

### Logging

Aether uses structured JSON logging. Configure log aggregation with:

- **ELK Stack** (Elasticsearch, Logstash, Kibana)
- **Loki + Grafana**
- **Cloud-native** (CloudWatch, Stackdriver, Azure Monitor)

---

## Scaling Guidelines

### Horizontal Scaling

| Metric | Scale Up | Scale Down |
|--------|----------|------------|
| CPU | > 70% | < 30% |
| Memory | > 80% | < 40% |
| Request Latency | > 500ms | < 100ms |

### Vertical Scaling

| Load | Memory | CPU | Replicas |
|------|--------|-----|----------|
| Low (< 100 RPS) | 512MB | 0.5 | 2 |
| Medium (100-1000 RPS) | 1GB | 1 | 3-5 |
| High (1000-10000 RPS) | 2GB | 2 | 5-20 |
| Very High (> 10000 RPS) | 4GB | 4 | 20+ |

### Database Scaling

- Use connection pooling (HikariCP built-in)
- Consider read replicas for read-heavy workloads
- Use caching (Redis) for frequently accessed data
- Implement query optimization and indexing

---

## Troubleshooting

### Common Issues

**Application won't start:**
```bash
# Check logs
kubectl -n aether logs -l app.kubernetes.io/name=aether --tail=100

# Check events
kubectl -n aether get events --sort-by='.lastTimestamp'
```

**Database connection issues:**
```bash
# Test connectivity
kubectl -n aether exec -it deploy/aether-app -- sh -c "nc -zv postgres-service 5432"

# Check credentials
kubectl -n aether get secret aether-secrets -o jsonpath='{.data.DATABASE_URL}' | base64 -d
```

**High memory usage:**
```bash
# Get heap dump
kubectl -n aether exec -it deploy/aether-app -- jcmd 1 GC.heap_dump /tmp/heap.hprof

# Copy heap dump
kubectl -n aether cp aether-app-xxx:/tmp/heap.hprof ./heap.hprof
```

---

## Support

For issues and questions:
- GitHub Issues: https://github.com/your-org/aether/issues
- Documentation: https://aether.dev/docs
- Community: https://discord.gg/aether
