# Platform security (MSA)

How the services protect their APIs and secrets. User-facing security (sign-in, roles, permissions, state scope,
audit, PII masking, maker-checker) is the edge's and is described in the ADRs (0017 and later).

## Who can call what

| Caller | Reaches | Checked by |
|--------|---------|------------|
| Browser / API client | the gateway only (port 8080, the Ingress in Kubernetes) | the gateway routes only the UI's paths (`/api/**`, sign-in, API docs) to the edge; the edge requires a signed-in user with the endpoint's permission |
| The edge | the pipeline services' internal APIs (ingestion, identity, statepack) | the service token |
| A pipeline service | another service's internal API (canonical and identity read State Packs from statepack-service) | the service token |
| Prometheus, probes | `/actuator/health/**`, `/actuator/prometheus` | open, never routed by the gateway; network policy limits who reaches them in Kubernetes |

- **Service token** (`UDDP_INTERNAL_TOKEN`): every UDDP process sends it on its calls to other services
  (`X-UDDP-Service-Token`, `contracts.ServiceUrls.request`); ingestion, canonical, identity, registry and statepack
  answer `/api/*` only when it is present and right (constant-time comparison, 401 otherwise;
  `messaging.ServiceTokenAutoConfiguration`). The gateway drops the header (and `X-UDDP-Actor`) from outside requests,
  so nobody can pose as the edge or a service. Without a token nothing is checked: acceptable only on one machine,
  where the services listen on 127.0.0.1. The e2e test runs with the token required.
- **The user's name between services** (`X-UDDP-Actor`) is trusted only because the caller holds the service token;
  permissions and state scope were already checked at the edge.

## Secrets

- **Database passwords** come only from the environment (`UDDP_POSTGRES_PASSWORD`, or a service's own
  `UDDP_<SERVICE>_POSTGRES_PASSWORD`); in Kubernetes from the `uddp-database` Secret, generated per overlay from a
  gitignored `secrets.env` or provided by the cluster's secret manager. No password is in a file in the repository.
  The edge can keep a setup-mode connection encrypted under `~/.uddp`, but environment settings always win.
- **Source credentials** (database-table sources and connectors) live only in ingestion-service: database sources in
  `uddp.ingestion.connections.<name>.*` with passwords from its environment, connectors AES-GCM encrypted in its own
  schema. The Studio hosted by statepack-service lists those connections and previews rows through ingestion-service
  (`/api/ingestion/connections`, `/api/ingestion/sources/rows`) and refuses to store a password. Commands and events
  name a connection, never its credentials.
- Logs and error messages redact passwords (`SecretRedactor`), and child processes (Hop, Python) receive database
  settings through a deleted argument file or their environment, never on a command line.

## Local development accounts

The first administrator of a local edge is created through `POST /api/setup/admin`; the local stack check keeps its
generated passwords in `deploy/local/` (gitignored).
