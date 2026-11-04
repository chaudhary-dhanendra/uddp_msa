# M10 acceptance: State 4 Portability Proof and Onboarding Orchestration

**Status: COMPLETE** (every criterion below is PASS)

Branch `feature/m10`, baseline tag `m9-data-quality-survivorship` (commit `f8b1b3d`). Close-out on 2026-10-25.

## Scope delivered

- **Onboarding orchestration** (`common-core/.../onboarding/`, `V9__onboarding_orchestration.sql`): State Pack
  lifecycle `DRAFT -> VALIDATED -> ACTIVE -> RETIRED`; persisted runs and per-attempt stage executions for
  `PACK_VALIDATED -> SOURCE_TESTED -> INGESTED -> MAPPED -> DQ_CHECKED -> MATCHED -> GOLDEN_BUILT -> PUBLISHED`
  with counts, timestamps, execution IDs and errors; failure stops the run; resume retries only the failed
  stage; at most one stage executes per run at a time.
- **REST API** `/api/onboarding`: register/transition a pack, start a run (STEP or FULL), run status, run
  history per state, next stage, named stage, resume; typed error contract (404/409/400/500 STAGE_FAILED).
- **State 4 portability proof**: `m10-state-packs/state-4/` and `sample-data/state-4/` only; onboarded by the
  unchanged generic Hop pipeline, mapping, DQ, Splink matching and survivorship engines.
- **Portability guard** covering every generic engine, the matching module, the Hop pipelines and state names
  in production code.
- Documentation: `docs/m10-onboarding.md`, `docs/m10-portability.md`, `docs/dev-setup.md`, `README.md`.

## Acceptance criteria

| # | Criterion | Evidence | Result |
| --- | --- | --- | --- |
| 1.1 | All 8 stages persisted in order, status COMPLETED | `OnboardingPersistenceTest` (DB rows, order and each stage starting after the previous finished) | PASS |
| 1.2 | Attempt = 1 for each stage | same | PASS |
| 1.3 | Record counts correct and non-null | same: exact input/output per stage checked against `raw_record`, `canonical_resident`, `dq_batch_evaluation`, `resident_match_cluster_member`, `golden_resident` | PASS |
| 1.4 | started/finished set, finished >= started, duration derivable | same (`completed_at - started_at` computed in SQL, >= 0) | PASS |
| 1.5 | Unique execution ID per stage; run ID links all stages | same (8 distinct IDs, 8 rows for the run, none elsewhere) | PASS |
| 1.6 | Run COMPLETED only after PUBLISHED | same (RUNNING before every stage, COMPLETED and `completed_at` only after PUBLISHED) | PASS |
| 2.1 | Failure injected at a middle stage generically | `OnboardingFailureResumeTest`: MATCHED fails via the matching-boundary hook, no state branch | PASS |
| 2.2 | Earlier stages COMPLETED attempt 1, not re-executed on resume; failed stage FAILED with error | same: earlier rows identical (IDs, timestamps, counts), batch/raw/canonical/DQ row counts unchanged, Hop not called again | PASS |
| 2.3 | Resume gives attempt 2 with new execution ID, continues to PUBLISHED, run COMPLETED | same | PASS |
| 2.4a | Resuming a COMPLETED run is rejected (existing design) | same; API returns 409 | PASS |
| 2.4b | Two concurrent resumes cannot both execute | `twoConcurrentResumesOfOneRunCannotBothExecute`, `aStageAlreadyInFlightCannotBeExecutedAgainConcurrently` | PASS |
| 3.1 | API: start full and stage-by-stage, status, history, single stage, resume | `OnboardingControllerTest` (7 tests, full Spring stack) | PASS |
| 3.2 | API errors: unknown pack/state, MATCHED before MAPPED, resume of non-existent run | same: 404 / 409 / 404 with JSON `status`, `error`, `message` | PASS |
| 4.1 | Blank DB migrates V1-V9; lifecycle/orchestration integration tests (Testcontainers) | `DatabaseMigratorTest` (9 migrations, all tables), onboarding tests | PASS |
| 4.2 | Full Java regression | 61 run, 61 passed, 0 failed, 0 skipped (M9 baseline 44/44) | PASS |
| 4.3 | Python regression, at least 13 | 13 passed | PASS |
| 4.4 | 3-state e2e exactly 7 / 7 / 3 / 5 | `scripts/reset-and-run-e2e.ps1` output below | PASS |
| 4.5 | State 4 e2e exactly 9 / 9 / 6 / 6 | `scripts/reset-and-run-m10-e2e.ps1` output below | PASS |
| 5.1a | No ST4/state-4/state4 or state-code conditionals in production Java/Python | audit below: zero hits | PASS |
| 5.1b | Only `generic-file-ingest.hpl` under `hop/pipelines` | audit below | PASS |
| 5.1c | V1-V8 byte-identical to M9; only V9 added | audit below | PASS |
| 5.1d | Generic engines unchanged | audit below: no existing production file modified since M9, only additions | PASS |
| 5.1e | Guard passes and fails on a state-specific change | guard demonstration below | PASS |
| 5.2 | Documentation updated | files listed in scope | PASS |
| 5.3 | `git diff --check` clean | exit 0, working tree and against the M9 tag | PASS |

