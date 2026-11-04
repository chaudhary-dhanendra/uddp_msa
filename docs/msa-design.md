# UDDP microservices design (Phase 0)

Status: draft for review. No code has changed. The modular monolith in `D:\projects\uddp-mvp` is the read-only
reference; all MSA work happens in `D:\projects\uddp_msa`.

The core promise does not change: **build the platform once; configure it for every state.** Every service is
generic. State behaviour comes only from State Pack data, which every service fetches by `packId` + `version` +
`digest` from `statepack-service`. `stateCode` travels on events as data for routing, filtering and audit, never
as a branch condition.

## 1. Starting point (what the monolith really is)

The monolith is larger than the original M1–M11 description. `common-core` holds about 300 main classes in 30
packages and 187 test classes. There are 75 Flyway migrations (V1–V111) in one `public` schema with about 120
tables and dense foreign keys between stages (`raw_record` → `canonical_resident` → `resident_match_pair` →
`golden_resident_member`). Python Splink (`matching/run.py`) reads `canonical_resident_current` and
`golden_resident_membership` directly and writes `matching_run`, `resident_match_pair` and
`resident_match_cluster_member`. Hop writes `raw_record` over JDBC.

Existing seams we will put the new transport behind:

| Interface (monolith)                       | Becomes                                                         |
|--------------------------------------------|-----------------------------------------------------------------|
| `onboarding.HopRunner`                     | command to ingestion-service                                    |
| `canonical.RawRecordRepository`            | consumer of `raw-record-received` (canonical keeps its own copy of nothing; it receives payloads) |
| `matching.MatchingService` + `PythonMatchingRunner` | identity-service, triggered by `canonical.batch-processed` |
| `read.statepack.StatePackQueryService`, `statepack.StatePackLoader` | statepack-service HTTP API + `statepack-client` library |
| `golden.GoldenResidentService` (build)     | registry-service, triggered by `identity.match-run-completed`   |
| `golden.StewardshipConstraints`            | registry's local projection of `stewardship.decision-recorded`  |
| `inbox.ExceptionSource`, `gates.MetricsSource`, `monitoring.PlatformMetricsSource` | per-service read APIs aggregated by api-stewardship |

Seams that are missing today and must be added in Phase 2: an `EventPublisher` (outbox) port, a golden-build
trigger port, and a canonical-read port used by matching and survivorship instead of SQL against
`canonical_resident`.

## 2. Service boundaries

| Service | Owns (responsibility) | Database / schema | Main tables moved from the monolith |
|---|---|---|---|
| **ingestion-service** | Runs the generic Hop templates (`generic-file/db/excel-ingest.hpl`), writes Raw/Landing append-only, source connectors, schedules, delivery change detection, re-publishes Raw for replay. No semantics. | `uddp_raw` / `ingestion` | `ingestion_batch`, `raw_record`, `raw_source_line`, `source_connector`, `source_schedule`, `schedule_trigger`, `delivery_change_set`, `source_delivery_policy`, `source_record_state` |
| **canonical-service** | State Pack driven shaping (ADR-0020), mapping engines (Resident, Family, Benefit, Application, Asset), conversion, crosswalks, validation, DQ rules, held records, identifier protection (vault), reference data incl. LGD geography. Canonical stays source-aligned and versioned. | `uddp_platform` / `canonical` | `shaped_record`, `shaping_change`, `canonical_resident`, `canonical_resident_version`, `canonical_*` (family, benefit, warnings, errors, chunks, progress), `application*`, `asset*`, `held_record*`, `dq_result`, `dq_batch_evaluation`, `source_quality_gate`, `quality_gate_result`, `identifier_type*`, `identifier_vault`, `identity_policy`, `reference_dataset`, `reference_value`, `lgd_*` |
| **identity-service** | Decides *who is the same person*. Java service hosting the `MatchingService` boundary; runs the Python Splink adapter (`resident-match-v1`) as today, against its own DB only. Matching profiles, labels, training, monitoring. Never touches canonical or golden data. | `uddp_identity` / `identity` | `matching_run`, `resident_match_pair`, `resident_match_cluster_member`, `matching_profile`, `matching_label`, `matching_metric_bound`, `matching_run_metrics`, `matching_threshold_proposal`, `matching_training_job`, `matching_alert` + projections `candidate_resident`, `current_membership` |
| **registry-service** | Decides *which value wins*. Generic SurvivorshipEngine (DMN rules from the pack), Golden Resident and Golden Family lifecycle (stable IDs, versions, history, merge/split, carry-forward ADR-0001/0006), field provenance, UDDP IDs, benefits linked to golden residents, scheme eligibility, lineage events. | `uddp_registry` / `registry`, `lineage` | `golden_build`, `golden_resident*`, `golden_family*`, `uddp_id`, `benefit`, `benefit_end_marker`, `scheme_eligibility_*` + new `lineage.lineage_event` + projections `canonical_observation`, `link_constraint` |
| **statepack-service** | Publish, approve (maker-checker, ADR via V18/V44) and serve State Packs and Scheme Packs by version and digest. Studio pack generation and drafts, rule sets, departments and state profile, scheme catalogue. Packs are immutable once published. | `uddp_platform` / `statepack` | `state_pack_lifecycle`, `state_pack_document`, `state_pack_approval`, `scheme_pack*`, `rule_set`, `ai_rule_draft`, `studio_draft`, `studio_suggestion_review`, `department`, `department_source_pack`, `state_profile` |
| **api-stewardship-service** | Edge/BFF for the onboarding UI: authentication, sessions, permissions, state/department scoping, audit; read APIs (Resident 360 by API composition and read models); review queue, manual merge/split, decision log; onboarding orchestration (saga); release gates; alerts and webhooks; privacy retention requests; setup mode. | `uddp_platform` / `stewardship` | `stewardship_review_task`, `stewardship_decision`, `stewardship_proposal`, `stewardship_link_constraint` (source of truth), `onboarding_run`, `onboarding_stage_execution`, `run_release_decision`, `app_user*`, `app_role*`, `app_permission`, `user_*_scope`, `app_audit_event`, `alert_rule`, `alert_notification`, `webhook_delivery`, `retention_run`, `retention_tombstone` + read-model projections |

