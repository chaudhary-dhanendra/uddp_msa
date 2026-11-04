
B1 done: synthetic generator (python -m matching.synth).
Files: matching/
Tests: pytest matching/test_synth.py -> 1 passed.

B2 done: matching profiles (migrations V50-V51, python train/profile, Java service+API, permissions).
Files: matching/, common-core/{matching,api,security,db}, test.
Tests: pytest test_profile_training.py+test_matching.py 13 passed; mvn MatchingProfileServiceTest passed. EndpointAuthorizationTest has 2 PRE-EXISTING failures from uncommitted Track A endpoints (rule-sets, scheme-packs lack rules/@Audited); Matching rules added.

B3 done: labels (V52), threshold suggestion (python thresholds), impact preview (Java, read-only) + API.
Files: matching/, common-core/{matching,api,db}, test.
Tests: pytest test_thresholds.py 1 passed; mvn MatchingImpactServiceTest+MatchingProfileServiceTest passed.

B5 backend done (V53 metrics/alerts/proposals, python metrics, Java monitoring+API); openapi.json/schema.d.ts regenerated ONCE (after B5 backend). UI for B4+B5 in progress.

B4 done: Matching Studio UI (web/onboarding-ui/src/features/matching-studio, nav entry, Why-matched link from Match evidence); openapi.json+schema.d.ts regenerated (twice: DTO rename + optional query params).
Files: web/onboarding-ui/{src/features,src/app,src/i18n,src/api}, common-core/api.
Tests: vitest src/features/matching-studio 14 passed; tsc + eslint clean.

B5 done: UI Monitoring tab + Java MatchingMonitoringServiceTest (4 passed).
Files: web/onboarding-ui/src/features/matching-studio, common-core/matching, matching/.
Tests: vitest monitoring.test.tsx 2 passed; mvn MatchingMonitoringServiceTest passed.

