# M11 acceptance: Golden Record Lifecycle and Incremental Processing

**Status: COMPLETE** (every criterion below is PASS)

Branch `feature/m11-golden-lifecycle`, created from `master` after `master` was fast-forwarded to the M10 tip
`218f19a` (tagged `m10-onboarding-portability`). Closed on 2026-10-25. Nothing is pushed.

## Scope delivered

- **Design**: [docs/golden-lifecycle.md](../golden-lifecycle.md) (data model, carry-forward, incremental flow,
  canonical versioning, migration, API, six worked examples) and ADRs 0001-0006 in [docs/decisions/](../decisions/).
- **Schema** `V10__golden_lifecycle.sql`: `canonical_resident_version` + view `canonical_resident_current`;
  `matching_run.mode`; `onboarding_run.processing_mode`; Golden identity status / `merged_into_id` /
  `split_from_id` on `golden_resident`; `golden_build`, `golden_resident_version` (+ `_provenance`),
  `golden_resident_membership` (effective dates), `golden_resident_event`. Existing per-run Golden rows migrate to
  identities: the most recent build's IDs stay current, older IDs retire into them.
- **Stable identity** (`golden/CarryForward`, `GoldenTargetGroups`, `GoldenLifecycleStore`, `GoldenResidentService`):
  every build updates one current Golden set; IDs carried forward by source-record overlap (ties: oldest); merges
  retire the loser with `MERGED_INTO`; splits get new IDs with `SPLIT_FROM`; versions only on real change;
  provenance per version; events `CREATED`, `MEMBER_ADDED`, `MEMBER_REMOVED`, `MEMBER_VERSION_CHANGED`,
  `VALUE_CHANGED`, `MERGED`, `SPLIT`, `RETIRED`.
- **API**: Golden detail carries status, current version and a version summary; retired IDs return 200 with
  `mergedIntoId` and `resolvedGoldenResidentId`; new `GET /api/golden-residents/{id}/history`; `includeRetired` on
  the list.
- **Canonical versions** (`canonical/SourceRecordKey`, processing service, repository): source record key from
  `recordKeyFields` (default `recordIdField`); unchanged re-deliveries skipped, corrections and State Pack version
  changes become version n+1; rows of one batch sharing a key are all rejected.
- **Incremental**: `python -m matching.run --incremental`; Golden builds honour the run's mode; onboarding runs take
  `processingMode` `FULL` (default) or `INCREMENTAL`.
- **Tooling**: `scripts/reset-and-run-incremental-e2e.ps1`; `scripts/clean-local-golden.ps1` (verified pg_dump into
  gitignored `backups/`, migrate, rebuild with stable IDs).

## Decisions

The product owner's rules (carry-forward, splits, policy/model changes, latest canonical only) are ADR-0001. Choices
made in M11, each the most conservative generic option:

| ADR | Choice |
| --- | --- |
| 0001 | Tie-breaks: oldest identity (`created_at`, then UUID); on an even split, the side with the identity's longest-standing member |
| 0002 | Keep `golden_resident` as the identity table; its V6 columns become the founding snapshot; drop the per-run unique key |
| 0003 | Source record key via optional `recordKeyFields`; state-1 configures `[ration_no, beneficiary_nm]` because `ration_no` is a family number |
| 0004 | "New or changed" = latest canonical that is not a current Golden member; incremental runs score only pairs touching one |
| 0005 | Portability guard re-baselined on the M10 tag with an explicit list of M11 engine files |
| 0006 | Only one-time closes update history rows; retired IDs resolve through the API (200, not 404/redirect) |

## Acceptance criteria