Boundary rules:

1. A service reads and writes only its own schema. No cross-schema joins, views or foreign keys, even inside
   `uddp_platform`. Monolith FKs that cross a boundary become plain `uuid` columns with a documented logical
   reference (for example `canonical.canonical_resident.raw_record_id`).
2. Data another service needs arrives by event (projection table) or by a synchronous read API. Projections are
   rebuildable from topics or by a replay endpoint.
3. Matching and Golden builds never update canonical rows or matching evidence (rule 3 of the monolith). With
   separate databases this becomes structural, not just a convention.
4. Choices I made where the brief was silent (please confirm): shaping, held records, identifier vault and LGD
   geography go to **canonical-service** (they interpret pack semantics and feed mapping); benefits and scheme
   eligibility go to **registry-service** (they hang off golden residents); onboarding orchestration and auth go to
   **api-stewardship-service** (it is the edge and already drives the UI).

## 3. Event contracts

### 3.1 Envelope (every topic)

JSON, validated against `contracts/events/envelope.v1.schema.json`:

```json
{
  "eventId": "uuid (deterministic where possible, see 5.3)",
  "eventType": "uddp.canonical.resident-created",
  "schemaVersion": 1,
  "occurredAt": "2026-11-04T05:00:00Z",
  "producer": "canonical-service",
  "partitionKey": "string, same as the Kafka record key",
  "correlationId": "onboarding run or replay run id",
  "causationId": "eventId that caused this one",
  "stateCode": "data only, never a branch condition",
  "statePack": { "packId": "...", "version": "1.2.0", "digest": "sha256:..." },
  "payload": { }
}
```

Kafka headers duplicate `eventId`, `eventType`, `schemaVersion` and `correlationId` so consumers and tools can route
without parsing. Payloads never contain raw protected identifiers: canonical-service replaces them with vault
tokens before publishing (existing `IdentifierProtection`), and never contain credentials.

### 3.2 Schemas and compatibility

- One JSON Schema per event type and version in the shared `contracts` module (`contracts/src/main/resources/events/`),
  with Java records generated or hand-written beside them. Producers validate before writing the outbox; contract
  tests in each consumer validate sample payloads.
- Rules: additive changes only within a version (new optional fields); consumers ignore unknown fields (same rule
  as pack extensions, ARCHITECTURE.md J); a breaking change creates `.v2` on a new topic and both are produced
  during migration.
- Recommendation: start with schemas in the repo plus contract tests (no extra infrastructure). A schema registry
  (Apicurio, which supports JSON Schema) can be added later without changing the contracts. **Decision needed.**

### 3.3 Event catalogue

