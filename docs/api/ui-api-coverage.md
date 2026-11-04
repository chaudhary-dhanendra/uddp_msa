# UI API coverage (M12 Part 1)

Audit of every HTTP endpoint in Common Core at the start of M12 (`51fbb08`), mapped to the M12 screens, and the
gaps M12 closes. The new endpoints are listed in the last section; their contract is the OpenAPI document at
`/v3/api-docs` (Swagger UI at `/swagger-ui.html`).

## Existing endpoints (before M12)

| # | Method and path | Response | Typed? | Paging/filter | Used by screen |
| --- | --- | --- | --- | --- | --- |
| 1 | `GET /health` | `{status, service}` | map | - | header (backend reachable) |
| 2 | `GET /api/state-packs` | `[StatePackSummary]` | record | none | State Pack viewer (list only) |
| 3 | `GET /api/state-packs/{state}` | `StatePackDetails`: sources, and only *counts* of mappings, crosswalks and rules | record | - | State Pack viewer (insufficient) |
| 4 | `POST /api/canonical/residents/process/batches/{batchId}` | `CanonicalProcessingSummary` | record | - | none (write; not exposed in UI) |
| 5 | `GET /api/dq/batches/{batchId}` | `DqBatchSummary`: counts, rule breakdown, 20 samples | record | none | DQ report (per batch only) |
| 6 | `POST /api/golden-residents/build` | `GoldenBuildSummary` | record | - | Match evidence: "Build Golden" action |
| 7 | `GET /api/golden-residents` | list of maps (payload, status, version) | map | run, policy, includeRetired; no paging/search | Golden list (insufficient) |
| 8 | `GET /api/golden-residents/{id}` | map: current version, members, provenance, versions | map | - | Golden 360 (untyped) |
| 9 | `GET /api/golden-residents/{id}/history` | map: every version and event | map | - | Golden 360 history (untyped) |
| 10 | `POST /api/onboarding/state-packs/{packId}/register` | `{statePackId}` | map | - | none (setup; not exposed in UI) |
| 11 | `POST /api/onboarding/state-packs/{packId}/{version}/transitions/{target}` | 204 | - | - | none (lifecycle editing is M13) |
| 12 | `POST /api/onboarding/runs` | `{runId, mode, processingMode, status}` | map | - | Onboarding runs: "Start run" |
| 13 | `GET /api/onboarding/runs/{runId}` | map with stages | map | - | Run detail (untyped, no durations) |
| 14 | `GET /api/onboarding/states/{state}/runs` | list of maps | map | per state only, no paging | Runs list (one state at a time) |
| 15 | `POST /api/onboarding/runs/{runId}/next` | 204 | - | - | Run detail: "Run next stage" |
| 16 | `POST /api/onboarding/runs/{runId}/stages/{stage}` | 204 | - | - | none |
| 17 | `POST /api/onboarding/runs/{runId}/resume` | 204 | - | - | Run detail: "Resume failed run" |

Error bodies: the onboarding controller returns `{status, error, message}`; the others return Spring's default error
body (`timestamp, status, error, path`) with no message.

## Coverage by screen

| Screen | What it needs | Before M12 | Gap |
| --- | --- | --- | --- |
| 1. Overview dashboard | counts per state (raw, canonical, DQ errors/warnings, golden, match decisions), canonical to golden reduction | nothing | **missing**: no aggregate endpoint |
| 2. Onboarding runs | all runs (any state) with paging; stage timeline with attempts, counts, durations, errors; start and resume | #12-#17, per-state history | **partial**: no cross-state list, no paging, no durations, untyped |
| 3. State Pack viewer | sources, mapping rules (source field, target, conversion, crosswalk), crosswalk values, validation, matching and survivorship rules, versions and lifecycle status | #2, #3 (counts only) | **missing**: rule content, crosswalk values, lifecycle status and versions |
| 4. Canonical explorer | list with state/batch/name filters and paging; detail with source vs canonical values, extensions, warnings, lineage to raw and batch, version history | nothing | **missing** |
| 5. Match evidence | runs list; pairs by decision and canonical ID; side-by-side field comparison with weight, probability, decision | nothing | **missing** |
| 6. Golden Resident 360 | search (name, mobile, district), state filter, paging; current values with provenance (winning source, rule, contributors); members linking to canonical; lineage to raw; history with version diff; retired-ID redirect | #7-#9 | **partial**: no search/paging, untyped maps, provenance lacks the winning record's state and source, no raw lineage |
| 7. DQ report | per batch and per rule severity counts, sample failing records | #5 (one batch, needs a batch ID) | **partial**: no batch list, no cross-batch rule view, no paged findings |
| Shared | consistent error body, OpenAPI for the generated client | none | **missing**: no springdoc, no common error body |

## M12 decisions for the API

- New read endpoints live under `/api/v1/` with typed response records (ADR-0007). Existing endpoints and their
  response bodies are unchanged; the UI uses them only for the allowed write actions (start run, next stage,
  resume, Golden build).
- Every list endpoint is paged the same way: `page` (0-based), `size` (1..200, default 20) and `sort`
  (`field,asc|desc` from a per-endpoint allow-list) return `Page<T>` = `{items, page, size, totalItems,
  totalPages, sort}`.
- Every `/api/v1` error is `ApiError` = `{status, error, message, path}`: 400 for bad paging, sort or IDs, 404 for
  unknown resources.
- State Packs are served through `StatePackQueryService` (ADR-0008) so M13's DB-backed registry can replace the
  file-backed implementation without changing controllers or the UI.