## Test counts

| Suite | Before M10 (tag `m9-data-quality-survivorship`) | After close-out |
| --- | --- | --- |
| Java (`mvn test`) | 44 run / 44 passed | 61 run / 61 passed / 0 failed / 0 skipped |
| Python (`pytest matching`) | 13 passed | 13 passed |

The baseline was measured by running `mvn test` on a clean `git archive` of the M9 tag.

New Java tests (17):

- `onboarding.OnboardingLifecycleTest` (2): lifecycle gates runs; DQ-stage failure and resume.
- `onboarding.OnboardingPersistenceTest` (1): full run, every stage asserted from the database.
- `onboarding.OnboardingFailureResumeTest` (4): MATCHED failure and resume; resume of COMPLETED rejected;
  two concurrent resumes; a stage in flight cannot run twice.
- `api.OnboardingControllerTest` (7): full start; stage-by-stage start; out-of-order stage; history; stage
  failure and resume via API; unknown pack, source and malformed start; unknown or malformed run references.
- `portability.M10PortabilityGuardTest` (3): generic code unchanged since M9; only the generic Hop pipeline;
  no state names in production code.

## End-to-end results (real Hop 2.19 and Splink, throwaway PostgreSQL 16)

3-state, `scripts/reset-and-run-e2e.ps1`:

```
E2E counts
  raw_record          7
  canonical_resident  7   (processing errors: 0)
  AUTO_MATCH pairs    3   (POSSIBLE_MATCH: 0)
  golden_resident     5   (run eb926db2-61c0-4b7f-acc8-ead4e1ec66e1, policy resident-survivorship-v3)
E2E PASSED: raw=7 canonical=7 golden=5 AUTO_MATCH=3
```

State 4, `scripts/reset-and-run-m10-e2e.ps1` (run separately):

```
Ingesting state-4/citizen-register with generic-file-ingest.hpl (batch c1968ddc-6b3e-46d2-b42f-d354c7954917) ...
Canonical batch c1968ddc-6b3e-46d2-b42f-d354c7954917: read=2 mapped=2 skipped=0 failed=0
E2E counts
  raw_record          9
  canonical_resident  9   (processing errors: 0)
  AUTO_MATCH pairs    6   (POSSIBLE_MATCH: 0)
  golden_resident     6   (run 81f04312-bf8c-4667-b2ae-7abad7fcf6d4, policy resident-survivorship-v3)
E2E PASSED: raw=9 canonical=9 golden=6 AUTO_MATCH=6
```

Why these numbers: State 4 adds one more "Ram Singh", so Ram Singh appears in all four states, which gives
C(4,2) = 6 AUTO_MATCH pairs collapsing into one Golden Resident; State 4's second record ("Neha Verma") is a
new person, so Golden goes from 5 to 6 and Raw/Canonical from 7 to 9.

## Audit

```
$ grep -rniE "ST4|state-4|state4" common-core/src/main matching
(exit 1: no hits)

$ grep -rniE "\bST[0-9]+\b|state-[0-9]+|state[0-9]+" common-core/src/main matching --exclude="test_*.py"
(exit 1: no hits)

$ grep -rnE "(stateCode|state_code|STATE_CODE)[^;]*(==|equals\(|switch|case )" common-core/src/main/java matching --include=*.java --include=*.py --exclude="test_*.py"
(exit 1: no hits)

$ ls hop/pipelines
generic-file-ingest.hpl

$ git diff --name-status m9-data-quality-survivorship -- common-core/src/main/resources/db/migration
A	common-core/src/main/resources/db/migration/V9__onboarding_orchestration.sql

$ git diff --name-status m9-data-quality-survivorship -- common-core/src/main matching hop
A	common-core/src/main/java/gov/uddp/core/api/OnboardingController.java
A	common-core/src/main/java/gov/uddp/core/api/OnboardingExceptionHandler.java
A	common-core/src/main/java/gov/uddp/core/onboarding/HopRunner.java
A	common-core/src/main/java/gov/uddp/core/onboarding/OnboardingConflictException.java
A	common-core/src/main/java/gov/uddp/core/onboarding/OnboardingNotFoundException.java
A	common-core/src/main/java/gov/uddp/core/onboarding/OnboardingService.java
A	common-core/src/main/java/gov/uddp/core/onboarding/OnboardingStage.java
A	common-core/src/main/java/gov/uddp/core/onboarding/OnboardingStageFailedException.java
A	common-core/src/main/java/gov/uddp/core/onboarding/ProcessHopRunner.java
A	common-core/src/main/java/gov/uddp/core/onboarding/PythonMatchingRunner.java
A	common-core/src/main/java/gov/uddp/core/onboarding/StatePackLifecycleService.java
A	common-core/src/main/resources/db/migration/V9__onboarding_orchestration.sql
```