| Event (topic `uddp.<name>.v1`) | Producer | Key | Payload (main fields) | Consumers |
|---|---|---|---|---|
| `ingestion.batch-started` | ingestion | `batchId` | batchId, sourceSystem, sourceDefinition ref, pack ref | api-stewardship |
| `ingestion.raw-record-received` | ingestion | `sourceSystem:rawRecordId` | rawRecordId, batchId, sourceSystem, sourceLine, payloadFormat, rawPayload (inline), landedAt, replayRunId (null) | canonical |
| `ingestion.batch-completed` | ingestion | `batchId` | batchId, recordCount, checksum, status | canonical, api-stewardship |
| `ingestion.raw-record-replayed` | ingestion | `sourceSystem:rawRecordId` | as raw-record-received + replayRunId + requested pack ref | canonical |
| `canonical.resident-created` | canonical | `sourceRecordKey` | canonicalResidentId, sourceRecordKey, version, supersedesCanonicalId, rawRecordId, canonicalPayload (Common Data Model, tokens), warnings, processingRunId | identity, registry, api-stewardship |
| `canonical.family-observed`, `canonical.benefit-observed`, `canonical.application-observed`, `canonical.asset-observed` | canonical | `sourceRecordKey` | observation id, canonical payload, links to canonical resident ids | registry |
| `canonical.record-held` | canonical | `sourceRecordKey` | heldId, rawRecordId, reasonCode, rule ids | api-stewardship |
| `canonical.batch-processed` | canonical | `batchId` | batchId, processingRunId, counts (created/held/rejected/warnings), DQ gate result | identity, api-stewardship |
| `canonical.record-rejected` (**DLQ**, topic `uddp.canonical.dlq.v1`) | canonical | `sourceSystem:rawRecordId` | rawRecordId, batchId, errors[] (ruleId, field, reasonCode), pack ref | api-stewardship (rejection reports) |
| `identity.match-decided` | identity | `sourceRecordKey` of record A | matchingRunId, canonicalRecordA/B, probability, weight, decision `AUTO_MATCH`/`POSSIBLE_MATCH`/`NO_MATCH`, evidence | registry, api-stewardship (POSSIBLE_MATCH → review task) |
| `identity.cluster-assigned` | identity | `sourceRecordKey` | matchingRunId, canonicalRecordId, clusterId | registry |
| `identity.match-run-completed` | identity | `matchingRunId` | matchingRunId, mode FULL/INCREMENTAL, modelVersion, thresholds, pairCount, clusterMemberCount | registry, api-stewardship |
| `registry.golden-resident-changed` | registry | `goldenResidentId` | goldenResidentId, uddpId, changeType CREATED/UPDATED/MERGED/SPLIT/RETIRED, versionNo, members[], mergedInto/splitFrom, goldenBuildId | identity (current membership), api-stewardship (read model) |
| `registry.golden-family-changed` | registry | `goldenFamilyId` | as above for families | api-stewardship |
| `registry.golden-build-completed` | registry | `goldenBuildId` | goldenBuildId, matchingRunId, counts | api-stewardship |
| `statepack.pack-published`, `statepack.pack-approved`, `statepack.pack-activated`, `statepack.pack-retired` | statepack | `packId` | packId, stateCode, version, digest, approver/maker | all services (cache invalidation) |
| `stewardship.decision-recorded` | api-stewardship | `goldenResidentId` (or review task id) | decisionId, kind ACCEPT/REJECT/MERGE/SPLIT, sourceRecordKeys, constraint (must-link / cannot-link), reviewer | registry, identity (labels) |
| `privacy.erasure-requested` | api-stewardship | subject key | tombstone id, scope | every service |

Commands (also Kafka, same envelope, `uddp.cmd.<name>.v1`), sent by the onboarding saga in api-stewardship:
`ingestion.ingest-requested`, `ingestion.replay-requested` (batch or source/time selection + explicit pack ref),
`identity.matching-requested` (FULL/INCREMENTAL), `registry.golden-build-requested`. Each service replies with its
normal domain events; the saga advances `onboarding_stage_execution` from them.

### 3.4 Dead-letter handling

Two kinds, kept separate:

- **Business invalid** (failed ERROR validation rules, unmapped mandatory codes): published by canonical to
  `uddp.canonical.dlq.v1` as `canonical.record-rejected`. It is data, not a failure: it feeds the existing rejected
  record reports, and a steward can fix the pack and replay.
- **Technical poison** (schema-invalid event, repeated exception): each consumer retries with backoff (3 attempts
  on a retry topic `<topic>.retry.<consumer>`), then parks the message on `<topic>.dlt.<consumer>` with the error.
  Parked messages are replayable by an admin endpoint. They never block the partition.

## 4. Topics and partitioning

