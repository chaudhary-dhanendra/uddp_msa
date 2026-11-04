# M14 acceptance: Family domain

**Status: COMPLETE.** Every acceptance item below passed on 2026-10-25 on `feature/m14-family-domain`
(based on `7a2cad1`, M12 Parts 0–2 committed). Nothing was pushed.

## Scope delivered

- **Configuration-driven Family mapping.** `FamilyMappingEngine` applies each State Pack's declarative `Family:` rules
  (`sourceFamilyIds[]`, `membership.relationshipType` via crosswalk onto a closed platform list, `extensions.*`).
  The rules that packs have carried since M1 are now used; State 4 gained a one-line Family rule (YAML only).
- **Canonical Family observations (V11).** Canonical processing records, for every stored or unchanged Resident
  delivery, which households it names (`canonical_family_observation`, `canonical_family_membership`), append-only and
  idempotent; the latest delivery per resident record decides its current households
  (`canonical_family_membership_current`). Family errors go to `canonical_family_processing_error` and never reject
  a Resident.
- **Stable Golden Families.** Built at the end of every Golden Resident build in the same transaction: one platform
  UUID per source household, members resolved to current Golden Residents, versions, memberships with effective
  dates and events (`CREATED`, `MEMBER_ADDED`, `MEMBER_REMOVED`, `MEMBER_CHANGED`, `RETIRED`, `REACTIVATED`). A
  Golden Resident merge or split moves household members to the new identity.
- **Read APIs.** `GET /api/v1/families`, `/api/v1/families/{id}`, `/api/v1/families/{id}/history`,
  `/api/v1/golden-residents/{id}/families`; OpenAPI and the UI's generated types regenerated.
- **E2E.** The three e2e scripts now also assert active Golden Families (6, then 8 with State 4).
- **Docs.** `docs/family-domain.md`, ADR-0009, ADR-0005 update, API coverage, README.

## Decisions

- ADR-0009: Families are observed by the canonical layer; a Golden Family is one source household, never merged
  across sources automatically (shared members are reported as related families).
- ADR-0005 (update): the portability guard stays on the `m11-golden-lifecycle` tag because M12 is not accepted; M14's
  engine files are listed in `M14_GENERIC_CHANGES`.
- V11 was amended once inside M14 (adding the `REACTIVATED` event type) before it was applied to any database; the
  local uddp DB is at V10 and has never seen V11.

## Acceptance criteria

| # | Criterion | Evidence | Result |
| --- | --- | --- | --- |
| 1 | Family rules in packs are applied without state-specific code | `FamilyMappingEngineTest` (4), `CanonicalFamilyProcessingTest` (3); guard 3/3 | PASS |
| 2 | Canonical Family observations keep lineage to raw record and canonical Resident | `samplePacksObserveEveryResidentsHousehold...` (lineage query = 0 mismatches); `FamilyReadApiTest` follows member → canonical → raw | PASS |
| 3 | Append-only, idempotent; re-processing writes nothing | same test, second processing pass: 7 observations, 7 memberships | PASS |
| 4 | A Family error never rejects a Resident | fx-household: 6 canonical Residents, 1 Family error (`COUSIN`) | PASS |
| 5 | Latest delivery decides the household; history kept | `theLatestDeliveryDecidesTheHousehold...`: Rohan HH1 → HH2, 10 observations, HH1's 5 memberships kept | PASS |
| 6 | One stable Golden Family per household; unchanged rebuild writes nothing | `GoldenFamilyTest.sampleHouseholds...`: 6 families, 7 memberships, same IDs, 6 versions after rebuild | PASS |
| 7 | Members follow Golden Resident merges | `aGoldenResidentMergeMoves...`: 2 `MEMBER_CHANGED`, previous IDs retired into the survivor, no member points at a retired identity | PASS |
| 8 | Moves, retirement and reactivation keep IDs and history | `movesRetirementAndReactivation...`: HH1 v2, HH2 retired then `REACTIVATED` v3 under the same ID, HH9 new | PASS |
| 9 | State 4 households onboarded by configuration alone | `stateFourHouseholdsAreOnboardedByConfigurationAlone` (incremental onboarding: 8 families, Ram Singh in 4); e2e below | PASS |
| 10 | Read APIs with paging, filters, 400/404 | `FamilyReadApiTest` (3); `OpenApiContractTest` | PASS |
| 11 | Resident behaviour unchanged | all 102 baseline tests pass; e2e Resident counts unchanged | PASS |
| 12 | Migrations: V1–V10 byte-identical, V11 only added; populated V10 DB upgrades without rewriting rows | `git diff --name-status m11-golden-lifecycle -- …/db/migration` → `A V11__family_domain.sql`; `FamilyMigrationTest` | PASS |
| 13 | Testcontainers only; local uddp DB untouched | tests use `TestDatabase`; e2e uses throwaway containers; local DB read once (Flyway history, read-only) | PASS |

