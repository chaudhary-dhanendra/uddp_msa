# UDDP MVP — Unified Digital Data Platform

UDDP is a configuration-driven interoperability platform for onboarding government data sources without creating a new application or state-specific ETL pipeline for each state.

This repository is the microservices (MSA) version of UDDP ([docs/msa-design.md](docs/msa-design.md)): six Java 21 /
Spring Boot services that exchange Kafka events through an outbox and idempotent consumers, each with its own database
schema, sharing engine modules. Apache Hop, PostgreSQL, Python/Splink and the React UI keep their responsibilities.
The milestone history below was achieved in the modular monolith this code was imported from.

## Baseline status

Milestones M1–M11 are complete for the Resident-domain MVP. M8 establishes a reproducible build: Flyway owns the single schema path, PostgreSQL proof tests use Testcontainers, and the supported Windows and Linux/macOS e2e scripts run a disposable database through generic Hop ingestion, canonical processing, Splink matching, and Golden Resident construction.

M10 is complete only as recorded in [docs/milestones/M10_ACCEPTANCE.md](docs/milestones/M10_ACCEPTANCE.md):
persisted onboarding orchestration (State Pack lifecycle, eight stages with failure/resume, `/api/onboarding`)
and the State 4 portability proof (now `mvn test -pl e2e -Dmsa.e2e=true`). See
[docs/m10-onboarding.md](docs/m10-onboarding.md).

M11 adds the Golden record lifecycle (see [docs/golden-lifecycle.md](docs/golden-lifecycle.md) and
[docs/milestones/M11_ACCEPTANCE.md](docs/milestones/M11_ACCEPTANCE.md)): stable Golden IDs carried forward across
builds, versions with history (`GET /api/golden-residents/{id}/history`), merges and splits with pointers,
canonical versions per source record, and incremental matching and onboarding. Design decisions are in [docs/decisions/](docs/decisions/).

M14 adds the Family domain ([docs/family-domain.md](docs/family-domain.md),
[docs/milestones/M14_ACCEPTANCE.md](docs/milestones/M14_ACCEPTANCE.md)): households observed from each State Pack's
`Family:` mapping rules, stable Golden Families with versions and history, and `/api/v1/families`.

M13 adds stewardship and manual review ([docs/stewardship.md](docs/stewardship.md),
[docs/milestones/M13_ACCEPTANCE.md](docs/milestones/M13_ACCEPTANCE.md)): a POSSIBLE_MATCH review queue, accept/reject,
manual merge and split of Golden Residents, and an append-only decision log that every Golden build honours
(`/api/v1/stewardship`, `/api/stewardship`).

M15 adds the Scheme and Benefit domains ([docs/benefit-domain.md](docs/benefit-domain.md),
[docs/milestones/M15_ACCEPTANCE.md](docs/milestones/M15_ACCEPTANCE.md)): a platform scheme catalogue
(`platform/schemes/catalogue.yaml`) that State Packs crosswalk their scheme codes onto, benefits observed from each
pack's `Benefit:` mapping rules (PDS ration and old age pension in the sample), Resident 360 benefits across
departments and states (`/api/v1/golden-residents/{id}/benefits`), and duplicate-benefit and
eligible-but-not-enrolled reports (`/api/v1/reports/...`).

The shaping layer ([docs/decisions/0020-shaping-layer.md](docs/decisions/0020-shaping-layer.md), [docs/shaping-operations.md](docs/shaping-operations.md)) turns messy Excel/CSV rows into tidy records from State Pack `layout`/`parse` steps; unit and Studio tests prove it on synthetic workbooks built in the tests.

M9 is complete: configuration-driven data quality, generic persisted reference data,
batch DQ summaries, and platform-owned `resident-survivorship-v3` are implemented.
The normal demo remains neutral between PDS, Pension, and Beneficiaries; governed
`SOURCE_TRUST` is exercised only by an isolated integration fixture.

## Architectural promise

> Build the platform once; configure it for every state.

The portability proof will run State 1 PDS and State 2 Pension through the same Common Core, Common Data Model, Registry, and appropriate generic Hop ingestion template. Only the State Pack changes.

## Layout

- `services/` — the six services: `ingestion-service` (Hop, connectors, landing folders), `canonical-service`,
  `identity-service` (Splink matching and the Matching Studio), `registry-service` (Golden records),
  `statepack-service` (State Pack versions and the Onboarding Studio) and `edge-service` (the edge for the
  UI: security, read APIs, stewardship, onboarding orchestration); `gateway-service` (the single public entry, Spring Cloud
  Gateway, port 8080) and `discovery-service` (the Eureka registry, port 8761)
- `engine/`, `canonical-engine/`, `golden-engine/`, `matching-engine/`, `studio-engine/` — engine modules the
  services share; `contracts/` (event schemas) and `messaging/` (outbox, inbox, Kafka loop)
- `e2e/` — the cross-service test (`mvn test -pl e2e -Dmsa.e2e=true`)
- `state-packs/`, `m10-state-packs/` — state configuration; `sample-data/` — the sample source files
- `platform/` — mapping targets, scheme catalogue, reference data; `common-model/` — JSON Schema contracts
- `hop/`, `metadata/` — the generic Hop pipelines and Hop metadata; `matching/` — the Python Splink adapter
- `web/onboarding-ui/` — the React UI; `scripts/` — database roles and Kafka topics; `docs/` — design and ADRs

Read [ARCHITECTURE.md](ARCHITECTURE.md), [MVP_SCOPE.md](MVP_SCOPE.md), and [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md) before implementation.

For local setup and the supported reproducibility commands, see [docs/dev-setup.md](docs/dev-setup.md).