| Topic | Partitions (dev / prod start) | Retention | Why this key |
|---|---|---|---|
| `uddp.ingestion.raw-record-received.v1` | 3 / 12 | 7 days (Raw DB is the authority) | spreads load; canonical serialises versions of one source record by a DB constraint, because the source record key is only known after mapping |
| `uddp.ingestion.raw-record-replayed.v1` | 3 / 12 | 7 days | same |
| `uddp.ingestion.batch-*.v1` | 1 / 3 | 30 days | batch control |
| `uddp.canonical.resident-created.v1` | 3 / 12 | compacted + 30 days | per source record order (versions arrive in order) |
| `uddp.canonical.*-observed.v1` | 3 / 12 | compacted + 30 days | same |
| `uddp.canonical.batch-processed.v1`, `uddp.canonical.dlq.v1` | 1 / 3 | 30 days / 90 days | |
| `uddp.identity.match-decided.v1`, `uddp.identity.cluster-assigned.v1` | 3 / 12 | 30 days | |
| `uddp.identity.match-run-completed.v1` | 1 / 3 | 90 days | |
| `uddp.registry.golden-resident-changed.v1`, `golden-family-changed.v1` | 3 / 12 | compacted | per golden id order; compacted topic = current state for projections |
| `uddp.registry.golden-build-completed.v1` | 1 / 3 | 90 days | |
| `uddp.statepack.*.v1` | 1 / 1 | compacted | low volume, total order per pack |
| `uddp.stewardship.decision-recorded.v1` | 1 / 3 | infinite | append-only decision log mirror |
| `uddp.cmd.*.v1` | 1 / 3 | 7 days | |

Run completeness: registry starts a Golden build for a matching run only when it has received
`match-run-completed` and counted `pairCount` pairs and `clusterMemberCount` cluster rows for that run (all in its
inbox). This replaces "read the matching tables" without a cross-database query.

Volume note: matching is a batch over the population, so per-pair events are fine at MVP scale. If a run grows to
millions of pairs, `match-run-completed` can instead carry a pointer to a run export served by identity-service;
the contract keeps that option open with an optional `exportRef` field.

## 5. Reliable messaging

### 5.1 Outbox (every producing service)

```sql
CREATE TABLE <schema>.outbox_event (
  event_id      uuid PRIMARY KEY,
  topic         text NOT NULL,
  partition_key text NOT NULL,
  event_type    text NOT NULL,
  payload       jsonb NOT NULL,   -- full envelope
  headers       jsonb NOT NULL DEFAULT '{}',
  created_at    timestamptz NOT NULL DEFAULT now(),
  published_at  timestamptz
);
CREATE INDEX ON <schema>.outbox_event (created_at) WHERE published_at IS NULL;
```

The domain write and the outbox insert share one transaction. A relay in the same service polls unpublished rows
in `created_at` order with `FOR UPDATE SKIP LOCKED`, sends with an idempotent Kafka producer (`acks=all`,
`enable.idempotence=true`), and sets `published_at`. Delivery is at least once. Published rows are pruned after
7 days. Debezium CDC can replace the poller later without changing contracts. The relay lives in the shared
`messaging` module.

### 5.2 Idempotent consumers (inbox)

```sql
CREATE TABLE <schema>.processed_event (
  consumer   text NOT NULL,
  event_id   uuid NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);
```

A consumer inserts into `processed_event` and applies its effect in one transaction; a duplicate key means "already
done, commit offset". Natural keys back this up (`canonical_resident (source_record_key, version)`,
`resident_match_pair (run_id, a, b)`, `golden_resident_membership` effective intervals).

### 5.3 Deterministic event ids

Where an event is derived from an immutable row, its id is a name-based UUID (v5) of `eventType + rowId`. Example:
Hop writes raw rows itself (Hop stays technical and does not know about Kafka). When Hop finishes, ingestion-service
reads the batch and writes one outbox row per raw record in one transaction. If that step crashes and reruns, the
same event ids are produced and the outbox primary key absorbs duplicates.

## 6. Database ownership

| Database | Schema | Owner role (login) | Flyway history table | Other access |
|---|---|---|---|---|
| `uddp_raw` | `ingestion` | `uddp_ingestion` | `ingestion.flyway_schema_history` | Hop connects with the same role (logical connection name, secrets outside packs) |
| `uddp_identity` | `identity` | `uddp_identity` | `identity.flyway_schema_history` | Python Splink adapter uses `uddp_identity` |
| `uddp_registry` | `registry`, `lineage` | `uddp_registry` | `registry.flyway_schema_history` (one history for both schemas) | none |
| `uddp_platform` | `canonical` | `uddp_canonical` | `canonical.flyway_schema_history` | none |
| `uddp_platform` | `statepack` | `uddp_statepack` | `statepack.flyway_schema_history` | none |
| `uddp_platform` | `stewardship` | `uddp_stewardship` | `stewardship.flyway_schema_history` | none |

- Each role owns only its schema; `REVOKE ALL ON SCHEMA public`; no `USAGE` grants on other schemas. A Testcontainers
  test per service proves the role cannot read another schema.
- Each service runs its own Flyway at startup (as `StartupMigration` does today) with
  `schemas=<own>`, `defaultSchema=<own>`.