| # | Criterion | Evidence | Result |
| --- | --- | --- | --- |
| 0 | Baseline recorded | [M11_BASELINE.md](M11_BASELINE.md): 61 Java, 13 Python, e2e 7/7/3/5 and 9/9/6/6 | PASS |
| 1 | Design document with model, algorithm, flow, versioning, migration and the six worked examples | `docs/golden-lifecycle.md`, ADRs 0001-0006 | PASS |
| 2.1 | Blank DB migrates V1-V10 cleanly | `DatabaseMigratorTest.createsTheWholeSchemaOnABlankDatabase` (10 migrations, all new tables) | PASS |
| 2.2 | DB at V9 with Golden rows migrates without losing identities | `GoldenLifecycleMigrationTest` (two legacy builds; latest IDs active, older retire into them, legacy and canonical rows byte-identical); local DB: same 5 IDs before and after | PASS |
| 3.1 | Carry-forward on every build, one current Golden set | `GoldenLifecycleTest`, `CarryForwardTest` (6 rule cases) | PASS |
| 3.2 | Versions and events recorded; provenance tied to the version | `GoldenLifecycleTest`, `GoldenIncrementalAndCorrectionTest` (per-version provenance in history) | PASS |
| 3.3 | Detail with current version and history; `/history`; retired IDs return their target | `GoldenResidentHistoryApiTest` (HTTP), `GoldenLifecycleTest` (split pointer) | PASS |
| 4.1 | Only new/changed canonicals are matched; INCREMENTAL onboarding mode; full runs unchanged | `test_run_incremental.py` (3), `GoldenIncrementalAndCorrectionTest`, all M10 onboarding tests still pass | PASS |
| 4.2 | Corrected source data: new canonical version supersedes the old, old excluded, Golden VALUE_CHANGED with history | `CanonicalVersioningTest`, `GoldenIncrementalAndCorrectionTest` (full and incremental) | PASS |
| 4.3 | State Pack version change re-maps into new versions, not duplicates | `CanonicalVersioningTest.aStatePackVersionChangeRemapsIntoNewVersionsNotDuplicates` | PASS |
| 5.1 | Re-run on unchanged data: every ID identical, no new versions | `GoldenLifecycleTest.rerunningMatchingAndGoldenOnUnchangedDataKeepsEveryIdAndCreatesNoVersion` | PASS |
| 5.2 | State 4 incremental: still 6 Golden, same Ram Singh ID, version increments, MEMBER_ADDED | `GoldenIncrementalAndCorrectionTest.stateFourOnboardedIncrementallyJoinsTheExistingRamSinghIdentity`; incremental e2e | PASS |
| 5.3 | Correction: same ID, new version, VALUE_CHANGED, old value in history | `GoldenIncrementalAndCorrectionTest.aMobileCorrection...` (2 tests) | PASS |
| 5.4 | Merge: survivor by the rule, loser RETIRED with MERGED_INTO, ID never reused | `GoldenLifecycleTest.mergeKeeps...`, `CarryForwardTest` (largest overlap; tie to oldest) | PASS |
| 5.5 | Split: larger side keeps the ID, other gets a new ID with SPLIT_FROM | same test, second half; `CarryForwardTest` | PASS |
| 5.6 | Policy v2 -> v3: same IDs, new versions | `GoldenLifecycleTest.survivorshipPolicyChange...`; local DB rebuild (5 IDs, version 2, `POLICY_CHANGED`) | PASS |
| 5.7 | Full Java and Python regression | 80/80 Java, 16/16 Python, 0 skipped | PASS |
| 5.8 | 3-state e2e 7/7/3/5; State 4 e2e 9/9/6/6; new incremental e2e with Ram Singh ID unchanged | outputs below | PASS |
| 6.1 | Local DB: pg_dump backup first, migrations, clean 7 -> 5 with stable IDs | section below | PASS |
| 6.2 | Audit: no state-specific code; V1-V9 unchanged; only the generic Hop pipeline; guard passes and catches violations | section below | PASS |
| 6.3 | Docs, known gaps, README updated; `git diff --check` clean | files listed under scope; `git diff --check` exit 0 | PASS |

## Test counts

| Suite | Before M11 | After M11 |
| --- | --- | --- |
| Java (`mvn test`) | 61 run / 61 passed | 80 run / 80 passed / 0 failed / 0 skipped |
| Python (`pytest matching`) | 13 passed | 16 passed |

New Java tests (19): `database.GoldenLifecycleMigrationTest` (1), `golden.CarryForwardTest` (6),
`golden.GoldenLifecycleTest` (3), `golden.GoldenIncrementalAndCorrectionTest` (3),
`api.GoldenResidentHistoryApiTest` (1), `canonical.CanonicalVersioningTest` (5). New Python tests (3):
`matching/test_run_incremental.py`. `DatabaseMigratorTest` was updated for V10 (10 migrations; the manual-V7 case
now also removes V10 objects and expects 3 pending migrations).

## End-to-end results (real Hop 2.19 and Splink, throwaway PostgreSQL 16)

3-state, `scripts/reset-and-run-e2e.ps1` (the Golden count is now active Golden identities):

```
E2E counts
  raw_record          7
  canonical_resident  7   (processing errors: 0)
  AUTO_MATCH pairs    3   (POSSIBLE_MATCH: 0)
  golden_resident     5   (active identities; run 5804cb7d-5728-4d54-a20a-9ba64f9e2632, policy resident-survivorship-v3)
E2E PASSED: raw=7 canonical=7 golden=5 AUTO_MATCH=3
```

State 4, `scripts/reset-and-run-m10-e2e.ps1`:

```
E2E counts
  raw_record          9
  canonical_resident  9   (processing errors: 0)
  AUTO_MATCH pairs    6   (POSSIBLE_MATCH: 0)
  golden_resident     6   (active identities; run 34903fba-f539-4da3-8c0d-55bfdd246522, policy resident-survivorship-v3)
E2E PASSED: raw=9 canonical=9 golden=6 AUTO_MATCH=6
```

