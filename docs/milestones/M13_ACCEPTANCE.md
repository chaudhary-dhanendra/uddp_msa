# M13 acceptance: Stewardship and manual review

**Status: COMPLETE (backend and API).** Every acceptance item below passed on 2026-10-25 on
`feature/m13-stewardship` (branched from the M14 tag `m14-family-domain`). Nothing was pushed. There are no stewardship
screens: the M12 operational UI is unfinished, so M13 is delivered as REST APIs.

## Scope delivered

- **Review queue (V12).** Every Golden build creates one `stewardship_review_task` per POSSIBLE_MATCH source-record
  pair it has not seen before. Tasks are never updated; OPEN/DECIDED is derived from the decision log.
- **Decisions.** Accept/reject a task, merge two or more Golden Residents, split members off a Golden Resident.
  Each is one append-only `stewardship_decision` (reviewer, reason, Golden IDs, detail) with `MUST_LINK` /
  `CANNOT_LINK` constraints on source record keys. The latest decision on a pair is effective
  (`stewardship_link_constraint_effective`).
- **Applied by every Golden build.** `GoldenTargetGroups` separates `CANNOT_LINK` pairs, then joins `MUST_LINK`
  pairs unless that would join a `CANNOT_LINK` pair (skipped and counted). Carry-forward gives the usual
  `MERGED_INTO` / `SPLIT_FROM` history, and Golden Families follow. Each decision rebuilds Golden against the latest
  completed matching run and returns that build.
- **API.** `GET /api/v1/stewardship/reviews`, `/reviews/{id}` (side by side with match evidence), `/decisions`;
  `POST /api/stewardship/reviews/{id}/decision`, `/merge`, `/split`. Errors: 400, 404, 409. OpenAPI and the UI's
  generated types are regenerated.
- **E2E.** The 3-state script splits Ram Singh through REST (6 active) and merges him back (5 active, same Golden ID)
  with real Hop and Splink.
- **Docs.** `docs/stewardship.md`, ADR-0010, ADR-0005 update, API coverage, README, CLAUDE.md, golden-resident.md.

## Acceptance criteria

| # | Criterion | Evidence | Result |
| --- | --- | --- | --- |
| 1 | POSSIBLE_MATCH pairs become reviewable tasks, once per pair | `StewardshipTest.aPossibleMatchBecomesOneReviewTask...` (1 task after two builds); `StewardshipApiTest` queue | PASS |
| 2 | Accept merges the pair; the loser is RETIRED with MERGED_INTO and a MERGED event | same test: 5 → 4 active | PASS |
| 3 | A decision outlives later matching runs (POSSIBLE or NO_MATCH again) | same test: two more builds, still merged, constraintsInForce=1 | PASS |
| 4 | Reject keeps a pair apart even when a later run AUTO_MATCHes it | `aRejectedPairStaysApart...` | PASS |
| 5 | Manual split: larger side keeps the ID, other side gets a new ID with SPLIT_FROM; holds against re-clustering | `splitThenMerge...`; e2e smoke | PASS |
| 6 | Manual merge after a split: original ID survives, split ID retired MERGED_INTO, never reused; families follow | same test; e2e smoke (Ram Singh ID unchanged) | PASS |
| 7 | Latest decision per pair wins | `StewardshipMigrationTest`; split-then-merge leaves 0 effective CANNOT_LINK; API log shows the superseded constraint as not effective | PASS |
| 8 | Matching evidence and canonical rows are never changed | `aPossibleMatch...` snapshots before/after the decision | PASS |
| 9 | Invalid requests are rejected and record nothing | `invalidDecisions...` (blank reviewer/reason, bad decision, unknown task/ID, one ID, all members, non-member, retired ID); `errorContract` (400/404/409, bad JSON) | PASS |
| 10 | A decision needs a completed matching run | `aDecisionNeedsACompletedMatchingRun` (409) | PASS |
| 11 | Deterministic constraint application with conflicts counted | `GoldenTargetGroupsConstraintTest` (5) | PASS |
| 12 | Resident and Family behaviour unchanged; all earlier tests pass | 118 M14 tests pass; e2e counts unchanged | PASS |
| 13 | Migrations: V1–V11 byte-identical, V12 only added | `git diff --name-status m14-family-domain -- …/db/migration` → `A V12__stewardship.sql` | PASS |

## Test counts

| Suite | Before (M13 Part 0) | After |
| --- | --- | --- |
| Java (`mvn clean test`) | 118 passed | **132 passed**, 0 failed, 0 errors, 0 skipped |
| Python (`pytest matching`) | 16 passed | **16 passed** |

New: `StewardshipMigrationTest` (2), `GoldenTargetGroupsConstraintTest` (5), `StewardshipTest` (5),
`StewardshipApiTest` (2). Migration-count tests now expect V12 (same assertions).

## End to end (real Hop 2.19 and Splink, throwaway PostgreSQL 16)

```text
reset-and-run-e2e.ps1:              E2E PASSED: raw=7 canonical=7 golden=5 AUTO_MATCH=3 families=6
                                    stewardship split -> 6 active, merge -> 5 active (review tasks: 0)
                                    STEWARDSHIP E2E PASSED: Ram Singh Golden ID unchanged
reset-and-run-m10-e2e.ps1:          E2E PASSED: raw=9 canonical=9 golden=6 AUTO_MATCH=6 families=8
reset-and-run-incremental-e2e.ps1:  INCREMENTAL E2E PASSED: raw=9 canonical=9 golden=6 AUTO_MATCH=3 families=8
                                    Ram Singh Golden ID unchanged
```

Real Splink scores no sample pair as POSSIBLE_MATCH, so the e2e review queue is empty (it asserts tasks = POSSIBLE
pairs). The queue itself is proven by the fixture-run tests.

## Audit

```text
$ git diff --name-status m14-family-domain -- common-core/src/main/resources/db/migration
A       common-core/src/main/resources/db/migration/V12__stewardship.sql
$ ls hop/pipelines
generic-file-ingest.hpl
$ grep -rniE "\b(st[1-4]|state-?[1-4]|fx-household|fxh)\b" common-core/src/main matching/*.py
(no output)
$ git diff --check
(clean)
```

Engine changes listed in `M13_GENERIC_CHANGES` (portability guard 3/3):

- `GoldenTargetGroups`: an overload that applies link constraints after the unchanged automatic grouping; the old
  signature delegates with no constraints. Constraints are keyed by source record key, never by state.
- `StewardshipConstraints` (new interface): the build's hook for effective constraints and review-task creation.
- `GoldenResidentService` (already listed for M14): reads constraints, records `summary.stewardship`, creates tasks.

The stewardship package itself (`gov.uddp.core.stewardship`) is outside the protected paths.

## Known limitations and deferred items

- No screens (M12 UI unfinished); no roles, authentication or maker-checker (M17): the reviewer is a declared name.
- Household (Family) stewardship is not covered; households follow their members' Golden IDs.
- Constraints are evaluated over all current participants on each build (fine at MVP size).
- The `.sh` e2e has no stewardship smoke (the Windows script does).
- The portability guard still compares against `m11-golden-lifecycle` with M12, M14 and M13 lists; re-baseline on
  the next accepted milestone's tag.

## Commits

```text
20dd2d8 M13 Parts 0-1: baseline, stewardship design, ADR-0010 and V12 schema
22b6f5c M13 Part 2: stewardship constraints in the Golden build, review tasks and decisions
ddf25ab M13 Part 3: stewardship REST API
(this commit) M13 Part 4: e2e stewardship smoke, audit, docs and acceptance report
```