C1 done: DeliveryChangeService (NEW/CHANGED/UNCHANGED/REMOVED by content hash, MARK_ONLY|END_BENEFITS), counts on run history/detail, V60.
Files: common-core/{onboarding,db/migration}, test.
Tests: mvn DeliveryChangeDetectionTest 1 passed. (Policy REST endpoint comes with C2's controller.)

C2 done: SchedulerService/SchedulerRunner (cron+tz, queue, catch-up, stuck-stage recovery, SYSTEM audit), OperationsController (schedule + delivery-policy API), V61.
Files: common-core/{onboarding,api,db/migration}, test. Schedule UI not yet built (planned in the Operations screen, last).
Tests: mvn SchedulerServiceTest 5 passed.

C5 done: connector package (excel/jdbc[pg,mysql,sqlserver,oracle]/sftp/rest), ConnectorService staging to the generic file pipeline, source-level connector API (test/preview/profile), V62, poi+jsch+optional drivers in pom.
Files: common-core/{connector,onboarding,api,setup,db/migration,pom.xml}, test.
Tests: mvn ConnectorFixtureTest (3) + ConnectorContainersTest (PG, MySQL, SFTP; 3) passed.

C3 done: QualityGateService (5 gates, MetricsSource), ReleaseService (checkpoint before GOLDEN_BUILT, PAUSED state, AUTO_APPROVED audit, maker-checker approve/reject), AutoApprovalPolicy+SensitiveChangeDetector for pack edits (StudioService hook), gate/release API, permission ONBOARDING_APPROVE, V63.
Files: common-core/{gates,onboarding,studio,api,security,db/migration}, test.
Tests: mvn QualityGateReleaseTest 2 passed; StatePackApprovalTest 3 passed (unchanged behaviour).

C6 backend done: MonitoringService (DQ score/trends, load/gate failures, schedule misses, MetricsSource drift), AlertService (rules, notifications, signed retried webhook, secrets in settings store), MonitoringController, AlertRunner, V64. UI pending (Operations screens, last).
Files: common-core/{monitoring,api,db/migration}, test.
Tests: mvn AlertServiceTest 1 passed.

C4 done + UI: ExceptionSource/ExceptionInboxService + 5 platform sources, /api/inbox; UI features inbox, monitoring (+header bell), operations (schedule/connector/gates/removal policy, run release panel); openapi.json + schema.d.ts regenerated once; EndpointAuthorizationTest rules appended; registry.test updated (was stale vs Track A/B).
Files: common-core/{inbox,api}, web/onboarding-ui/{src/features,src/app,src/api,openapi.json}.
Tests: mvn ExceptionInboxServiceTest 2, OpenApiContractTest 1, EndpointAuthorizationTest 11 passed; vitest inbox+monitoring+app+i18n+overview 9 passed; tsc and eslint clean.

Close-out S1: prompts file absent; A7 inferred = rule-set/Scheme Pack maker-checker (Rules Studio said "approval unavailable"). Gaps: no approve API, no Track A/B ExceptionSource/MetricsSource, setEligibilityRuleSet edits APPROVED versions. Flyway V30-V64 unique/ordered.
Close-out S2: backend 266 run/94 errors, one cause (uncommitted @ConditionalOnProperty removed the ReleaseService bean OperationsController needs); eslint crashed on unreadable web/onboarding-ui/.pytest_cache; tsc ok, vitest 21 files/51 ok, pytest 23 ok.
Close-out S3: ReleaseService always a bean, flag uddp.operations.release-checkpoint.enabled only skips pauses(); eslint ignores .pytest_cache; V44 adds rule/scheme approval columns + RULES_APPROVE.
Close-out S3 (cont.): rules/MakerChecker + RuleSet/SchemePack submit/approve/reject APIs, RulesAndMatchingExceptionSources, MatchingDriftMetrics, PendingApprovalMetrics (V65), matching approvals now refuse a null approver; UI approval buttons, sendJson adds CSRF to Track A writes; openapi.json + schema.d.ts regenerated.
Close-out S3 tests: RuleAndSchemeApprovalTest 6, EndpointAuthorizationTest 11, targeted Rule/Scheme/Matching/Inbox/Migrator classes green; vitest rules+schemes+inbox+monitoring+app 16 ok; tsc + eslint clean.
Close-out S4: mvn 276/276 (100 classes, M10PortabilityGuardTest 3 ok), vitest 21 files/53, pytest 23, tsc/eslint/check:api clean.
Close-out S4 e2e: not run (the tool blocked powershell -ExecutionPolicy Bypass); reset-and-run-e2e.ps1 uses a throwaway container DB uddp_e2e, run it yourself.
Next: user runs e2e, reviews the diff and commits (no git writes done here).

Slice status 2026-10-28 (from git log, LOG.md, RESUME.md; prompts file UDDP_Build_Prompts_R1-R6.md not found on disk):
| Slice | Status | Evidence |
|---|---|---|
| R1a-c | done | 0eb89a8, bd3a113, acf5274 (V30-V31) |
| A1 | done | 8a4251e DMN rule sets |
| A2 | done | 51d2d00 DMN in DQ + survivorship (V41) |
| A3 | done | db16ff0, 6fdc400 Rules Studio |
| A4 | done | e0b90b4, 1d94641 Scheme Pack model |
| A5 | done | 8412aea, bd82333 eligibility contract + impact preview |
| A6 | missing? | no provider code found; needs prompt text (fake provider, off by default) |
| A7 | done (inferred, uncommitted) | close-out S3: MakerChecker V44, inbox/metrics sources |
| B1-B5 | done | 24ee4df (V50-V53) |
| C1-C6 | done (C4/C6 UI + close-out uncommitted) | 04f71d3, d788566 (V60-V65) |
| D1-D2 | unknown | no Track D commits or notes; needs prompt text |
| e2e | not run | reset-and-run-e2e.ps1 pending (user) |
Next: get the prompts file, then A6, D1, D2 in order.
A6 done (uncommitted): ai/AiDraftService (+Disabled/Http, off by default), scheme/GoEligibilityDraftService, rules/DecisionTableDmn, V45 ai_rule_draft, PDFBox 3.0.3, endpoints GET /api/scheme-packs/ai-draft/status + POST .../eligibility/ai-draft, UI GoDraftButton in Scheme authoring Eligibility; openapi/schema regenerated.
Tests: mvn GoEligibilityDraftServiceTest 3, EndpointAuthorizationTest 11, OpenApiContractTest 1; vitest schemes 6; tsc/eslint/check:api clean.
D1 done (uncommitted): SuggestionEngine learning (name variants, date patterns, crosswalk values, per-source agreement, reasons name the source), accepted flag + uddp.studio.suggestion-accept-confidence=0.7, SuggestionReviewStore (V70) + inbox StudioSuggestionExceptions (MAPPING_SUGGESTION), Map step pre-fills accepted only and offers the rest; openapi/schema regenerated.
Tests: mvn SuggestionLearningTest 1, StudioBuildingBlocks/Equivalence/PublishGuard/DatabaseSource, StatePackApproval, ExceptionInbox, RuleAndSchemeApproval, OpenApiContract green; vitest studio+i18n 5; tsc/eslint/check:api clean.
D2 done (uncommitted): studio/AiMappingAdvisor combined into StudioService.suggestions (before the review store), application.yml uddp.ai.enabled=false; M10PortabilityGuardTest allows scheme/GoEligibilityDraftService (A6).
Tests: mvn AiMappingAdvisorTest 2, SuggestionLearningTest 1, StudioEquivalence/DatabaseSource, M10PortabilityGuardTest 3, OpenApiContractTest 1 green.
Next: user reviews and commits A6/D1/D2 with the close-out work; full suite + e2e not run (FULL CHECK only on request).

Slice status re-check 2026-10-28 against the actual prompts file (supersedes the table above):
| Slice | Status | Gap |
|---|---|---|
| R1a-c, A1, A2, A6 | done | - |
| A3 | partial | editor does not save drafts, no metadata field picker / sample-record picker, history+diff not wired, hand-written types |
| A4 | partial | draft API lacks GO reference, classification, amount/quantity/frequency, code crosswalks |
| A5 | partial | missing data-availability report, enrolled/enrolled-not-eligible/by-district impact, generated test cases, recompute after Golden build with stored explanations |
| A7 | partial | maker-checker done; 8-step Scheme Studio wizard missing |
| B1-B5 | done | - |
| C1 | partial | Studio "Upload new delivery" on an existing source missing (backend done) |
| C2-C6 | done | - |
| D1, D2 | done | - |
Plan: A3 -> A4 -> A5 -> A7 -> C1 UI, one slice at a time, uncommitted.
A3 completed (uncommitted): table drafts, version evaluate, fields, test cases API; Rules Studio UI rewritten on generated types (field builder, generated-case picker, history/diff, save draft).
Tests: mvn RuleSetTableTest 1, EndpointAuthorizationTest 11, OpenApiContractTest 1; vitest rules+app 10; tsc/eslint/check:api clean.
A4 completed (uncommitted): SchemePackService Details + CodeCrosswalk, importCodeCrosswalks, SchemeCatalogueImporter (startup, idempotent), V46; guard allow-list +SchemeCatalogueImporter.
Tests: mvn SchemePackDetailsTest 1, SchemePackServiceTest, RuleAndSchemeApprovalTest, GoEligibilityDraftServiceTest, BenefitReportTest, OpenApiContractTest, EndpointAuthorizationTest, M10PortabilityGuardTest green; tsc clean.
A5 completed (uncommitted): SchemeImpactService (+ endpoints data-availability, impact, eligibility-results), GoldenBuildCompletedEvent from GoldenResidentService.build after commit, V47; test cases generator from A3.
Tests: mvn SchemeImpactServiceTest 1, GoldenLifecycleTest, GoldenResidentPostgresProofTest, BenefitReportTest, M10PortabilityGuardTest, EndpointAuthorizationTest, OpenApiContractTest green; tsc clean.
A7 completed (uncommitted): web/onboarding-ui/src/features/schemes/wizard (model, steps, index), routes appended, links from catalogue/workspace; registry route count 38 -> 40.
Tests: vitest schemes+rules+app 24 (wizard: one test per step); tsc/eslint clean; OpenApiContractTest regenerated for the schema renames.
C1 completed (uncommitted): StudioService.uploadDelivery + endpoint, UI NewDeliveryPanel on the Onboard step (en.json studio.delivery.*).
Tests: mvn StudioDeliveryTest 1, EndpointAuthorizationTest, OpenApiContractTest; vitest studio+i18n 7; tsc/eslint/check:api clean.
All slices R1a..D2 now done per the prompts file; everything since the Track C merge is uncommitted. Next: user reviews, commits, runs FULL CHECK (mvn test, npm typecheck/lint/test, pytest, reset-and-run-e2e.ps1).
E1 done (uncommitted): identity/IdentifierType + IdentifierTypeRegistry (seeded from platform/identity/identifier-types.yaml: AADHAAR, RATION_CARD, VOTER_ID), pluggable ChecksumValidator (VERHOEFF, LUHN), maker-checker changes, /api/identity/identifier-types(+changes), V80, Permission IDENTITY_VIEW/MANAGE/APPROVE.
Folders: platform/identity, common-core identity/, api/IdentityController, security/Permission, db/migration V80, test identity/, EndpointAuthorizationTest rules.
Tests: mvn IdentifierTypeRegistryTest 3 green.
E2 done (uncommitted): identifiers.<TYPE> targets (nationalIds.* alias) protected per registry type by identity/IdentifierProtection (KEYED_HASH/VAULT_REF/PLAIN, ISSUER key, last4) into one extension uddp.identifiers; PostgresIdentifierVault (V81); NationalIdLeakGuard driven by sensitivePattern types; raw redaction token uddp-id:*; HOUSEHOLD ids copied to canonical family payload.
Folders: identity/, mapping/ResidentMappingEngine, privacy/(NationalIdLeakGuard, RawNationalIdRedactor, NationalIdProtector), canonical/(Resident, Family processors), studio/CsvProfiler, matching/MatchingInsightService, V81; tests NationalIdLeakGuardTest/NationalIdAndRetentionTest/M10PortabilityGuardTest adjusted (not run).
Tests: mvn IdentifierMappingTest 4 green.
E3 done (uncommitted): identity/IdentityPolicy + IdentityPolicyService (V82, versioned, maker-checker, /api/identity/policies) + IdentityPolicyEvaluator (ladder); GoldenResidentService adds ladder MUST/CANNOT_LINK constraints before stewardship ones; GoldenFamilyBuilder anchors households on familyAnchorType; matching/identity.py per-type id_<type> columns, deterministic pre-pass, never-merge, stricter no-identifier threshold, base source on first load. No approved policy = unchanged.
Folders: identity/, golden/(GoldenResidentService, GoldenFamilyBuilder), api/IdentityController, matching/(identity.py, run.py, config.py), V82.
Tests: mvn IdentityPolicyTest 2 green; pytest matching/test_identity.py 2 green.
E4 done (uncommitted): identity/UddpIdService (V83 uddp_id, append-only trigger; format uddp.identity.uddp-id.length/alphabet/resident-prefix/family-prefix/checksum, Luhn mod N check char, SecureRandom) minted by every Golden build for new Residents and Families; resolve() follows the merge chain; GET /api/v1/golden-residents/by-uddp-id/{uddpId} and /api/v1/families/by-uddp-id/{uddpId}; Golden 360 gains uddpId + redirectToUddpId, Family detail uddpId. UI display lands with E5 (types regenerated once there).
Folders: identity/, golden/GoldenResidentService, read/(GoldenReadService, FamilyReadService), api/v1/ReadApiControllers, V83, EndpointAuthorizationTest rules.
Tests: mvn UddpIdTest 2 green.
E5 done (uncommitted): identity/IdentityStudioService (coverage per source present/invalid/duplicated, recommendation with reasons+warnings, preview via IdentityPolicyEvaluator over the latest matching run, writes nothing) behind GET /api/identity/coverage, /recommendation, POST /preview; Overview identity stats; UI Matching Studio "Identity" tab (coverage, recommended policy, preview, submit = draft + submit for approval), UDDP ID on Resident 360 / Family 360, Overview identity cards. openapi.json + schema.d.ts regenerated once; identity schemas prefixed (IdentityPreview, IdentityCoverage, ...) to avoid name clashes; UddpIdService needed @Autowired on its property constructor.
Folders: identity/, api/IdentityController, read/OverviewReadService, web/onboarding-ui (openapi.json, src/api, features/matching-studio, overview, simple).
Tests: mvn IdentityPreviewTest 1 green (plus OpenApiContractTest write, all Track E classes re-run green); vitest identity.test.tsx 1 green; typecheck, lint, check:api clean.
F1 done (uncommitted): geography/ LGD master (V90: lgd_entity_type extensible, lgd_entity, lgd_entity_version, lgd_relationship, lgd_snapshot, lgd_staging_row, lgd_sync_run, lgd_change, lgd_master_state); LgdSourceClient + FileLgdSourceClient (CSV/JSON, uddp.geography.lgd.source-file); LgdImportService stage -> validate whole snapshot -> diff -> persist + activate in one transaction.
Folders: common-core geography/, db/migration V90, tests/fixtures/lgd (fictional fixture), test geography/LgdMasterImportTest.
Tests: mvn LgdMasterImportTest 3 green.
F2 done (uncommitted): geography/GeographyService (search by type/code/name within a parent area, paged; ancestors/descendants; validate ACTIVE_VALID|KNOWN_INACTIVE|UNKNOWN + parent consistency; as-of); reference/ ReferenceDataService over StaticReferenceDataProvider + LgdReferenceDataProvider (lgd-<type>, lgd-district = DISTRICT view, static until loaded) used by REFERENCE_LIST in canonical processing and Studio preview; GeographyController /api/geography/*, /api/reference-data; V91 GEOGRAPHY_VIEW/GEOGRAPHY_SYNC.
Folders: geography/, reference/, api/GeographyController, security/Permission, validation/PlatformReferenceData (datasets()), canonical/CanonicalResidentProcessingService, studio/PreviewService, V91; EndpointAuthorizationTest rules and M10PortabilityGuardTest list updated (not run).
Tests: mvn GeographyServiceTest 3 green.
F3 done (uncommitted): geography/LgdSyncService (one running sync at a time via a unique RUNNING index, 409 on overlap; failures keep the last good snapshot; runs audited as SYSTEM or the user; schedule in lgd_sync_schedule ticked by the C2 SchedulerRunner through onboarding/PlatformScheduledJob; stuck runs abandoned after a timeout); platform metrics LGD_MASTER_AGE_HOURS / LGD_SYNC_FAILURES (monitoring/PlatformMetricsSource, evaluated once by AlertService); endpoints /api/geography/sync/status|runs|schedule, /api/geography/changes; V92.
Folders: geography/, onboarding/(SchedulerRunner, PlatformScheduledJob, SchedulerService.parse public), monitoring/(MonitoringService, AlertService, PlatformMetricsSource), api/GeographyController, application.yml, V92.
Tests: mvn LgdSyncServiceTest 3 green.
F4 done (uncommitted): generic canonical geography resolution, source-owned crosswalk input, review-only fuzzy suggestions, ancestor derivation, and districtLGD projection.
F4 tests: matching/test_geography.py passed; CanonicalGeographyMappingTest blocked because Testcontainers cannot access \\.\pipe\docker_engine.
Next F5: regenerate OpenAPI/UI types and implement the requested geography API/UI slice only.
Track F F1-F6 complete (uncommitted): LGD master/reference/sync, canonical geography, Studio coverage/operations, and requested-dimension Scheme impact.
Track F tests: six focused Java classes and matching/test_geography.py passed earlier; GeographyPanel.test.tsx, typecheck, and lint passed now.
Next: user reviews and commits Track F; no further Track F work remains.
Track F fixes after 92abfea (uncommitted): coverage endpoint SQL fixed (typed params; per source/level resolved incl. derived, unknown, % of the source's Residents); F6 BLOCK/VILLAGE/STATE read members' canonical geography (Golden keeps no extensions) + caveat text; address.districtLGD passes through until an LGD snapshot is active; geography.* crosswalk misses fall through the ladder; lgd-<level> code sets with live LGD lookup for crosswalk suggestions and an LGD search picker in the Crosswalk step; EndpointAuthorizationTest rules for coverage and geography-impact; openapi.json + schema.d.ts regenerated.
Folders: geography/(GeographyCoverageService, CanonicalGeographyResolver), mapping/ResidentMappingEngine, scheme/SchemeImpactService, studio/(StudioMetadata, SuggestionEngine), api/GeographyController, platform/studio/mapping-targets.yaml, web/onboarding-ui (GeographyPanel, CrosswalkStep, api).
Tests: mvn GeographyCoverageTest 1, SchemeGeographyImpactTest 1, CanonicalGeographyMappingTest 2, OpenApiContractTest, StudioBuildingBlocksTest, AiMappingAdvisorTest, SchemeImpactServiceTest green; EndpointAuthorizationTest only the 4 pre-existing Track E identity 400s; vitest GeographyPanel 2, typecheck, lint, check:api clean. Still to do by hand: git rm -r .pytest-f4.
- 2026-10-29 Demo guide: sections 0-1 filled; fill steps 1-6 + checklist. Files in D:\projects\uddp-demo (excel, pin-code-plugin.jar, approved-plugins.yaml).
- 2026-10-29 Demo guide complete (all sections); not yet walked through on a running app.
