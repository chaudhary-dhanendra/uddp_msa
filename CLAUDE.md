
## Working rules (token budget)
- Do only the task asked. Do not re-explore the repo; open only files named in the prompt or directly needed.
- Tests: run ONLY tests for changed code, quietly: `mvn -q test -Dtest=<Class>` / `npx vitest run <file>`.
  Never run the full suite, e2e scripts, Playwright or screenshots unless the prompt says "FULL CHECK".
- Print only failures (redirect test output to a file; show the failing lines).
- Git: one commit at the end, one-line message. Do not print git status/log/diff.
- No baseline files, acceptance reports, ADRs or docs unless asked. Add a one-line note to docs/decisions/LOG.md for real design choices.
- Final answer: max 5 lines — what changed, tests run and result, anything I must do.
- If the usage limit is near: commit WIP and append the next step to docs/RESUME.md (2 lines).
- Keep existing rules: no state-specific code; Testcontainers only; never touch the uddp DB; never print secrets.



# UDDP MVP — instructions for Claude Code

## What this project is
Unified Digital Data Platform: a configuration-driven platform that onboards state
government data sources without state-specific code. Core promise:
**"Build the platform once; configure it for every state."**

Pipeline: Apache Hop generic ingest -> Raw/Landing (Postgres) -> State Pack + generic
ResidentMappingEngine -> Canonical Resident (lineage/replay) -> Splink matching (Python)
-> generic SurvivorshipEngine -> Golden Resident (+ field provenance) -> REST APIs.

Milestones M1–M11 are complete for the **Resident** domain. M9 provides Data Quality
Framework and Cross-State Survivorship; M10 adds persisted onboarding orchestration
(`onboarding/`, `/api/onboarding`) and the State 4 portability proof; M11 makes Golden IDs stable
identities with versions, history, merge/split and incremental processing (`docs/golden-lifecycle.md`,
ADRs in `docs/decisions/`). M14 adds the Family domain (`docs/family-domain.md`, ADR-0009): canonical Family
observations from each pack's `Family:` rules and stable Golden Families built with every Golden build. M13 (done after
M14) adds stewardship: review queue, manual merge/split, decision log. M15 adds Scheme and Benefit domains
(`docs/benefit-domain.md`, ADR-0011/0012): the platform scheme catalogue `platform/schemes/catalogue.yaml`, each pack's
`Benefit:` rules crosswalked onto it, Resident 360 benefits and duplicate/eligible-not-enrolled reports. The Onboarding
Studio (ADR-0013..0016, `docs/milestones/STUDIO_ACCEPTANCE.md`) adds setup mode (`setup/`, `/api/setup`), an encrypted
local settings store, a generic Studio (`studio/`, `/api/studio`) that generates ordinary State Packs into
`studio-packs/`, database-table sources (`generic-db-ingest.hpl`) and the UI wizard. The shaping layer (ADR-0020, `docs/shaping-operations.md`, `shaping/`) turns raw CSV/xlsx rows into tidy records from a source's `layout`/`parse` steps; Shaping is proven by unit and Studio tests on synthetic workbooks (no reference dataset is kept in the repo). Dev OS is Windows
(PowerShell); prefer cross-platform commands. Read
`ARCHITECTURE.md`, `MVP_SCOPE.md`, `IMPLEMENTATION_PLAN.md` and `docs/` before larger changes.

## Layout
- `services/edge-service/` — the edge (formerly `common-core/`), package root `gov.uddp.core`; the other
  services are in `services/` and the shared engines in `engine/`, `canonical-engine/`, `golden-engine/`,
  `matching-engine/`, `studio-engine/` (docs/msa-design.md)
  - `statepack/` loader, `mapping/` engine, `canonical/` persistence, `golden/` survivorship, `api/` controllers
- `services/identity-service/matching/` — Python Splink 4.0.17 adapter (`resident-match-v1`)
  - `scheme/` platform scheme catalogue and declarative eligibility (M15); `read/Benefit*Service` benefit reads and reports
  - `services/edge-service/src/main/resources/db/migration/` — the edge's Flyway path; each service has
    its own `db/<schema>` location
- `services/statepack-service/seed/state-packs/state-{1,2,3}/` — YAML: sources, mappings, crosswalks, validation, matching, survivorship
- `services/statepack-service/seed/m10-state-packs/state-4/` — State 4 portability fixture (used by the e2e)
- `services/ingestion-service/hop/pipelines/generic-*-ingest.hpl` — the ONLY ingest pipelines (generic)
- `platform/studio/` — Studio metadata: mapping targets, keyword groups, rule defaults (no state names)
- Studio drafts, uploads and evidence are runtime data in statepack-service (`UDDP_STUDIO_WORKSPACE`, default its
  `target/studio-workspace`); nothing is written to the repository root
- `common-model/` — JSON Schemas for Resident, Family, Scheme, Benefit
- `services/ingestion-service/seed/sample-data/` — tiny CSVs per state (7 residents total; 3 are "Ram Singh")

## Commands
- Start DB: `docker compose up -d postgres`
- Java tests: `mvn test -pl services/<service>` (needs Docker: Postgres proofs use Testcontainers, never your DB);
  cross-service: `mvn test -pl e2e -Dmsa.e2e=true`; real Hop: `mvn test -pl services/ingestion-service
  -Dtest=RealHopIngestionTest -Dmsa.hop=true`
- Python tests: `pip install -r services/identity-service/matching/requirements-dev.txt && python -m pytest
  services/identity-service/matching` (integration tests need Docker)