- Credentials keep the current rule: environment variables only, now per service, e.g.
  `UDDP_CANONICAL_POSTGRES_URL/USER/PASSWORD`. Docker Compose reads them from the user environment or an untracked
  `.env`; nothing is hard-coded or printed.
- Append-only guarantees (Raw, golden history, `uddp_id`, decision log) keep their existing triggers inside the owning
  schema.

### 6.1 Projections that replace monolith joins

| Service | Projection | Built from | Replaces |
|---|---|---|---|
| identity | `candidate_resident` (current canonical version per source record, comparison columns only) | `canonical.resident-created` | `canonical_resident_current` + `resident_v11` view |
| identity | `current_membership` | `registry.golden-resident-changed` | `golden_resident_membership` read in `run.py` incremental mode |
| registry | `canonical_observation` (canonical payload per version) | `canonical.resident-created`, `*-observed` | joins to `canonical_resident` in `GoldenResidentService` |
| registry | `link_constraint` | `stewardship.decision-recorded` | `stewardship_link_constraint` |
| api-stewardship | `resident_360` read model | registry, canonical, identity events | `read/*ReadService` SQL across all tables |

## 7. Replay

Replay keeps ARCHITECTURE.md D: re-read Raw with an explicitly chosen State Pack version, never overwrite.

1. A steward (or the saga) sends `cmd.ingestion.replay-requested` with a selection (batch, source, time window) and
   a pack ref (`packId`, `version`, `digest`).
2. ingestion-service reads the immutable Raw rows and publishes `raw-record-replayed` with a new `replayRunId`.
   Raw is not modified.
3. canonical-service fetches that exact pack (digest checked), creates a new processing run and new canonical
   *versions* (`supersedesCanonicalId`), plus lineage. Earlier versions stay.
4. Identity and registry react as for new data; Golden IDs carry forward by source-record overlap (ADR-0001).

## 8. Cross-cutting concerns

- **State Packs at runtime:** `statepack-client` (shared module) fetches a pack by version + digest over HTTP, checks
  the digest, caches it immutably and parses it with the existing `StatePackLoader` model. Every event carries the
  pack ref it was produced with, so a consumer uses the same version.
- **Security:** api-stewardship-service authenticates users (existing ADR-0017 sessions/OAuth) and calls services
  with a signed JWT carrying permissions and state/department scopes (ADR-0018/0019). Each service is an OAuth2
  resource server and re-checks permissions on its own endpoints. Services call each other with client-credential
  tokens.
- **Audit:** every service writes `audit.recorded` events; api-stewardship keeps `app_audit_event` as the store.
- **Observability:** `correlationId` in logs (MDC), Micrometer metrics per consumer lag and outbox backlog. The
  existing monitoring and quality-gate metrics become per-service read APIs.
- **Portability guard:** the `M10PortabilityGuardTest` idea becomes one ArchUnit + forbidden-pattern test that runs
  over every service module (no `State1Mapper`, no `"ST2"` literals, no state names in Java, Python, Hop or SQL).

## 9. Target repository layout (Phase 1 onward)

```text
uddp_msa/
  pom.xml                       parent + aggregator (Java 21, Spring Boot 3.5.6)
  contracts/                    event envelope, JSON Schemas, Java event records, topic names
  messaging/                    outbox relay, inbox consumer support, Kafka config
  statepack-model/              State Pack model + loader (moved from common-core/statepack) + statepack-client
  common-core/                  the monolith as one module; shrinks every phase, removed at the end
  services/
    ingestion-service/
    canonical-service/
    identity-service/
    registry-service/
    statepack-service/
    api-stewardship-service/
  matching/                     Python Splink adapter (unchanged API, new DB connection)
  hop/ state-packs/ m10-state-packs/ platform/ common-model/ sample-data/ web/ scripts/
  docker-compose.yml            Kafka (KRaft), 4 Postgres DBs (or one server with 4 DBs), init SQL for roles
  docs/msa-design.md
```

Shared modules carry contracts and plumbing only, never domain logic, so services cannot couple through a shared
library.

## 10. Migration plan from the monolith

The monolith is never modified. Each phase ends with every existing test still passing, then stops for review.

### Phase 1: multi-module build and infrastructure

1. Bring the monolith into `uddp_msa` (see decision D1) and wrap `common-core` as a module under a parent POM.
2. Add empty modules: `contracts`, `messaging`, `statepack-model`, and the six service shells (Spring Boot app,
   health endpoint, own Flyway with an empty V1, one Testcontainers smoke test each).
3. `docker-compose.yml`: Kafka in KRaft mode (single broker for dev), Postgres 16 with the four databases, one role
   per service, schemas and grants from an init script. Credentials only from environment variables.
4. Done when: `mvn -q test` on the monolith module passes unchanged (187 test classes), each shell's smoke test
   passes, `docker compose up` gives Kafka plus four databases.

