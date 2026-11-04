# M15 acceptance: Scheme and Benefit domains

**Status: COMPLETE (backend and API).** Every acceptance item below passed on 2026-10-25 on `feature/m15`, branched
from the M13 tag `m13-stewardship` (which includes M14). Nothing was pushed. The M12 UI is unfinished, so Resident 360
benefits and the reports are delivered as read APIs; their screens are deferred.

## Scope delivered

- **Platform scheme catalogue.** `platform/schemes/catalogue.yaml` (version 1.0.0), validated at startup:
  Food and Civil Supplies and Social Welfare; `PDS-RATION` (household level), `OLD-AGE-PENSION` and
  `DISABILITY-PENSION` (person level, one exclusivity group `SOCIAL-PENSION`). No state code in it.
- **Crosswalks from state and department codes.** Each pack maps its own codes with an ordinary `scheme` crosswalk
  (state 1: `PHH`, `AAY` → `PDS-RATION`; state 2: `VRIDHA`; state 3: `OAP`, `DIVYANG`) through declarative
  `Benefit:` mapping rules. YAML only.
- **Benefit model (V13).** Canonical benefit observations per delivery (append-only, idempotent), a stable platform
  `benefit_id` per source benefit, disbursements written once per benefit/date/reference, Benefit errors in their
  own table. Entitlement (amount or quantity, unit, frequency) and assistance are catalogue benefit types; status is
  `ACTIVE`/`SUSPENDED`/`CLOSED`, or `ENDED` when no current delivery names the benefit.
- **Links to Golden Residents and Golden Families** resolved on read (`benefit_holding_current`), so they follow
  every Golden build, merge, split and stewardship decision.
- **Sample PDS and pension data** mapped through States 1–3 (new columns in their sample files).
- **Read APIs.** `GET /api/v1/schemes`, `/api/v1/benefits`, `/api/v1/benefits/{id}`,
  `/api/v1/golden-residents/{id}/benefits` (Resident 360 benefits), `/api/v1/reports/duplicate-benefits`,
  `/api/v1/reports/eligible-not-enrolled`. OpenAPI and the UI's generated types regenerated.
- **E2E.** All three scripts assert benefits through the REST API (and the 3-state run again after the stewardship
  split/merge smoke); the `.sh` twin asserts them too.
- **Docs.** `docs/benefit-domain.md`, ADR-0011, ADR-0012, ADR-0005 update, API coverage, common data model, JSON
  Schemas (`common-model/scheme.schema.json`, `benefit.schema.json` 1.1.0), README.

## Decisions (the roadmap's "decisions before starting")

- **Which schemes and departments first (ADR-0011):** the PDS ration (Food and Civil Supplies, held by a household)
  and the old age pension (Social Welfare, held by a person), plus a disability pension in the same exclusivity group
  so duplicates across schemes are provable. These are what the sample sources already are and what the acceptance
  criteria name.
- **Eligibility in scope? Minimal (ADR-0012):** declarative `minAgeYears`/`maxAgeYears`/`genders` on RESIDENT-level
  catalogue schemes, evaluated only by the eligible-but-not-enrolled report. A full eligibility engine is deferred.
  The sample old age threshold is 45 because the synthetic residents are 32–51.
- **Benefits are source facts (ADR-0011):** no benefit matching or survivorship; two states paying one person are
  two benefits, which is what the duplicate report must see.
- **Fields only `Benefit:` rules read are not copied into Resident extensions.** Without this, every monthly payment
  would have created a new canonical Resident version and a Golden version (found by a test during Part 2).
- **Portability guard re-baselined on `m13-stewardship` (ADR-0005 update),** with `scheme/` now protected and one
  `M15_GENERIC_CHANGES` list.

## Acceptance criteria