- Run order: discovery-service (8761), then the services (8101-8106, each registers by name), then
  gateway-service (8080, the only public entry; point the UI at it). Service URLs default to `lb://<name>`.
- CI: `.github/workflows/ci.yml` (build, each service's tests incl. the full edge suite, Python, UI, e2e, deployment files)
- Observability: `docker compose --profile observability up -d` (Grafana 3000, Prometheus 9090, Tempo, Loki;
  `docs/observability.md`)
- Infrastructure: `docker compose up -d kafka kafka-init` (topics from `deploy/kafka/create-topics.sh`);
  `docker compose --profile postgres up -d postgres` creates the service databases and roles from `deploy/postgres/`
  (or run those two SQL files with psql against an existing server)
- Build Golden: `POST /api/golden-residents/build {"matchingRunId": "<uuid>"}` (carries Golden IDs forward;
  history at `GET /api/golden-residents/{id}/history`)

## Non-negotiable architecture rules
1. **No state-specific code.** Never add `State1Mapper`, `if (stateCode == "ST2")`,
   or a `state-2-*.hpl` pipeline. State differences go in `state-packs/` YAML only.
2. Hop does technical ingestion only; semantics live in Common Core.
3. Matching decides *who is the same person*; survivorship decides *which value wins*.
   Keep them separate. Matching and Golden builds must never UPDATE canonical rows
   or matching evidence.
4. Raw is append-only; canonical records stay source-aligned; lineage must be preserved.
5. Golden IDs are platform UUIDs, never Splink cluster IDs. They are carried forward by source-record
   overlap (ADR-0001), never reused or deleted; history tables are append-only (ADR-0006).
6. Local dev DB: PostgreSQL on localhost:5432, db `uddp`, user `postgres`. Connection
   settings come ONLY from Windows user env vars: UDDP_POSTGRES_URL, UDDP_POSTGRES_HOST,
   UDDP_POSTGRES_PORT, UDDP_POSTGRES_DB, UDDP_POSTGRES_USER, UDDP_POSTGRES_PASSWORD.
   Never hard-code or print credentials in source, YAML, Hop metadata, tests or docs;
   production will only change the environment.
7. New domains (Family, Scheme, Benefit) follow the same layered pattern as Resident.

## Coding style
- Prefer readable, multi-line Java. Several existing files (e.g. `GoldenResidentService`,
  `GoldenResidentController`, test classes) are dense one-liners — when you touch them,
  reformat to normal style without changing behaviour, in a separate commit.
- Every change ends with automated tests. Run `mvn test` and the pytest suite before
  declaring a task done.
- Small, reviewable commits, one milestone/concern per branch.

## Known gaps (do not assume these work)
- State Pack `validation/` rules, `matching/` thresholds and `survivorship/fieldPriority` are
  wired in (V7 migration). POSSIBLE_MATCH pairs become review tasks (M13, `docs/stewardship.md`, ADR-0010);
  accept/reject, merge and split are append-only decisions (source-record-key link constraints) applied by every
  Golden build. API only: no stewardship screen, roles or maker-checker yet (reviewer is a declared name until M17).
- Benefits (M15): benefits are source facts (no benefit survivorship, matching, events or stewardship), one per source
  row; Golden links are resolved on read. Eligibility is age/gender only (ADR-0012; sample old age threshold 45). Fields
  read only by `Benefit:` rules are not Resident extensions. No benefit screens; the sample packs gained Benefit rules
  and columns without a pack version bump. State 4 declares no Benefit rules.
- Family (M14): one Golden Family per source household; households are never merged across sources, household
  attributes are not survived, and there is no Family screen or household stewardship yet.
- Spring's Flyway/DataSource auto-config stays disabled; `database/StartupMigration` runs Flyway itself
  through `PostgresConnectionFactory` (`uddp.database.migrate-on-startup=false` turns it off).
- Java Golden tests use a fixture matching run (`testsupport/MatchingRunFixture`, FULL or INCREMENTAL); Splink is
  proven by `matching/test_run_integration.py` and `matching/test_run_incremental.py`. Real Hop ingestion runs only
  outside unit tests.
- `M10PortabilityGuardTest` compares the generic engines with the `m15-scheme-benefit` tag and allows only the files in
  its `STUDIO_GENERIC_CHANGES` list (ADR-0005); re-baseline it on the next accepted milestone's tag.
- Settings: `UDDP_POSTGRES_*` env vars win; otherwise `~/.uddp/uddp.properties` (password AES-GCM, key file beside it);
  with neither the backend runs in setup mode. Tests set `uddp.settings-directory` to a temp/target folder.
- Incremental matching does not re-score existing pairs; a model change needs a FULL run. A name correction in a
  source keyed by name (state-1 `recordKeyFields`) appears as a new source record. Pre-V10 state-1 canonicals were
  backfilled with the default key, so re-ingesting state-1 into a migrated DB creates new source records.
- Older M12 views in `web/onboarding-ui/src/features/simple` still use untyped `any`.
- Onboarding (`docs/m10-onboarding.md`) is synchronous and in-process. A stage left RUNNING by a crashed
  process blocks its run (no recovery yet); resume retries only the failed stage. The API path with the real
  `ProcessHopRunner`/`PythonMatchingRunner` is not covered by automated tests (the e2e scripts call Hop and
  Splink directly); `ProcessHopRunner` uses `hop-run.bat` (Windows) and passes no Hop project.
- `PUBLISHED` only marks a run complete; it publishes nothing beyond the Golden build.