### Phase 2: outbox and Kafka behind the existing interfaces

1. Add an `EventPublisher` port in common-core with two adapters: `InProcessEventPublisher` (default, current
   behaviour) and `OutboxEventPublisher` (writes `outbox_event` in the same transaction). New migration in the MSA
   copy only.
2. Emit the catalogue events from the existing code paths (raw landed, canonical created, match run completed,
   golden changed, pack published, decision recorded) without changing what the code does.
3. Add the outbox relay and inbox support in `messaging`; contract tests validate each event against its schema.
4. Add Kafka-backed adapters for `HopRunner`, `MatchingService` trigger and golden-build trigger, selected by
   configuration and off by default.
5. Done when: all existing tests pass with the default config; new Testcontainers tests (Postgres + Kafka) prove
   outbox → Kafka → idempotent consumer, duplicate delivery and relay restart.

Phase 2 as built (2026-11-04): `messaging` module (`Outbox`, `OutboxRelay`, `Inbox`); `common-core` V112 adds
`outbox_event`/`processed_event`; `gov.uddp.core.events.PlatformEvents` writes raw, canonical (incl. DLQ), match and
golden events from `CanonicalResidentProcessingService`, `PostgresCanonicalResidentRepository`,
`GoldenResidentService` and the onboarding matching stage, only with `uddp.events.enabled=true`;
`OutboxRelayScheduler` publishes when `uddp.events.kafka.bootstrap-servers` is also set. Step 4 (Kafka command
adapters for `HopRunner`, matching and golden-build triggers) moves to Phase 3, where each adapter gets its real
consumer in the extracted service. Statepack and stewardship events follow with those services. Envelopes carry no
`statePack` ref yet (the monolith records only the pack version string); statepack-service adds the digest.
The portability guard baseline is now the local tag `msa-import` (the import commit), since the monolith's tags were
not copied.

### Phase 3: extract services in this order

For each service: create its schema with a new V1 that recreates the monolith end-state of the tables it owns
(cross-boundary FKs removed), build its projections, switch the monolith to the Kafka adapter, then delete the
moved code from the `common-core` module. Each step proves the e2e numbers still hold (3 states: 7 raw / 7 canonical
/ 3 AUTO_MATCH / 5 golden / 6 golden families / 5 benefits; with State 4: 9 / 9 / 6 / 6 golden, 8 families) and
that every baseline Golden ID is unchanged in the incremental run.

1. **identity-service.** Move `matching/*` Java services and tables. Build `candidate_resident` and
   `current_membership`. Change `matching/run.py` to read those two tables in `uddp_identity` (same columns, so the
   Splink model and its tests do not change). Triggered by `canonical.batch-processed` or `cmd.identity.matching-requested`.
   *As built (Phase 3a, 2026-11-04):* identity-service V2/V3 hold the matching tables (no FKs to canonical), the
   projections `candidate_resident`, `current_membership`, `known_golden_resident`, `review_outcome`, and
   compatibility views with the monolith's names (`canonical_resident_current`, `canonical_resident_match_v`,
   `golden_resident_membership`, `golden_resident`, `stewardship_decision`, `stewardship_review_task`), so
   `matching/run.py` runs **unchanged** (proven: the 7 sample residents give the 3 Ram Singh AUTO_MATCH pairs).
   `MatchingRuns` publishes match-decided / cluster-assigned / match-run-completed (with `correlationId`). The monolith
   gets `MatchingRunner` (local `PythonMatchingRunner` stays the default) and, with `uddp.identity.remote=true`,
   `RemoteMatchingRunner` (command + wait) and `RemoteMatchingProjection` (V113: runs stay RECEIVING until complete).
   Still in the monolith: matching profile, label, training, monitoring and identity-policy management (their tables
   exist in identity-service but are not synced yet), and stewardship.decision-recorded (so `review_outcome` stays
   empty and run metrics show no acceptance by band in remote mode).
2. **ingestion-service.** Move Hop runner, connectors, schedules, delivery change detection and Raw tables. Point
   Hop's logical connection at `uddp_raw`. canonical consumes `raw-record-received` instead of reading
   `raw_record`; `RawRecordRepository` becomes an in-memory view of the received batch.
   *As built (Phase 3b, 2026-11-04):* ingestion-service V2 owns `ingestion_batch` and an append-only `raw_record`
   (trigger; only a one-time privacy purge may change a row). The three generic Hop templates take a generic
   `RAW_SCHEMA` parameter (default `public`, so the monolith and the e2e scripts are unchanged; ingestion-service
   passes `ingestion`). `Ingestions` runs `cmd.ingestion.ingest-requested` (template + allow-listed parameters,
   validated by its JSON Schema), publishes raw-record-received per row then batch-completed, and runs
   `cmd.ingestion.replay-requested` as raw-record-replayed without touching Raw. The monolith, with
   `uddp.ingestion.remote=true`, uses `RemoteHopRunner` (parameters from `ProcessHopRunner.fileInvocation`) and
   `RemoteRawProjection` (V114) so canonical processing reads the same raw ids. Still local: database-table sources
   (their credentials are not sent over Kafka), source connectors, schedules, landing folders and delivery change
   detection; source files are passed as paths, so both services must see the same filesystem for now.