## Test counts

| Suite | Before (Part 0) | After |
| --- | --- | --- |
| Java (`mvn test`) | 102 passed | **118 passed**, 0 failed, 0 errors, 0 skipped |
| Python (`pytest matching`) | 16 passed | **16 passed**, 0 skipped |

New Java tests: `FamilyMigrationTest` (2), `FamilyMappingEngineTest` (4), `CanonicalFamilyProcessingTest` (3),
`GoldenFamilyTest` (4), `FamilyReadApiTest` (3). Changed: `DatabaseMigratorTest` and `GoldenLifecycleMigrationTest`
now expect V11 as well (same assertions; the V7 adoption test also drops V11 objects to model a V7 schema).

## End to end (real Hop 2.19 and Splink, throwaway PostgreSQL 16)

```text
reset-and-run-e2e.ps1:              E2E PASSED: raw=7 canonical=7 golden=5 AUTO_MATCH=3 families=6
reset-and-run-m10-e2e.ps1:          E2E PASSED: raw=9 canonical=9 golden=6 AUTO_MATCH=6 families=8
reset-and-run-incremental-e2e.ps1:  E2E PASSED: raw=7 canonical=7 golden=5 AUTO_MATCH=3 families=6
                                    INCREMENTAL E2E PASSED: raw=9 canonical=9 golden=6 AUTO_MATCH=3 families=8
                                    Ram Singh Golden ID unchanged (version 2)
```

Families: 3 states give R1001 (Ram, Sita), R1002, F5001, F5002, F9001, F9002 = 6 households with 7 memberships;
State 4 adds H4-RAM and H4-NEHA = 8 with 9.

## Audit

```text
$ git diff --name-status m11-golden-lifecycle -- common-core/src/main/resources/db/migration
A       common-core/src/main/resources/db/migration/V11__family_domain.sql
$ ls hop/pipelines
generic-file-ingest.hpl
$ grep -rniE "\b(st[1-4]|state-?[1-4]|fx-household|fxh)\b" common-core/src/main matching/*.py
(no output)
$ git diff --name-only m11-golden-lifecycle -- <protected engine paths>
common-core/src/main/java/gov/uddp/core/canonical/CanonicalFamilyProcessor.java
common-core/src/main/java/gov/uddp/core/canonical/CanonicalResidentProcessingService.java
common-core/src/main/java/gov/uddp/core/golden/GoldenFamilyBuilder.java
common-core/src/main/java/gov/uddp/core/golden/GoldenResidentService.java
common-core/src/main/java/gov/uddp/core/mapping/FamilyMappingEngine.java
$ git diff --check
(clean)
```

Each listed engine change is generic and applies to every pack that declares Family rules:

- `FamilyMappingEngine` (new): reads the pack's `Family:` rules; no source schema knowledge.
- `CanonicalFamilyProcessor` (new): writes observations from the mapped rules.
- `CanonicalResidentProcessingService`: calls the processor after a Resident is stored or found unchanged; Resident
  mapping, validation, versioning and counts are unchanged.
- `GoldenFamilyBuilder` (new): household consolidation from canonical observations.
- `GoldenResidentService`: calls the builder inside the build transaction; Resident grouping and survivorship are
  unchanged.

The guard was shown to fail with a temporary `mapping/State4FamilyMapper.java` naming `ST4` (2 failures: new
protected file, state-specific name and file name) and to pass again after it was removed.

## Bugs found and fixed during M14

1. The working-tree draft `FamilyMappingEngine` did not compile (it passed a `Map` where `Family` expects an
   `Address`); replaced by the new engine.
2. Stale `target/classes` carried the draft V11 into an incremental build; baselines are measured with
   `mvn clean test`.

## Known limitations and deferred items

- Households are never linked across sources; there is no household matching model or manual household merge.
- Household attributes are kept in `family_payload.extensions` but not survived (no Family survivorship policy).
- No Family screen: the M12 UI is unfinished; the API is ready for it.
- Golden Families are rebuilt from all current observations on each build (fine at MVP size).
- M12 remains NOT accepted; its uncommitted UI work is still in the working tree, untouched.

## Commits

```text
6f36bae M14 Part 0: baseline test counts before any Family change
f42791c M14 Part 1: Family domain design, ADR-0009 and V11 schema
e6473c3 M14 Part 2: canonical Family observations from State Pack Family rules
cba36b1 M14 Part 3: stable Golden Families built with every Golden Resident build
28c2f49 M14 Part 4: Family read APIs under /api/v1
(this commit) M14 Part 5: e2e Family assertions, audit, docs and acceptance report
```