| # | Criterion | Evidence | Result |
| --- | --- | --- | --- |
| 1 | **Ram Singh shows PDS and pension benefits from different states in one view** | `BenefitReadApiTest.ramSinghsResident360...`: 3 benefits, departments Food and Civil Supplies + Social Welfare, PDS (state 1, by household and as card member) and old age pensions (states 2 and 3), lineage to raw records; all three e2e runs: `Ram Singh: 3 ... PDS from ST1 and pensions from ST2,ST3` | PASS |
| 2 | **Duplicate-benefit report identifies a seeded duplicate correctly** | `BenefitReadApiTest.theCatalogueAndTheDuplicateReport...`: exactly 1 finding, Ram Singh, group `SOCIAL-PENSION`, 2 distinct pensions from 2 states; e2e `duplicate=Ram Singh` in every run | PASS |
| 3 | Duplicates follow Golden identity changes | `BenefitReportTest.theSeededDuplicateFollowsStewardshipSplitsAndMerges`: no finding before a build, 1 after; split → 0 (pension moves to the split identity); merge → back under the original ID; retired ID holds nothing. E2E re-check after split/merge | PASS |
| 4 | Two schemes of one group are a duplicate; suspended benefits are not active | `BenefitReportTest.twoSchemesOfOneGroup...` (fx-welfare: Asha Rao old age + disability pension; Esha Nair's suspended PDS) | PASS |
| 5 | Platform catalogue with crosswalks from state codes, validated | `SchemeCatalogueTest` (3): sample catalogue, 9 invalid catalogues rejected with their reason; two source codes → one scheme in `CanonicalBenefitProcessingTest` | PASS |
| 6 | Benefit model: entitlement, assistance type, disbursement; Resident or Family level | `BenefitMappingEngineTest` (4), `CanonicalBenefitProcessingTest` (entitlement, catalogue defaults, R1001 household key, 5 disbursements, Ramesh's household PDS) | PASS |
| 7 | Append-only and idempotent; latest delivery decides; history kept; IDs stable | `CanonicalBenefitProcessingTest.theLatestDeliveryDecides...`: re-processing writes nothing, a new month adds a disbursement, suspension recorded with history, a delivery naming no pension ends the link, 5 benefit IDs unchanged | PASS |
| 8 | Benefit errors never reject a Resident or Family | fx-welfare: 8 canonical Residents, 8 Family observations, 4 Benefit errors (unmapped code, scheme outside catalogue, household benefit without household, non-numeric amount) | PASS |
| 9 | Eligible-but-not-enrolled from declarative eligibility | `BenefitReadApiTest.eligibleButNotEnrolled...`: on 2026-10-25 eligible 3, enrolled 2, not enrolled Ramesh Kumar (51); on 2020-01-01 only Ramesh; 400 for a bad date, unknown scheme, scheme without eligibility; fx-welfare: enrolled through either pension of the group | PASS |
| 10 | Read APIs with paging, filters, 400/404 | `BenefitReadApiTest.listsFiltersPagesAndDetailsBenefits`; `OpenApiContractTest` (6 new paths, 6 new schemas) | PASS |
| 11 | No state-specific code; YAML-only onboarding of benefits | audit grep below; guard 3/3, shown failing on a temporary `State2BenefitMapper` | PASS |
| 12 | Existing behaviour unchanged; existing e2e counts | all 132 baseline tests pass; 7/7/3/5 + 6 families, 9/9/6/6 + 8, incremental 9/9/3/6 + 8 with Ram Singh's ID unchanged | PASS |
| 13 | Migrations: V1–V12 byte-identical, V13 only added; populated V12 DB upgrades without rewriting rows | `git diff --name-status m13-stewardship -- …/db/migration` → `A V13__scheme_benefit_domain.sql`; `BenefitMigrationTest` (2) | PASS |
| 14 | Testcontainers only; local uddp DB untouched | tests use `TestDatabase`; e2e uses throwaway containers; the local DB was not opened (no backup needed) | PASS |

## Test counts

| Suite | Before (Part 0) | After |
| --- | --- | --- |
| Java (`mvn clean test`) | 132 passed | **150 passed**, 0 failed, 0 errors, 0 skipped |
| Python (`pytest matching`) | 16 passed | **16 passed** |

Both Java counts include the 2 tests of M12's uncommitted `ProcessHopRunnerTest` (left untouched in the working tree).
New Java tests: `BenefitMigrationTest` (2), `SchemeCatalogueTest` (3), `BenefitMappingEngineTest` (4),
`CanonicalBenefitProcessingTest` (3), `BenefitReportTest` (2), `BenefitReadApiTest` (4).

Existing tests changed, none weakened:

- Migration-count tests expect V13 as well (same assertions; the V7 adoption test also drops the V13 objects;
  the V11 → V12 stewardship and V12 → V13 tests seed with pre-V13 processing).
- `CanonicalVersioningTest` appends a duplicate-key row to the pension file; the row now has the file's new
  (empty) benefit columns, otherwise it is a malformed row rather than a duplicate key. Same assertions.
- `matching/test_e2e_sources.py` asserts the state-1 file header exactly; it now expects the new header.
- `M10PortabilityGuardTest`: re-baselined on `m13-stewardship`, `scheme/` added to the protected paths, the fixture
  family and benefit packs added to the state-name list (stricter).

## End to end (real Hop 2.19 and Splink, throwaway PostgreSQL 16, V1–V13)

```text
reset-and-run-e2e.ps1:              E2E PASSED: raw=7 canonical=7 golden=5 AUTO_MATCH=3 families=6
                                    benefits 5 (Ram Singh: 3 in FOOD_CIVIL_SUPPLIES+SOCIAL_WELFARE, PDS from ST1
                                    and pensions from ST2,ST3); duplicate report 1 finding; eligible not enrolled: 1
                                    BENEFIT E2E PASSED (after the Golden build): duplicate=Ram Singh
                                    STEWARDSHIP E2E PASSED: split 6, merge 5, Ram Singh Golden ID unchanged
                                    BENEFIT E2E PASSED (after stewardship split and merge)
reset-and-run-m10-e2e.ps1:          E2E PASSED: raw=9 canonical=9 golden=6 AUTO_MATCH=6 families=8
                                    BENEFIT E2E PASSED (after the Golden build): benefits=5, Ram Singh 3, duplicate=Ram Singh
reset-and-run-incremental-e2e.ps1:  E2E PASSED: raw=7 canonical=7 golden=5 AUTO_MATCH=3 families=6
                                    BENEFIT E2E PASSED (after the Golden build)
                                    INCREMENTAL E2E PASSED: raw=9 canonical=9 golden=6 AUTO_MATCH=3 families=8
                                    Ram Singh Golden ID unchanged
                                    BENEFIT E2E PASSED (after incremental onboarding)
```

Benefits: state 1 R1001 (Ram, Sita) and R1002 (Ramesh), state 2 PPO-5001 (Ram) and PPO-5002 (Anita), state 3
OAP-9001 (Ram) = 5 benefits; State 4 declares no Benefit rules, so adding it changes no benefit count. The `.sh`
twin was syntax-checked (`bash -n`) but not run on this Windows machine.

## Audit

```text
$ git diff --name-status m13-stewardship -- common-core/src/main/resources/db/migration
A       common-core/src/main/resources/db/migration/V13__scheme_benefit_domain.sql
$ ls hop/pipelines
generic-file-ingest.hpl
$ grep -rniE "\b(st[1-4]|state-?[1-4]|fx-household|fxh|fx-welfare|fxw)\b" common-core/src/main <matching/*.py except tests>
(no output)
$ git diff --name-only m13-stewardship -- <protected engine paths>
common-core/src/main/java/gov/uddp/core/canonical/CanonicalBenefitProcessor.java
common-core/src/main/java/gov/uddp/core/canonical/CanonicalResidentProcessingService.java
common-core/src/main/java/gov/uddp/core/mapping/BenefitMappingEngine.java
common-core/src/main/java/gov/uddp/core/mapping/ResidentMappingEngine.java
common-core/src/main/java/gov/uddp/core/scheme/EligibilityEvaluator.java
common-core/src/main/java/gov/uddp/core/scheme/SchemeCatalogue.java
$ git diff --check m13-stewardship
(clean)
```

Each listed engine change is generic and applies to every pack alike (`M15_GENERIC_CHANGES`):

- `BenefitMappingEngine` (new): reads a pack's `Benefit:` rules and the platform catalogue; no source schema knowledge.
- `CanonicalBenefitProcessor` (new): writes observations, benefit identities and disbursements from the mapped rules.
- `CanonicalResidentProcessingService`: calls the benefit processor next to the Family processor, after a Resident
  is stored or found unchanged; Resident mapping, validation, versioning and counts are unchanged.
- `ResidentMappingEngine`: reformatted in its own commit (no behaviour change), then source fields read only by
  `Benefit:` rules are no longer copied into Resident extensions. No pack before M15 has Benefit rules, so earlier
  Resident payloads are unchanged (all baseline tests and e2e counts pass).
- `SchemeCatalogue`, `EligibilityEvaluator` (new): platform configuration loading and declarative evaluation.

The guard failed with a temporary `mapping/State2BenefitMapper.java` naming `ST2` (2 failures: new protected file;
state-specific name and file name) and passed again after it was removed.

## Bugs found and fixed during M15

1. Benefit-only source columns leaked into Resident extensions, so a payment or status change created a new
   canonical Resident version (`CanonicalBenefitProcessingTest`); fixed generically in `ResidentMappingEngine`.

## Known limitations and deferred items

- No screens: Resident 360 benefits, the benefit list and the two reports wait for the M12 UI.
- Eligibility is age and gender only; no means test, documents, per-state thresholds, FAMILY-level eligibility or
  rules engine (ADR-0012). The sample threshold of 45 is not a real scheme rule.
- One benefit per source row; no benefit survivorship, events, matching or stewardship; history is the observation
  log. Reports are computed on request and are not paged.
- The sample State Packs gained Benefit rules and columns without a pack version bump (as State 4's Family rule in
  M14); a database that already holds state 1–3 canonicals from before M15 sees the new columns only on re-delivery.
- The local uddp DB was not touched and is still at V10; the app applies V11–V13 (additive) at its next startup
  unless `uddp.database.migrate-on-startup=false`.
- `IMPLEMENTATION_PLAN.md` still has M12's uncommitted roadmap renumbering in the working tree, so it was not edited;
  M15 is recorded here, in README.

## Commits

```text
9f9f57b M15 Part 0: baseline test counts before any Scheme or Benefit change
3445693 M15: reformat ResidentMappingEngine to readable multi-line style (no behaviour change)
b970a1e M15 Parts 1-2: Scheme and Benefit design, ADR-0011/0012, V13 schema, scheme catalogue and canonical benefit observations
0a97dfd M15 Part 3: Resident 360 benefits, benefit and scheme read APIs, duplicate and eligible-not-enrolled reports
(this commit) M15 Part 4: e2e benefit assertions, audit, docs and acceptance report
```