3. **registry-service.** Move survivorship, golden lifecycle, family builder, UDDP ID, benefits, eligibility.
   Build `canonical_observation` and `link_constraint`. Golden build starts from `match-run-completed`; history and
   carry-forward stay exactly as ADR-0001/0006.
   *As built (Phase 3c, 2026-11-04):* registry-service V2 holds the monolith's end-state Golden tables (generated
   from a V114 schema, FKs to other services' rows dropped), V3 the projections with the monolith's names
   (`canonical_resident`, `canonical_resident_version`, `canonical_family_*`, matching tables,
   `stewardship_link_constraint`/`stewardship_decision`, and the views `canonical_resident_current`,
   `canonical_family_membership_current`, `stewardship_link_constraint_effective`), `lineage.lineage_event`, and the
   outbox. The Golden engine is the monolith's own code, reused from its plain jar (the executable jar now has the
   `exec` classifier) and wired as the application wires it; it moves into the module when canonical and statepack
   leave the monolith. Canonical processing now also publishes `canonical.family-observed`. Proven from events
   alone: 5 Golden Residents, 6 Golden Families, UDDP IDs, Ram Singh in one identity, and an unchanged rebuild keeps
   every Golden ID. **Not switched over yet:** the monolith still builds its own Golden records, because its read
   APIs, stewardship, scheme eligibility and benefits read the Golden tables in-process; that read side moves with
   api-stewardship-service (step 5). Also open: stewardship decision events (so registry's link constraints stay
   empty), identity policy and DMN survivorship rules in registry-service (both optional in the monolith and not
   wired here), and an automatic build on match-run-completed (builds are commanded).
4. **canonical + statepack last, together** (per the brief). Split what remains into canonical-service and
   statepack-service; packs are then fetched by digest over HTTP.
   *As built (Phase 3d, 2026-11-04):* **statepack-service** (V2: `state_pack_version`, immutable `state_pack_file`,
   append-only `state_pack_decision`) publishes, approves (checker must differ from maker), activates (retiring the
   previous ACTIVE version) and serves packs by version, by state+version and by digest; lifecycle events go through
   its outbox. `contracts.StatePackDigest` defines the digest and `contracts.StatePackClient` verifies it before a pack
   is cached for the loader. **canonical-service** reuses the monolith's canonical engine and State Pack loader
   together (plain jar), with its own schema (canonical tables, Raw projection, LGD master, identifier types); it
   maps a batch once ingestion-service reported it and every record arrived, using only packs fetched from
   statepack-service and verified by digest, and publishes resident-created, family-observed, the DLQ and
   batch-processed. The monolith gets `CanonicalBatchProcessor` and, with `uddp.canonical.remote=true` (needs
   `uddp.ingestion.remote=true`), `RemoteCanonicalProcessor` + `RemoteCanonicalProjection` (V115). Residents are
   consumed by their own consumer group because a family observation may arrive first (`Inbox.NotYetApplicable`
   waits instead of parking it). Not evented yet: benefit, application and asset observations (in remote canonical
   mode the monolith's benefit reports stay empty), held-record decisions, and replay of a batch with a different
   pack version in canonical-service.
5. **api-stewardship-service.** What is left of the monolith (API controllers, security, onboarding saga,
   stewardship, read models, setup) becomes this service and the `common-core` module is removed.

   *As built (2026-11-04):* **api-stewardship-service** is the monolith's remaining application started with
   `api-stewardship.yml`: events on and `uddp.{ingestion,canonical,identity,registry}.remote=true`, so every pipeline
   stage is another service's and the edge holds read models (Raw, canonical, matching runs, and a **Golden replica**:
   registry-service publishes `uddp.registry.golden-replica.v1` with every row a build wrote, applied by primary key
   with `RowReplication`). Golden builds, including those after a steward's merge or split, go to registry-service
   (`RemoteGoldenBuilder`, `GoldenBuildRunner`); decisions are published as `stewardship.decision-recorded` (rows of
   `stewardship_decision` and `stewardship_link_constraint`) and registry-service waits for the named decision before
   it builds. Its database is `uddp_platform` with `currentSchema=stewardship` (UDDP_POSTGRES_* variables).

### Status and remaining work (2026-11-04)

