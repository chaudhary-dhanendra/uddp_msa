# Running the whole stack on one machine

Proven on 2026-11-05: States 1-3 onboarded through the gateway, 7 source records became 5 Golden Residents (Ram Singh
merged across three states), with metrics, one trace per call across the services and logs from all of them.

## Once

1. PostgreSQL with the service databases, roles and schemas: run `deploy/postgres/01-msa-databases.sql` and
   `deploy/postgres/02-msa-service-roles.sql` with psql as a superuser (or `docker compose --profile postgres up -d
   postgres`). Re-running them is safe; they also create `pgcrypto` and grant each role temporary tables in its own
   database.
2. Build: `mvn install -DskipTests`.

## Start with one command (Windows)

```powershell
cd D:\projects\uddp_msa
$env:UDDP_MSA_SERVICE_PASSWORD = '<service roles password>'   # or put it in deploy\local\msa.env
powershell -ExecutionPolicy Bypass -File scripts\start-all.ps1          # add -Build after a git pull
```

`scripts/start-all.ps1` starts Kafka (and its topics), then the eight services in the order below as hidden
background processes, waiting for each to report UP on `/actuator/health` and to register in Eureka before starting
the next, then checks that the gateway routes `/health` to the edge, then builds (only when `src` changed) and serves
the UI on http://localhost:5173. No windows are opened. Each process writes `logs/<name>.out.log` and
`logs/<name>.err.log`, and its JSON log to `logs/<name>.log`; if one fails, the script prints the last lines of its log
and stops. A service that is already healthy is left alone, so the script can simply be run again.

- Settings: from the window, else from `deploy/local/msa.env` (gitignored; copy `scripts/msa.env.example`).
  `UDDP_MSA_SERVICE_PASSWORD` is required (used as `UDDP_POSTGRES_PASSWORD`). `UDDP_INTERNAL_TOKEN` is generated for
  the run when unset. The edge-only variables below are set for the edge alone, and cleared for the others.
- Switches: `-Build` (mvn install first), `-Observability`, `-DemoUsers` (the edge seeds the demo users and prints
  their one-time passwords to `logs/edge-service.out.log`), `-SkipKafka`, `-SkipUi`, `-RebuildUi`, `-UiPort`, `-Open`.
- Stop: `scripts/stop-all.ps1` (UI, services, then `docker compose stop`; `-KeepKafka` leaves Kafka running). It also
  stops a service started by hand, found by its port.
- Fresh demo: `scripts/reset-databases.ps1` stops the services, drops `uddp_raw`, `uddp_identity`, `uddp_registry`
  and `uddp_platform` (no other database), runs the two `deploy/postgres` scripts again, removes Kafka's data volume
  (`-KeepKafka` keeps it) and clears the landing folder, pack cache and Studio workspace. It asks you to type RESET
  and for the postgres superuser password (or `PGPASSWORD`). Then `scripts/start-all.ps1 -DemoUsers`.

Why one process per service: each service is its own Spring Boot JVM with its own port and database role, and they
find each other through Eureka, so discovery-service must be up first and the gateway last. The services' default
paths (`../../platform`, `hop`, ...) are relative to the service folder, so a hand start must run `java -jar` from
`services/<name>`, not from the repository root; the script does that.

## Start by hand

1. `docker compose up -d kafka kafka-init` (Kafka and every topic, dead-letter topics included).
2. `docker compose --profile observability up -d` with `UDDP_LOG_DIR` set to an absolute folder (for example
   `<repo>/logs`, gitignored); the services write their JSON logs there.
3. discovery-service (8761), then the six services (ingestion 8101, canonical 8102, identity 8103, registry 8104,
   statepack 8105, edge 8106), then gateway-service (8080): `java -jar target/<artifact>-exec.jar` from each
   `services/<name>` folder, with
   - `UDDP_POSTGRES_HOST`, `UDDP_POSTGRES_PORT`, `UDDP_POSTGRES_PASSWORD` (the service roles' password),
     `UDDP_KAFKA_BOOTSTRAP_SERVERS=localhost:9092`, `UDDP_LOG_DIR`, `HOP_HOME` (ingestion);
   - `UDDP_INTERNAL_TOKEN` (the same random value for every service; docs/security.md);
   - for the edge only: `UDDP_POSTGRES_URL=jdbc:postgresql://<host>:<port>/uddp_platform?currentSchema=edge`,
     `UDDP_POSTGRES_USER=uddp_edge`, `UDDP_POSTGRES_DB=uddp_platform`. The edge reads `UDDP_POSTGRES_*` first, so a
     developer environment that still points them at the monolith's `uddp` database must be overridden.

Services register as `127.0.0.1:<port>` (`UDDP_INSTANCE_ADDRESS`; a deployment sets the host or pod address).

## Use

- The UI and every API call go to the gateway, http://localhost:8080. The first administrator is created once with
  `POST /api/setup/admin` (only while no user exists).
- Packs are published, approved and activated in statepack-service (the Studio, or its API for the seed packs in
  `services/statepack-service/seed/`); the edge installs every activated version.
- A first delivery of a source pauses before the Golden build (release checkpoint); a different user with
  ONBOARDING_APPROVE (DATA_STEWARD or PLATFORM_ADMIN) approves it at
  `/api/operations/sources/{state}/{source}/runs/{runId}/release/approve`, and the run completes.
- Grafana http://localhost:3000, Prometheus http://localhost:9090 (docs/observability.md).
