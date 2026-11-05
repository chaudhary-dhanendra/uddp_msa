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
| Alert routing, silences | http://localhost:9093 (also Grafana > Alerting) | Alertmanager 0.28 |
| Kafka consumer lag, topic offsets | scraped by Prometheus (http://localhost:9308/metrics) | kafka-exporter 1.9 |
| Traces | Grafana > Explore > Tempo | Tempo 2.8 (OTLP on 4318) |
| Logs | Grafana > Explore > Loki | Loki 3.5, fed by Grafana Alloy |

## How the services take part

Every service (the six pipeline services, the gateway and the registry) has, from its `application.yml`:

- **Metrics** at `/actuator/prometheus` (Micrometer): HTTP server and client, JVM, CPU, database pool, Kafka clients,
  each tagged `application=<service name>`. Prometheus finds the services through the Eureka registry, so a new
  service or an extra instance is scraped without configuration. The edge allows `/actuator/health` and
  `/actuator/prometheus` without sign-in; the gateway never routes `/actuator`.
- **Event flow**: each service's outbox relay (`messaging.OutboxRelay`) reports `uddp_outbox_backlog_events` (rows not
  yet published), `uddp_outbox_oldest_unpublished_age_seconds`, `uddp_outbox_published_events_total` and
  `uddp_outbox_relay_failures_total`; the backlog is measured after a drain, at most every 5 seconds. The relay
  registers on Micrometer's global registry, which Spring Boot joins, so no service code is needed. Consumer lag is
  read from the broker by kafka-exporter (`kafka_consumergroup_lag_sum{consumergroup,topic}`), so a consumer that is
  down still shows its lag; dead-lettered events show as offsets of the `<topic>.dlt.<consumer>` topics.
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
| `UDDP_GRAFANA_PORT`, `UDDP_PROMETHEUS_PORT`, `UDDP_OTLP_PORT`, `UDDP_LOKI_PORT`, `UDDP_TEMPO_PORT`, `UDDP_ALERTMANAGER_PORT`, `UDDP_KAFKA_EXPORTER_PORT` | 3000, 9090, 4318, 3100, 3200, 9093, 9308 | Host ports, for machines where another stack already uses them. |

Prometheus reads the registry at `host.docker.internal:8761`; with another `UDDP_DISCOVERY_PORT`, change it in
`deploy/observability/prometheus.yml`. Tests never export traces or register (the build sets
`management.tracing.enabled=false` and `eureka.client.enabled=false`).

## Dashboards and alerts

- **UDDP services** (`deploy/observability/grafana/dashboards/uddp-services.json`): services up, requests per second,
  server errors, 95th percentile request time, heap, database connections, CPU, an **Event flow** row (consumer lag
  per group, outbox backlog and oldest unpublished event per service, events published and dead-lettered per second)
  and recent warnings and errors.
- **Alert rules** (`deploy/observability/alerts.yml`):

| Alert | Fires when | Severity |
|-------|------------|----------|
| ServiceDown | a service does not answer its metrics endpoint for 1 minute | critical |
| HighServerErrorRate | more than 5% of a service's requests fail with 5xx for 5 minutes | warning |
| SlowRequests | 95th percentile request time above 2 seconds for 10 minutes | warning |
| HeapAlmostFull | heap above 90% for 10 minutes | warning |
| KafkaConsumerLagHigh | a consumer group is more than 1000 events behind for 10 minutes | warning |
| KafkaConsumerStalled | a group has lag but committed no offset for 10 minutes (held 5 minutes) | critical |
| EventsDeadLettered | any event was parked on a `.dlt.` topic in the last 10 minutes | warning |
| OutboxBacklogHigh | more than 1000 unpublished outbox events for 10 minutes | warning |
| OutboxStuck | the oldest unpublished outbox event is older than 5 minutes (held 5 minutes) | critical |
| OutboxRelayFailing | the relay failed to publish in each of the last 5 minutes | warning |

Thresholds suit the development data volumes; tune them per environment in `alerts.yml`.

- **Alertmanager** (`deploy/observability/alertmanager.yml`): groups by alert, service and consumer group, repeats
  warnings every 4 hours and critical alerts every hour, and inhibits the follow-on alerts of a down service, a stuck
  outbox or a stalled consumer. Both receivers (`uddp-default`, `uddp-critical`) notify no one out of the box: add
  `email_configs` or `webhook_configs` there, with secrets read from files (`url_file`, `auth_password_file`), never
  written into the file. Grafana shows its alerts and silences through the Alertmanager data source.

Grafana lets anyone on the machine view; the admin account is Grafana's own (it asks for a new password on first
sign-in). All ports bind to localhost only.

## Not yet

Dashboards per pipeline stage and real notification receivers (each deployment adds its own).