Done: Phases 0-4. Every service has its own database/schema, Flyway location and history, outbox and inbox; the
cross-service test (`mvn test -pl e2e -Dmsa.e2e=true`) onboards States 2 and 4 by pack data alone with real Splink.
Not done yet, in priority order:

1. ~~Review tasks in remote mode~~ (done 2026-11-04: after each replicated remote build the edge turns the run's
   POSSIBLE_MATCH pairs into review tasks, as the monolith's build does; `RemoteReviewTasksTest`).
2. ~~Benefit, application and asset observations~~ (done 2026-11-04: canonical-service publishes each batch's
   benefit, application, asset, DQ and processing-error rows as `canonical.batch-observed` in ordered chunks of at
   most 500 rows before batch-processed; the edge applies them row for row and the MAPPED stage waits for them, V116).
   registry-service does not consume them yet (scheme eligibility and benefit reads are computed at the edge).
3. ~~Matching profiles and identity policy~~ (done 2026-11-04: every matching-requested command carries the edge's
   `matching_profile` and `identity_policy` rows, APPROVED ones last, and identity-service applies them row for row
   before the run, so the Splink adapter uses the approved profile and policy). Profile training, steward labels and
   threshold proposals still run at the edge against its own read models.
4. **common-core split, first step done (2026-11-04):** the 152 classes the engines need (State Pack loading,
   shaping, mapping, validation and DQ, canonical processing, identifier types, geography reads, survivorship, the
   Golden lifecycle and the shared event projections) moved unchanged, in their packages, to the `engine` module;
   canonical-service and registry-service depend on `uddp-engine` only, and common-core (220 classes, the edge's
   application) depends on it. The packages are cyclic (the canonical package alone reaches 33 of 34 packages), so
   the next step, splitting `engine` into a canonical engine and a Golden engine, first needs those cycles broken
   (PostgresConnectionFactory out of `canonical`, the `events` hooks behind interfaces). The portability guard
   protects both module paths and treats an unchanged move as no change.
5. Database-table sources, connectors, schedules and landing folders stay at the edge (credentials never go over
   Kafka); source files are passed as paths (shared filesystem); a Golden replica is one event (8 MB limit);
   canonical-service cannot yet replay a batch with a different pack version; real Hop runs are not in automated tests.

### Phase 4: cross-service portability proof

One Testcontainers e2e (Kafka + Postgres DBs + all six services in-process or as containers, real Splink adapter,
Hop run outside unit tests as today): onboard States 1–3, then add **State 2** and **State 4** purely by publishing
and approving their packs through statepack-service and running ingestion. The test asserts the counts above, and a
guard asserts the service source trees are byte-identical to the tagged baseline before and after (the
`M10PortabilityGuardTest` idea applied to every module).

## 11. Risks

- **Golden build needs the whole run.** Mitigated by run-completed counts (section 4); worst case an export ref.
- **Eventual consistency in the UI.** Read models lag by seconds. Onboarding run screens already poll stage state,
  so the saga state is the source of truth for "is it done".
- **Large raw payloads on Kafka.** Max message size is set to 2 MB; larger payloads get stored in Raw and the event
  carries a fetch ref served by ingestion-service.
- **Test runtime.** Many Testcontainers suites; per-service tests only, one shared Kafka/Postgres container per
  module via singleton containers.
- **Known monolith gaps stay gaps** (no stewardship screen, synchronous onboarding recovery, etc.); this work does not
  fix them unless asked.

## 12. Decisions needed before Phase 1

- **D1. How to bring the code over. DECIDED (2026-11-04): plain file copy with a fresh `git init`** and one initial
  import commit. Gitignored and local-only content (`studio-workspace/`, `studio-packs/`, `backups/`, `target/`,
  `node_modules/`) is not copied.
- **D2. Schema registry.** Recommended: JSON Schema files in `contracts` with contract tests now; Apicurio later.
- **D3. Postgres. DECIDED (2026-11-04): the user's local PostgreSQL** (localhost:5432) holds the four databases
  `uddp_raw`, `uddp_identity`, `uddp_registry`, `uddp_platform`, created by the user. docker-compose runs Kafka only
  (Postgres there only under an optional profile). Phase 1 ships `scripts/msa-db-roles.sql` that the user runs to
  create one login role per service with grants on its own schema only; services connect via per-service env vars.
  Automated tests still use Testcontainers, never the local databases. The monolith's `uddp` DB is never touched.
- **D4. Existing local `uddp` DB data.** Recommended: no data migration; the MSA databases are new and filled by
  replaying `sample-data` through the pipeline. The `uddp` DB is never touched.
- **D5. Placement choices in section 2.4** (shaping, held, vault, LGD in canonical; benefits and eligibility in
  registry; onboarding saga and auth in api-stewardship).
