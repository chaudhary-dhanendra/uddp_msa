/**
 * Names for the generated OpenAPI schemas (src/api/schema.d.ts). These are aliases only: every shape comes from the
 * backend's OpenAPI document, never from hand-written DTOs.
 */
import type { components } from "./schema";

type Schemas = components["schemas"];

export type ApiError = Schemas["ApiError"];
export type OverviewStats = Schemas["OverviewStats"];
export type StateStats = Schemas["StateStats"];
export type DecisionCounts = Schemas["DecisionCounts"];

export type StatePackListItem = Schemas["StatePackListItem"];
export type StatePackView = Schemas["StatePackView"];
export type MappingView = Schemas["MappingView"];
export type CrosswalkView = Schemas["CrosswalkView"];
export type ValidationRuleView = Schemas["ValidationRuleView"];
export type PackVersionView = Schemas["PackVersionView"];

export type BatchSummary = Schemas["BatchSummary"];
export type BatchDetail = Schemas["BatchDetail"];
export type DqRuleSummary = Schemas["DqRuleSummary"];
export type DqFinding = Schemas["DqFinding"];
export type PageBatchSummary = Schemas["PageBatchSummary"];
export type PageDqFinding = Schemas["PageDqFinding"];

export type CanonicalResidentSummary = Schemas["CanonicalResidentSummary"];
export type CanonicalResidentDetail = Schemas["CanonicalResidentDetail"];
export type FieldMapping = Schemas["FieldMapping"];
export type RawRecordDetail = Schemas["RawRecordDetail"];
export type PageCanonicalResidentSummary =
  Schemas["PageCanonicalResidentSummary"];

export type MatchingRunSummary = Schemas["MatchingRunSummary"];
export type MatchingRunDetail = Schemas["MatchingRunDetail"];
export type MatchPairSummary = Schemas["MatchPairSummary"];
export type MatchPairDetail = Schemas["MatchPairDetail"];
export type FieldComparison = Schemas["FieldComparison"];
export type CanonicalBrief = Schemas["CanonicalBrief"];
export type PageMatchingRunSummary = Schemas["PageMatchingRunSummary"];
export type PageMatchPairSummary = Schemas["PageMatchPairSummary"];

export type GoldenResidentSummary = Schemas["GoldenResidentSummary"];
export type GoldenResident360 = Schemas["GoldenResident360"];
export type GoldenFieldView = Schemas["GoldenFieldView"];
export type GoldenMemberView = Schemas["GoldenMemberView"];
export type GoldenResidentHistory = Schemas["GoldenResidentHistory"];
export type GoldenVersionView = Schemas["GoldenVersionView"];
export type GoldenEventView = Schemas["GoldenEventView"];
export type PageGoldenResidentSummary = Schemas["PageGoldenResidentSummary"];

export type OnboardingRunSummary = Schemas["OnboardingRunSummary"];
export type OnboardingRunDetail = Schemas["OnboardingRunDetail"];
export type StageTimelineEntry = Schemas["StageTimelineEntry"];
export type StageExecutionView = Schemas["StageExecutionView"];
export type PageOnboardingRunSummary = Schemas["PageOnboardingRunSummary"];
export type StartRunRequest = Schemas["Start"];
export type StartRunResponse = Schemas["StartRunResponse"];
export type GoldenBuildSummary = Schemas["GoldenBuildSummary"];
export type AuditEvent = Schemas["AuditEventView"];

/** The paging fields every list response shares. */
export type PageMeta = Pick<
  PageBatchSummary,
  "page" | "size" | "totalItems" | "totalPages" | "sort"
>;

// Platform database setup (Setup and Settings screens)
export type SetupStatus = Schemas["Status"];
export type MaskedSettings = Schemas["MaskedSettings"];
export type ConnectionRequest = Schemas["ConnectionRequest"];
export type ConnectionTest = Schemas["ConnectionTest"];

// Onboarding Studio
export type StudioStatus = Schemas["StudioStatus"];
export type StudioDraft = Schemas["Draft"];
export type DraftDetails = Schemas["DraftDetails"];
export type DraftConfiguration = Schemas["DraftConfiguration"];
export type DraftSource = Schemas["Source"];
export type StudioMappingRule = Schemas["MappingRule"];
export type StudioCrosswalk = Schemas["Crosswalk"];
export type StudioCrosswalkEntry = Schemas["CrosswalkEntry"];
export type StudioRules = Schemas["Rules"];
export type StudioValidationRule = Schemas["ValidationRule"];
export type StudioMatching = Schemas["Matching"];
export type SourceProfile = Schemas["Profile"];
export type ColumnProfile = Schemas["ColumnProfile"];
export type StudioSuggestions = Schemas["Suggestions"];
export type ColumnSuggestion = Schemas["ColumnSuggestion"];
export type TargetSuggestion = Schemas["TargetSuggestion"];
export type MappingTargets = Schemas["MappingTargets"];
export type MappingTarget = Schemas["MappingTarget"];
export type CodeValue = Schemas["CodeValue"];
export type StudioPreview = Schemas["Preview"];
export type PreviewRow = Schemas["PreviewRow"];
export type GeneratedPack = Schemas["GeneratedPack"];
export type StudioStateEntry = Schemas["StateEntry"];
export type PortabilityEvidence = Schemas["PortabilityEvidence"];
export type StateEvidence = Schemas["StateEvidence"];
export type SourceConnection = Schemas["SourceConnection"];
export type SourceConnectionRequest = Schemas["SourceConnectionRequest"];
export type StudioApproval = NonNullable<Schemas["Approval"]>;
export type StewardshipProposal = NonNullable<Schemas["Proposal"]>;
export type StewardshipProposalRequest = Schemas["ProposalRequest"];
export type ReviewTaskSummary = Schemas["ReviewTaskSummary"];