The broader `ST[0-9]` pattern without the test exclusion matches only `matching/test_e2e_sources.py`, an
M8 test fixture (not production code). Every production change since M9 is a new file in the generic
`onboarding/` package or the API layer; no mapping, canonical, validation/DQ, matching, survivorship, State
Pack loader or Hop file was modified.

### Portability guard demonstration

Each temporary change was made, the guard run, and the change reverted:

```
== A: temporary state-specific class (onboarding/State4Mapper.java: return "ST4".equals(stateCode))
M10PortabilityGuardTest.productionCodeNeverNamesAConfiguredState ... but was: <[.../State4Mapper.java: state-specific file name, .../State4Mapper.java:5: names state 'ST4']>
Tests run: 3, Failures: 1
== B: temporary state-specific Hop pipeline (hop/pipelines/state-4-ingest.hpl)
M10PortabilityGuardTest.genericEnginesMatchingAndHopTemplateAreUnchangedSinceM9 New files in protected generic paths ... <[hop/pipelines/state-4-ingest.hpl]>
M10PortabilityGuardTest.theOnlyHopPipelineIsTheGenericOne ... but was: <[generic-file-ingest.hpl, state-4-ingest.hpl]>
Tests run: 3, Failures: 2
== C: temporary edit to a protected engine (golden/SurvivorshipEngine.java)
M10PortabilityGuardTest.genericEnginesMatchingAndHopTemplateAreUnchangedSinceM9 Protected generic code changed since m9-data-quality-survivorship ... <M	common-core/src/main/java/gov/uddp/core/golden/SurvivorshipEngine.java>
Tests run: 3, Failures: 1
== reverted: git status of production paths clean; guard green (3/3)
```

## Bugs found and fixed during close-out

1. **Run never reached COMPLETED.** The terminal run status was `PUBLISHED`; it is now `COMPLETED`, set in
   the transaction that completes the PUBLISHED stage (V9 check constraint updated; V9 was unreleased).
2. **Stage history order and identity.** Stages were listed by `started_at` only (ties possible) and the
   execution ID was not exposed; now ordered by start then attempt, with `executionId` in the run detail.
3. **A stage could execute twice concurrently.** `executeNext` read the run then inserted an attempt
   without a guard, so a second `next`/`resume` while a stage was running executed it again (proved by
   `aStageAlreadyInFlightCannotBeExecutedAgainConcurrently` failing before the fix). Fixed with a single
   conditional insert (run must be RUNNING at that stage), a unique index allowing one RUNNING execution per
   run, and an atomic `FAILED -> RUNNING` update for resume.
4. **Documented API missing; every error a bare 500.** Added full-run mode, run history per state and
   named-stage execution as thin service wrappers, plus `OnboardingExceptionHandler` (404/409/400/500).
5. **Unknown pack reported as "must be ACTIVE".** Starting a run now checks the pack exists first (404).
6. **Portability guard too narrow.** It covered three engine files and the Hop template and could not
   detect an added state-specific file; it now covers all generic engines, the matching module, the Hop
   pipeline set and state names in production code.
7. **E2E did not assert Raw.** Both e2e scripts now fail on a Raw count mismatch.

## Known limitations and items deferred to M11

- Onboarding is synchronous and in-process. If the process dies mid-stage, the stage row stays RUNNING and
  the run is blocked; stale-execution recovery belongs with M11 lifecycle work.
- Resume retries only the failed stage; the remaining stages run via `next`, named stages or a new FULL run.
- The API path with the real `ProcessHopRunner` and `PythonMatchingRunner` has no automated test: the Java
  tests replace those two process boundaries with what they write, and the e2e scripts drive Hop and Splink
  directly rather than through `/api/onboarding`. `ProcessHopRunner` invokes `hop-run.bat` (Windows only)
  and passes no Hop project name. An e2e that onboards through the API is deferred to M11.
- `MATCHED` reruns matching over all canonical Residents (not incremental); incremental processing is M11.
- `PUBLISHED` only marks the run complete.
- The concurrent-resume test cannot force the narrow read/update race deterministically; the guarantee
  rests on the atomic update and unique index, and the in-flight test proves the stage-claim guard.
- A developer database that already applied the pre-close-out V9 will fail Flyway validation (changed
  checksum); reset it or run `flyway repair`. Unit and e2e tests use disposable databases.

## Commits

```
2e0bcff M10 close-out Part 5: portability guard covers all generic engines and state names
94f2378 M10 close-out Part 4: e2e scripts assert the Raw count
2cea84f M10 close-out Part 3: onboarding API coverage and error contract
81571c5 M10 close-out Part 2: failure/resume tests and concurrency fix
02f4f27 M10 close-out Part 1: orchestration persistence test and fixes
7fc6128 Reformat OnboardingService to multi-line style (no behaviour change)
3924bf5 M10: onboarding orchestration and State 4 portability fixture (pre-close-out baseline)
```

plus the commit adding this report and the documentation updates.
