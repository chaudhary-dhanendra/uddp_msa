# Observability

Metrics, traces and logs for every UDDP service, viewed together in Grafana. Everything is open source and runs from
`docker-compose.yml` under the `observability` profile; it is off unless started.

```
docker compose --profile observability up -d
```

| What | Where | Tool |
|------|-------|------|
| Dashboards, alerts, log and trace search | http://localhost:3000 (folder **UDDP**) | Grafana 12 |
| Metrics and alert rules | http://localhost:9090 | Prometheus 3 |
| Traces | Grafana > Explore > Tempo | Tempo 2.8 (OTLP on 4318) |
| Logs | Grafana > Explore > Loki | Loki 3.5, fed by Grafana Alloy |

## How the services take part

Every service (the six pipeline services, the gateway and the registry) has, from its `application.yml`:

- **Metrics** at `/actuator/prometheus` (Micrometer): HTTP server and client, JVM, CPU, database pool, Kafka clients,
  each tagged `application=<service name>`. Prometheus finds the services through the Eureka registry, so a new
  service or an extra instance is scraped without configuration. The edge allows `/actuator/health` and
  `/actuator/prometheus` without sign-in; the gateway never routes `/actuator`.
- **Traces** over OTLP to Tempo (Micrometer Tracing with OpenTelemetry), sampled at `UDDP_TRACING_SAMPLING` (1.0 in
  development). HTTP calls carry the trace automatically (gateway, edge, the services' direct calls). Kafka events
  carry it too: the outbox stores the trace an event is written in, the relay copies it into a `traceparent` header,
  and the consuming loop handles the event in a span that continues it (`messaging.TraceContext`), so one onboarding
  run is one trace across ingestion, canonical, identity and registry.
- **Logs** as Spring Boot's structured JSON (ECS) in `<UDDP_LOG_DIR>/<service>.log`, with the trace id on each line
  written inside a trace. Alloy tails that folder into Loki, labelled by service and level; a log line opens its trace
  in Tempo, and a trace shows its logs.

## Settings

| Variable | Default | Meaning |
|----------|---------|---------|
| `UDDP_LOG_DIR` | services: `target/logs`; compose: `./logs` | Folder the services write JSON logs to and Alloy reads. Set it to the same absolute folder for both. |
| `UDDP_OTLP_TRACING_URL` | `http://127.0.0.1:4318/v1/traces` | Where the services send traces. |
| `UDDP_TRACING_ENABLED` | `true` | Turns trace export off. |
| `UDDP_TRACING_SAMPLING` | `1.0` | Share of requests traced (use 0.1 or less in production). |
| `UDDP_GRAFANA_PORT`, `UDDP_PROMETHEUS_PORT`, `UDDP_OTLP_PORT`, `UDDP_LOKI_PORT`, `UDDP_TEMPO_PORT` | 3000, 9090, 4318, 3100, 3200 | Host ports, for machines where another stack already uses them. |

Prometheus reads the registry at `host.docker.internal:8761`; with another `UDDP_DISCOVERY_PORT`, change it in
`deploy/observability/prometheus.yml`. Tests never export traces or register (the build sets
`management.tracing.enabled=false` and `eureka.client.enabled=false`).

## Dashboards and alerts

- **UDDP services** (`deploy/observability/grafana/dashboards/uddp-services.json`): services up, requests per second,
  server errors, 95th percentile request time, heap, database connections, CPU, and recent warnings and errors.
- **Alert rules** (`deploy/observability/alerts.yml`): a service not answering for a minute, more than 5% server
  errors, 95th percentile above 2 seconds, heap above 90%. Production routes them with an Alertmanager.

Grafana lets anyone on the machine view; the admin account is Grafana's own (it asks for a new password on first
sign-in). All ports bind to localhost only.

## Not yet

Kafka consumer lag and outbox backlog as metrics (a Kafka exporter and an outbox gauge), dashboards per pipeline
stage, and Alertmanager routing.