// Authentication and the signed-in user
export type CurrentUser = Schemas["Me"];
export type Permission = CurrentUser["permissions"][number];
export type AuthProviders = Schemas["Providers"];

// User, role and state-scope management (P1 Part 6)
export type UserView = Schemas["UserView"];
export type UserWithPassword = Schemas["UserWithPassword"];
export type RoleMatrix = Schemas["RoleMatrix"];
export type RoleDefinition = Schemas["RoleDefinition"];

// Detail pages (canonical record, matching run, benefit)
export type SourceField = Schemas["SourceField"];
export type CanonicalVersionView = Schemas["CanonicalVersionView"];
export type ValidationWarningView = Schemas["ValidationWarningView"];
export type RawRecordRef = Schemas["RawRecordRef"];
export type PairCounts = Schemas["PairCounts"];
export type ClusterView = Schemas["ClusterView"];
export type BenefitDetail = Schemas["BenefitDetail"];
export type BenefitSummary = Schemas["BenefitSummary"];
export type BenefitBeneficiary = Schemas["BenefitBeneficiary"];
export type DisbursementView = Schemas["DisbursementView"];
export type BenefitObservationView = Schemas["BenefitObservationView"];

// Departments and their Source Packs (R1)
export type DepartmentWithSources = Schemas["DepartmentWithSources"];
export type SourcePack = Schemas["SourcePack"];
export type StateProfile = Schemas["StateProfile"];

// Matching Studio (Track B)
export type MatchingProfile = Schemas["MatchingProfile"];
export type MatchingProfileRequest = Schemas["MatchingProfileRequest"];
export type MatchingTrainingJob = Schemas["MatchingTrainingJob"];
export type MatchingSourceReadiness = Schemas["MatchingSourceReadiness"];
export type MatchingSamplePair = Schemas["MatchingSamplePair"];
export type MatchingLabelSummary = Schemas["MatchingLabelSummary"];
export type MatchingImpact = Schemas["MatchingImpact"];
export type MatchingChangedPair = Schemas["MatchingChangedPair"];
export type MatchingExplanation = Schemas["MatchingExplanation"];
export type MatchingExplanationStep = Schemas["MatchingExplanationStep"];
export type MatchingRunMetrics = Schemas["MatchingRunMetrics"];
export type MatchingAlert = Schemas["MatchingAlert"];
export type MatchingBound = Schemas["MatchingBound"];
export type MatchingProposal = Schemas["MatchingProposal"];

export type AiDraftStatus = Schemas["AiDraftStatus"];
export type AiRuleDraft = Schemas["AiRuleDraft"];
export type RuleSetSummary = Schemas["RuleSetSummary"];
export type RuleSetVersion = Schemas["RuleSet"];
export type RuleField = Schemas["RuleField"];
export type RuleTestCase = Schemas["TestCase"];
export type RuleEvaluation = Schemas["Evaluation"];
export type DecisionRow = Schemas["DecisionRow"];
export type RuleTableDraft = Schemas["RuleTableDraft"];
// Scheme Studio wizard (A7)
export type SchemePackVersion = Schemas["SchemePackVersion"];
export type SchemePackDraft = Schemas["SchemePackDraft"];
export type SchemePackDetails = Schemas["SchemePackDetails"];
export type SchemeCodeCrosswalk = Schemas["SchemeCodeCrosswalk"];
export type SchemeDataAvailability = Schemas["DataAvailability"];
export type SchemeImpact = Schemas["Impact"];
export type SchemeImpactCounts = Schemas["Counts"];

export type IdentityPolicy = Schemas["IdentityPolicy"];
export type IdentityCoverage = Schemas["IdentityCoverage"];
export type IdentityRecommendation = Schemas["IdentityRecommendation"];
export type IdentityPreview = Schemas["IdentityPreview"];
export type IdentityPolicyVersion = Schemas["IdentityPolicyVersion"];

// Geography master upload (LGD snapshot check and load)
export type GeographyPreview = Schemas["SnapshotPreview"];
export type GeographyChange = Schemas["SnapshotChange"];
export type GeographySyncRun = Schemas["SyncRun"];
