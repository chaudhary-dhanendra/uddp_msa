# UDDP on Kubernetes

Kustomize manifests for the eight UDDP services. `base/` is what every environment shares; an overlay per environment
sets image tags, the addresses of the external dependencies and the database password.

```
deploy/k8s/
  base/                 namespace, shared ConfigMap, one Deployment + Service per service, storage, Ingress, policies
  overlays/local/       Docker Desktop Kubernetes / kind / minikube, with Kafka, PostgreSQL and observability on the host
```

## What the base defines

- **One Deployment and ClusterIP Service per service**: discovery (8761), gateway (8080), edge (8106), ingestion (8101),
  canonical (8102), identity (8103), registry (8104), statepack (8105). Each container runs as user 10001 with no
  capabilities, has a startup, liveness and readiness probe (`/actuator/health/{liveness,readiness}`), CPU and memory
  requests and a memory limit (identity 3Gi for Splink; ingestion 2Gi for Hop), and registers in Eureka at its pod IP
  (`UDDP_INSTANCE_ADDRESS` from `status.podIP`). Services find each other by name (`lb://<service>`).
- **The gateway is the only public entry**: the Ingress routes to `gateway-service`; the gateway routes only the UI's
  paths to the edge. Network policies let the other services accept traffic only from UDDP pods (and metric scrapes
  from the `monitoring` namespace).
- **External dependencies** in the `uddp-config` ConfigMap: PostgreSQL (`UDDP_POSTGRES_HOST/PORT`), Kafka
  (`UDDP_KAFKA_BOOTSTRAP_SERVERS`) and the OTLP trace endpoint. The databases, roles and schemas are created once with
  `deploy/postgres/*.sql`; the Kafka topics with `deploy/kafka/create-topics.sh <bootstrap>`.
- **Secrets**: only `uddp-database` (`UDDP_POSTGRES_PASSWORD`, the service roles' password). The base has no
  Secret; each overlay generates it from a gitignored `secrets.env` (copy `secrets.env.example`) or, in production,
  from the cluster's secret manager (External Secrets, Sealed Secrets). No credential is committed.
- **Storage**: claims for ingestion's landing folder and the Studio workspace in statepack-service (5Gi in the base).
- **Logs** go to stdout as JSON (ECS) for the cluster's collector; **metrics** at `/actuator/prometheus` (Prometheus
  can discover the pods through Eureka, as in `deploy/observability/prometheus.yml`); **traces** to the OTLP endpoint.

## Images

Build from the repository root after `mvn install -DskipTests`:

```
docker build -f deploy/docker/Dockerfile --build-arg SERVICE=discovery-service -t uddp-discovery-service:0.1.0-SNAPSHOT .
# same for gateway-service, edge-service, canonical-service, registry-service, statepack-service
docker build -f deploy/docker/Dockerfile.ingestion -t uddp-ingestion-service:0.1.0-SNAPSHOT .   # adds Apache Hop
docker build -f deploy/docker/Dockerfile.identity  -t uddp-identity-service:0.1.0-SNAPSHOT .    # adds Python + Splink
```

The images keep the repository layout under `/app` (platform data, seed State Packs, Hop templates, the matcher).

## Local cluster

1. Start Kafka, PostgreSQL and observability on the host (`docker compose up -d kafka kafka-init`,
   `docker compose --profile observability up -d`). Kafka advertises `host.docker.internal:29094` for the cluster.
2. Build the images (above); Docker Desktop's cluster uses the local images directly (`imagePullPolicy: IfNotPresent`).
3. `cp deploy/k8s/overlays/local/secrets.env.example deploy/k8s/overlays/local/secrets.env` and set the password.
4. `kubectl apply -k deploy/k8s/overlays/local`, then open http://uddp.localhost (needs an ingress controller) or
   `kubectl -n uddp port-forward svc/gateway-service 8080:8080`.

Check the rendering without a cluster: `kubectl kustomize deploy/k8s/overlays/local`.

## Production notes

Add an overlay per environment with the real dependency addresses, image registry and tags, a secret from the secret
manager, TLS on the Ingress, replicas and a PodDisruptionBudget per service, and two discovery replicas as Eureka
peers. Kafka, PostgreSQL and the observability stack are run as managed services or by their own operators.