Incremental, `scripts/reset-and-run-incremental-e2e.ps1` (3-state full baseline, then State 4 incrementally):

```
E2E PASSED: raw=7 canonical=7 golden=5 AUTO_MATCH=3
Incremental counts
  raw_record          9
  canonical_resident  9
  matching mode       INCREMENTAL   (records considered: 2)
  AUTO_MATCH pairs    3   (incremental run 93686553-ade1-4ac2-b9b8-7569c93ff408)
  golden_resident     6   (active; created=1 versioned=1 unchanged=4)
  Ram Singh Golden ID 45663010-bd9b-43c4-a1ac-41af3e7532ed -> 45663010-bd9b-43c4-a1ac-41af3e7532ed (version 2)
INCREMENTAL E2E PASSED: raw=9 canonical=9 golden=6 AUTO_MATCH=3 Ram Singh Golden ID unchanged (45663010-bd9b-43c4-a1ac-41af3e7532ed, version 2)
```

Why 3 AUTO_MATCH incrementally: only pairs touching the two new State 4 records are scored, and State 4 Ram Singh
matches the three existing Ram Singh records; the full State 4 run scores all C(4,2) = 6 Ram pairs. The script also
asserts that every baseline canonical keeps its Golden ID.

## Local database (`scripts/clean-local-golden.ps1`)

```
Before
  flyway version          9
  raw_record              7
  canonical_resident      7
  matching_run            1
  golden_resident rows    5
Backing up to D:\projects\uddp-mvp\backups\uddp-20261025-143340.dump ...
Backup verified: 55,300 bytes, 106 entries
Applied 1 migration(s); schema version 10
Flyway validate: OK
Golden build 53e353a3-0af7-433c-b31d-bd48766c8b93 from run 38ed8d29-d5e6-4be9-b7fa-5c40e38ba441 (resident-survivorship-v3): created=0 reused=5 versioned=5 unchanged=0 retired=0
After
  raw_record              7
  canonical_resident      7
  matching_run            1
  golden_resident rows    5
  current canonical       7
  active Golden           5
  retired Golden          0
  Golden versions         10
  current memberships     7
CLEAN-LOCAL-GOLDEN PASSED: raw=7 canonical=7 golden=5; backup D:\projects\uddp-mvp\backups\uddp-20261025-143340.dump
```

The five Golden IDs in the backup (`pg_restore --data-only -t golden_resident`) are the five active IDs after the
run; each is now at version 2 (`POLICY_CHANGED`, v2 -> v3), with Ram Singh holding 3 current members. (The run
printed "flyway version 9" after migrating because the query compared versions as text; the script now orders by
`installed_rank`, and the DB reports 10.) Nothing was deleted. Restore if ever needed with
`pg_restore --clean -d uddp backups/uddp-20261025-143340.dump`.

## Audit

```
$ grep -rniE "ST4|state-4|state4" common-core/src/main matching --exclude="test_*.py" --exclude=conftest.py
(exit 1: no hits)

$ grep -rniE "\bST[0-9]+\b|state-[0-9]+|state[0-9]+" common-core/src/main matching --exclude="test_*.py" --exclude=conftest.py --exclude-dir=__pycache__
(exit 1: no hits)

$ grep -rnE "(stateCode|state_code|STATE_CODE)[^;]*(==|equals\(|switch|case )" common-core/src/main/java matching --include=*.java --include=*.py --exclude="test_*.py"
(exit 1: no hits)

$ ls hop/pipelines
generic-file-ingest.hpl

$ git diff --name-status m10-onboarding-portability -- common-core/src/main/resources/db/migration
A	common-core/src/main/resources/db/migration/V10__golden_lifecycle.sql

$ git diff --name-status m9-data-quality-survivorship -- common-core/src/main/resources/db/migration
A	common-core/src/main/resources/db/migration/V10__golden_lifecycle.sql
A	common-core/src/main/resources/db/migration/V9__onboarding_orchestration.sql

$ git ls-tree <ref> common-core/src/main/resources/db/migration/   (blob IDs, V1-V9)
V1-V9 blobs identical: m10 tag vs HEAD
V1-V8 blobs identical: m9 tag vs HEAD
```

### Generic engine changes (the guard's `M11_GENERIC_CHANGES`)

Every change applies to every state alike; none reads a state, source or pack name.