## New endpoints (M12 Part 2)

| Method and path | Response | Screen |
| --- | --- | --- |
| `GET /api/v1/overview` | `OverviewStats` | 1 |
| `GET /api/v1/states` | `[StatePackSummary]` | 3, filters everywhere |
| `GET /api/v1/states/{state}` | `StatePackDetail` | 3 |
| `GET /api/v1/batches?state=&page=&size=&sort=` | `Page<BatchSummary>` | 7, 4 (filter) |
| `GET /api/v1/batches/{batchId}` | `BatchDetail` (with DQ summary) | 7 |
| `GET /api/v1/dq/rules?state=&batchId=` | `[DqRuleSummary]` | 7 |
| `GET /api/v1/dq/findings?batchId=&ruleId=&severity=&state=&page=` | `Page<DqFinding>` | 7 |
| `GET /api/v1/canonical-residents?state=&batchId=&name=&includeSuperseded=&page=` | `Page<CanonicalResidentSummary>` | 4 |
| `GET /api/v1/canonical-residents/{id}` | `CanonicalResidentDetail` | 4, 6 (lineage) |
| `GET /api/v1/raw-records/{id}` | `RawRecordDetail` | 4, 6 (lineage) |
| `GET /api/v1/matching-runs?page=` | `Page<MatchingRunSummary>` | 5 |
| `GET /api/v1/matching-runs/{runId}` | `MatchingRunDetail` | 5 |
| `GET /api/v1/matching-runs/{runId}/pairs?decision=&canonicalId=&page=` | `Page<MatchPairSummary>` | 5 |
| `GET /api/v1/matching-runs/{runId}/pairs/{a}/{b}` | `MatchPairDetail` | 5 |
| `GET /api/v1/golden-residents?q=&mobile=&district=&state=&status=&page=` | `Page<GoldenResidentSummary>` | 6 |
| `GET /api/v1/golden-residents/{id}` | `GoldenResident360` (retired IDs: `redirectTo`) | 6 |
| `GET /api/v1/golden-residents/{id}/history` | `GoldenResidentHistory` | 6 |
| `GET /api/v1/onboarding-runs?state=&status=&page=` | `Page<OnboardingRunSummary>` | 2 |
| `GET /api/v1/onboarding-runs/{runId}` | `OnboardingRunDetail` | 2 |

Write actions stay on the existing endpoints #6, #12, #15 and #17. Their request bodies (`Start`, `BuildRequest`)
are already typed; their responses are documented in OpenAPI with response schemas that describe the existing
bodies, without changing them.

## M14 additions (Family domain)

| Endpoint | Response | Screen |
| --- | --- | --- |
| `GET /api/v1/families?state=&status=&q=&goldenResidentId=&page=&size=&sort=` | `Page<FamilySummary>` | Family list (no screen yet) |
| `GET /api/v1/families/{id}` | `GoldenFamilyDetail` (members with lineage, related households, versions) | Family 360 (no screen yet) |
| `GET /api/v1/families/{id}/history` | `GoldenFamilyHistory` | Family history (no screen yet) |
| `GET /api/v1/golden-residents/{id}/families` | `[FamilySummary]` | 6 (households panel) |

`openapi.json` and `src/api/schema.d.ts` were regenerated; no UI screen uses these yet (see docs/family-domain.md).

## M13 additions (stewardship)

| Endpoint | Response | Screen |
| --- | --- | --- |
| `GET /api/v1/stewardship/reviews?status=&page=&size=&sort=` | `Page<ReviewTaskSummary>` | Review queue (no screen yet) |
| `GET /api/v1/stewardship/reviews/{id}` | `ReviewTaskDetail` (both records, match evidence) | Review side by side (no screen yet) |
| `GET /api/v1/stewardship/decisions?goldenResidentId=&action=&page=` | `Page<DecisionView>` | Decision log (no screen yet) |
| `POST /api/stewardship/reviews/{id}/decision` | `DecisionResult` (with the Golden build) | write |
| `POST /api/stewardship/merge` | `DecisionResult` | write |
| `POST /api/stewardship/split` | `DecisionResult` | write |

See docs/stewardship.md. These are the UI's first data-changing endpoints beyond onboarding and Golden builds; the
M12 UI does not call them yet.

## M15 additions (Scheme and Benefit domains)

| Endpoint | Response | Screen |
| --- | --- | --- |
| `GET /api/v1/schemes` | `SchemeCatalogueView` (departments, schemes, groups, eligibility, benefit counts) | Scheme catalogue (no screen yet) |
| `GET /api/v1/benefits?state=&schemeId=&department=&status=&goldenResidentId=&page=&size=&sort=` | `Page<BenefitSummary>` | Benefit list (no screen yet) |
| `GET /api/v1/benefits/{id}` | `BenefitDetail` (beneficiaries, household, observations, disbursements) | Benefit detail (no screen yet) |
| `GET /api/v1/golden-residents/{id}/benefits` | `ResidentBenefits` | Golden Resident 360, benefits tab (no screen yet) |
| `GET /api/v1/reports/duplicate-benefits` | `DuplicateBenefitReport` | Duplicate-benefit report (no screen yet) |
| `GET /api/v1/reports/eligible-not-enrolled?schemeId=&asOf=` | `EligibleNotEnrolledReport` | Eligible-but-not-enrolled report (no screen yet) |

See docs/benefit-domain.md. `openapi.json` and `src/api/schema.d.ts` were regenerated; the screens wait for the M12
UI.