| File | Change |
| --- | --- |
| `golden/CarryForward.java` (new) | ADR-0001 assignment over source record keys |
| `golden/GoldenTargetGroups.java` (new) | FULL/INCREMENTAL target groups (union-find over keys) |
| `golden/GoldenLifecycleStore.java` (new) | JDBC for identities, versions, memberships, events |
| `golden/GoldenResidentService.java` | builds via carry-forward; list/detail/history read versions |
| `golden/GoldenBuildSummary.java` | new counters (kept M6 fields) |
| `canonical/SourceRecordKey.java` (new) | key from the source definition's key fields; content comparison |
| `canonical/CanonicalResidentProcessingService.java` | versioned inserts, unchanged skip, duplicate-key rejection (plus a reformat-only commit) |
| `canonical/CanonicalResidentRepository.java`, `PostgresCanonicalResidentRepository.java` | latest version lookup; canonical + version row in one statement |
| `statepack/StatePackAssets.java` | optional `recordKeyFields` on `SourceDefinition` |
| `matching/run.py`, `matching/config.py` | latest versions only; `--incremental` blocking rule and cluster filter |

### Portability guard demonstration

The guard passed on the committed tree (3/3). Each temporary change was made, the guard run, then reverted:

```
== A: temporary state-specific class (onboarding/State4Mapper.java: return "ST4".equals(stateCode))
productionCodeNeverNamesAConfiguredState ... but was: <[.../State4Mapper.java: state-specific file name, ...]>
Tests run: 3, Failures: 1
== B: temporary state-specific Hop pipeline (hop/pipelines/state-4-ingest.hpl)
genericEnginesMatchingAndHopTemplateAreUnchangedSinceTheBaseline New files in protected generic paths ... <[hop/pipelines/state-4-ingest.hpl]>
theOnlyHopPipelineIsTheGenericOne ... but was: <[generic-file-ingest.hpl, state-4-ingest.hpl]>
Tests run: 3, Failures: 2
== C: temporary edit to a protected engine not on the M11 list (mapping/ResidentMappingEngine.java)
genericEnginesMatchingAndHopTemplateAreUnchangedSinceTheBaseline Protected generic code changed since m10-onboarding-portability outside the listed M11 engine changes
Tests run: 3, Failures: 1
== reverted: git status of production paths clean; guard 3/3
```

## Bugs found and fixed during M11

1. **DQ sample lost on an unchanged re-delivery.** `DqControllerTest` (which processes the same fixture twice in one
   database) failed once re-deliveries stopped creating duplicate canonicals: the warning finding had no canonical
   to point at. The finding now references the existing latest version.
2. **Duplicate keys in one delivery could supersede a different person.** Rejecting only the "repeat" depended on
   Raw read order (not file order), so the other row could become the record's next version. All rows sharing a
   key are now rejected (found by `CanonicalVersioningTest`).
3. **e2e script exited 0 after a failed REST call.** An exception inside the script's `try/finally` ended the run
   without a non-zero exit code; a `catch` now fails the run. Found when the refactored ingest step leaked Hop's
   output into the batch ID.
4. **Local cleanup printed the Flyway version as text max** ("9" after V10); fixed to use `installed_rank`.

## Known limitations and items deferred

- **M12 (read APIs / UI)**: Golden list has no paging or search; history returns everything at once.
- **M13 (stewardship)**: POSSIBLE_MATCH pairs are still not reviewable; there is no manual merge/split API (merges
  and splits come only from matching runs).
- Incremental matching does not re-score existing pairs; a model change needs a FULL run (ADR-0004). It still
  scores with Splink over the full table in memory (blocking restricts pairs, not the load), fine for the MVP size.
- A name correction in state-1 appears as a new source record (its key includes the name); pre-V10 state-1 rows were
  backfilled with the default key, so a re-ingest of state-1 into a migrated DB adds new source records (they match
  back to the same identity).
- The portability guard's `M11_GENERIC_CHANGES` list must be emptied and the baseline moved at the next milestone.
- Onboarding through the real `ProcessHopRunner`/`PythonMatchingRunner` remains untested by automation, and
  `reset-and-run-incremental-e2e` exists for Windows only (the `.sh` e2e covers the full run).
- Golden builds are serialised with a PostgreSQL advisory lock; there is still no recovery for a stage left RUNNING
  by a crashed process (from M10).

## Commits

```
558f1b6 M11 Part 0: record baseline test counts and e2e results
ce06f0a M11 Part 1: Golden lifecycle design and ADRs 0001-0006
37a2a79 M11 Part 2: V10 Golden lifecycle schema and migration of existing Golden rows
df0a14a M11 Part 3: stable Golden identity, carry-forward, versions, events and history API
f1b32b8 Reformat CanonicalResidentProcessingService.processBatch and PythonMatchingRunner to multi-line style (no behaviour change)
4c73f5c M11 Part 4: canonical versions, incremental matching and INCREMENTAL onboarding
783c46e M11 Part 5: acceptance tests for incremental onboarding and corrections; incremental e2e
```

plus the Part 6 commit with this report, the documentation updates and the cleanup-script fix.
