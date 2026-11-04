package gov.uddp.core.api.v1;

import gov.uddp.core.privacy.MaskPii;
import gov.uddp.core.read.BatchReadService.BatchDetail;
import gov.uddp.core.read.BatchReadService.BatchSummary;
import gov.uddp.core.read.BatchReadService.DqFinding;
import gov.uddp.core.read.BatchReadService.DqRuleSummary;
import gov.uddp.core.read.BatchReadService;
import gov.uddp.core.read.CanonicalReadService.CanonicalResidentDetail;
import gov.uddp.core.read.CanonicalReadService.CanonicalResidentSummary;
import gov.uddp.core.read.CanonicalReadService.RawRecordDetail;
import gov.uddp.core.read.CanonicalReadService;
import gov.uddp.core.read.FamilyReadService.FamilySummary;
import gov.uddp.core.read.FamilyReadService.GoldenFamilyDetail;
import gov.uddp.core.read.FamilyReadService.GoldenFamilyHistory;
import gov.uddp.core.read.FamilyReadService;
import gov.uddp.core.read.GoldenReadService.GoldenResident360;
import gov.uddp.core.read.GoldenReadService.GoldenResidentHistory;
import gov.uddp.core.read.GoldenReadService.GoldenResidentSummary;
import gov.uddp.core.read.GoldenReadService;
import gov.uddp.core.read.MatchingReadService.MatchPairDetail;
import gov.uddp.core.read.MatchingReadService.MatchPairSummary;
import gov.uddp.core.read.MatchingReadService.MatchingRunDetail;
import gov.uddp.core.read.MatchingReadService.MatchingRunSummary;
import gov.uddp.core.read.MatchingReadService;
import gov.uddp.core.read.OnboardingReadService.OnboardingRunDetail;
import gov.uddp.core.read.OnboardingReadService.OnboardingRunSummary;
import gov.uddp.core.read.OnboardingReadService;
import gov.uddp.core.read.OverviewReadService.OverviewStats;
import gov.uddp.core.read.OverviewReadService;
import gov.uddp.core.read.Page;
import gov.uddp.core.read.ReadNotFoundException;
import gov.uddp.core.read.statepack.StatePackModels.StatePackListItem;
import gov.uddp.core.read.statepack.StatePackModels.StatePackView;
import gov.uddp.core.read.statepack.StatePackQueryService;
import gov.uddp.core.security.Audited;
import gov.uddp.core.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The typed, read-only {@code /api/v1} API used by the operational UI (ADR-0007). Lists are paged with {@code page}
 * (0-based), {@code size} (1..200) and {@code sort} ({@code field,asc|desc}); errors are {@link ApiError}.
 */
public final class ReadApiControllers {
    private ReadApiControllers() {
    }

    static final String PAGE = "0-based page number";
    static final String SIZE = "Page size, 1 to 200 (default 20)";
    static final String SORT = "field or field,asc|desc";
    static final String STATE = "Pack ID or state code";

    @RestController
    @RequestMapping("/api/v1")
    @Tag(name = "Overview")
    public static class OverviewController {
        private final OverviewReadService overview;

        public OverviewController(OverviewReadService overview) {
            this.overview = overview;
        }

        @Operation(operationId = "getOverview", summary = "Counts per state and platform totals")
        @GetMapping("/overview")
        @PreAuthorize("hasAnyAuthority('PERM_REPORT_VIEW', 'PERM_ONBOARDING_VIEW', 'PERM_RESIDENT_VIEW')")
        public OverviewStats overview() {
            return overview.overview();
        }
    }

    @RestController
    @RequestMapping("/api/v1/states")
    @Tag(name = "State Packs")
    public static class StatePackReadController {
        private final StatePackQueryService packs;

        public StatePackReadController(StatePackQueryService packs) {
            this.packs = packs;
        }

        @Operation(operationId = "listStatePacks", summary = "Every State Pack with its lifecycle status")
        @GetMapping
        @PreAuthorize("hasAuthority('PERM_STATE_PACK_VIEW')")
        public List<StatePackListItem> list() {
            var scope = CurrentUser.scope();
            return packs.list().stream().filter(pack -> scope.allows(pack.stateCode())).toList();
        }

        @Operation(operationId = "getStatePack", summary = "One State Pack: sources, mappings, crosswalks, rules, versions")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown pack",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/{state}")
        @PreAuthorize("hasAuthority('PERM_STATE_PACK_VIEW')")
        public StatePackView detail(@Parameter(description = STATE) @PathVariable String state) {
            return packs.find(state)
                    .filter(pack -> CurrentUser.scope().allows(pack.stateCode()))
                    .orElseThrow(() -> new ReadNotFoundException("State Pack not found: " + state));
        }
    }

    @RestController
    @RequestMapping("/api/v1")
    @Tag(name = "Batches and data quality")
    public static class BatchReadController {
        private final BatchReadService batches;

        public BatchReadController(BatchReadService batches) {
            this.batches = batches;
        }

        @Operation(operationId = "listBatches", summary = "Ingestion batches with canonical and DQ counts")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "400", description = "Invalid parameter",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/batches")
        @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
        public Page<BatchSummary> list(@Parameter(description = STATE) @RequestParam(required = false) String state,
                                       @RequestParam(required = false) String status,
                                       @Parameter(description = PAGE) @RequestParam(required = false) Integer page,
                                       @Parameter(description = SIZE) @RequestParam(required = false) Integer size,
                                       @Parameter(description = SORT + "; startedAt, stateCode, sourceSystem, recordCount, status")
                                       @RequestParam(required = false) String sort) {
            return batches.batches(state, status, page, size, sort);
        }

        @Operation(operationId = "getBatch", summary = "One batch with its latest DQ evaluation and processing errors")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown batch",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/batches/{batchId}")
        @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
        public BatchDetail detail(@PathVariable UUID batchId) {
            return batches.batch(batchId);
        }

        @Operation(operationId = "listDqRules", summary = "DQ findings per rule and severity (latest evaluation per batch)")
        @GetMapping("/dq/rules")
        @PreAuthorize("hasAuthority('PERM_DATA_QUALITY_VIEW')")
        public List<DqRuleSummary> rules(@Parameter(description = STATE) @RequestParam(required = false) String state,
                                         @RequestParam(required = false) UUID batchId) {
            return batches.rules(state, batchId);
        }

        @Operation(operationId = "listDqFindings", summary = "DQ findings (failing records), filterable by rule and severity")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "400", description = "Invalid parameter",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/dq/findings")
        @PreAuthorize("hasAuthority('PERM_DATA_QUALITY_VIEW')")
        public Page<DqFinding> findings(@Parameter(description = STATE) @RequestParam(required = false) String state,
                                        @RequestParam(required = false) UUID batchId,
                                        @RequestParam(required = false) String ruleId,
                                        @Parameter(description = "ERROR or WARNING") @RequestParam(required = false) String severity,
                                        @Parameter(description = PAGE) @RequestParam(required = false) Integer page,
                                        @Parameter(description = SIZE) @RequestParam(required = false) Integer size,
                                        @Parameter(description = SORT + "; evaluatedAt, ruleId, severity, stateCode")
                                        @RequestParam(required = false) String sort) {
            return batches.findings(state, batchId, ruleId, severity, page, size, sort);
        }
    }

    @RestController
    @RequestMapping("/api/v1")
    @Tag(name = "Canonical Residents")
    @MaskPii
    public static class CanonicalReadController {
        private final CanonicalReadService canonical;

        public CanonicalReadController(CanonicalReadService canonical) {
            this.canonical = canonical;
        }

        @Operation(operationId = "listCanonicalResidents",
                summary = "Canonical Residents (latest versions unless includeSuperseded)")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "400", description = "Invalid parameter",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/canonical-residents")
        @PreAuthorize("hasAuthority('PERM_CANONICAL_VIEW')")
        public Page<CanonicalResidentSummary> list(@Parameter(description = STATE) @RequestParam(required = false) String state,
                                                   @RequestParam(required = false) UUID batchId,
                                                   @Parameter(description = "Name contains (case-insensitive)")
                                                   @RequestParam(required = false) String name,
                                                   @RequestParam(defaultValue = "false") boolean includeSuperseded,
                                                   @Parameter(description = PAGE) @RequestParam(required = false) Integer page,
                                                   @Parameter(description = SIZE) @RequestParam(required = false) Integer size,
                                                   @Parameter(description = SORT + "; name, createdAt, stateCode, sourceSystem")
                                                   @RequestParam(required = false) String sort) {
            return canonical.list(state, batchId, name, includeSuperseded, page, size, sort);
        }

        @Operation(operationId = "getCanonicalResident",
                summary = "One canonical Resident: source vs canonical values, lineage, warnings, versions")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown canonical Resident",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/canonical-residents/{id}")
        @PreAuthorize("hasAuthority('PERM_CANONICAL_VIEW')")
        @Audited(action = "CANONICAL_RESIDENT_VIEW", resource = "CANONICAL_RESIDENT", idVariable = "id")
        public CanonicalResidentDetail detail(@PathVariable UUID id) {
            return canonical.detail(id);
        }

        @Operation(operationId = "getRawRecord", summary = "One raw record as landed, parsed, with its canonical versions")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown raw record",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/raw-records/{id}")
        @PreAuthorize("hasAuthority('PERM_LINEAGE_VIEW')")
        @Audited(action = "LINEAGE_RAW_VIEW", resource = "RAW_RECORD", idVariable = "id")
        public RawRecordDetail raw(@PathVariable UUID id) {
            return canonical.raw(id);
        }
    }

    @RestController
    @RequestMapping("/api/v1/matching-runs")
    @Tag(name = "Matching")
    @MaskPii
    public static class MatchingReadController {
        private final MatchingReadService matching;

        public MatchingReadController(MatchingReadService matching) {
            this.matching = matching;
        }

        @Operation(operationId = "listMatchingRuns", summary = "Matching runs with decision counts")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "400", description = "Invalid parameter",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping
        @PreAuthorize("hasAuthority('PERM_MATCH_EVIDENCE_VIEW')")
        public Page<MatchingRunSummary> list(@RequestParam(required = false) String status,
                                             @Parameter(description = "FULL or INCREMENTAL") @RequestParam(required = false) String mode,
                                             @Parameter(description = PAGE) @RequestParam(required = false) Integer page,
                                             @Parameter(description = SIZE) @RequestParam(required = false) Integer size,
                                             @Parameter(description = SORT + "; startedAt, status, recordsConsidered")
                                             @RequestParam(required = false) String sort) {
            return matching.runs(status, mode, page, size, sort);
        }

        @Operation(operationId = "getMatchingRun", summary = "One matching run with its multi-member clusters")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown run",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/{runId}")
        @PreAuthorize("hasAuthority('PERM_MATCH_EVIDENCE_VIEW')")
        public MatchingRunDetail detail(@PathVariable UUID runId) {
            return matching.run(runId);
        }

        @Operation(operationId = "listMatchPairs", summary = "Pair decisions of a run, by decision and canonical ID")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown run",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/{runId}/pairs")
        @PreAuthorize("hasAuthority('PERM_MATCH_EVIDENCE_VIEW')")
        public Page<MatchPairSummary> pairs(@PathVariable UUID runId,
                                            @Parameter(description = "AUTO_MATCH, POSSIBLE_MATCH or NO_MATCH")
                                            @RequestParam(required = false) String decision,
                                            @Parameter(description = "Pairs that include this canonical Resident")
                                            @RequestParam(required = false) UUID canonicalId,
                                            @Parameter(description = PAGE) @RequestParam(required = false) Integer page,
                                            @Parameter(description = SIZE) @RequestParam(required = false) Integer size,
                                            @Parameter(description = SORT + "; matchProbability, matchWeight, decision")
                                            @RequestParam(required = false) String sort) {
            return matching.pairs(runId, decision, canonicalId, page, size, sort);
        }

        @Operation(operationId = "getMatchPair", summary = "One pair side by side with its evidence and decision")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown run or pair",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/{runId}/pairs/{canonicalA}/{canonicalB}")
        @PreAuthorize("hasAuthority('PERM_MATCH_EVIDENCE_VIEW')")
        public MatchPairDetail pair(@PathVariable UUID runId, @PathVariable UUID canonicalA,
                                    @PathVariable UUID canonicalB) {
            return matching.pair(runId, canonicalA, canonicalB);
        }
    }

    @RestController
    @RequestMapping("/api/v1/golden-residents")
    @Tag(name = "Golden Residents")
    @MaskPii
    public static class GoldenReadController {
        private final GoldenReadService golden;
        private final FamilyReadService families;

        public GoldenReadController(GoldenReadService golden, FamilyReadService families) {
            this.golden = golden;
            this.families = families;
        }

        @Operation(operationId = "searchGoldenResidents", summary = "Search Golden Residents")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "400", description = "Invalid parameter",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping
        @PreAuthorize("hasAuthority('PERM_RESIDENT_VIEW')")
        public Page<GoldenResidentSummary> list(@Parameter(description = "Name contains (case-insensitive)")
                                                @RequestParam(required = false) String q,
                                                @Parameter(description = "Digits the mobile number contains")
                                                @RequestParam(required = false) String mobile,
                                                @Parameter(description = "LGD district code")
                                                @RequestParam(required = false) String district,
                                                @Parameter(description = "Has a member from this pack ID or state code")
                                                @RequestParam(required = false) String state,
                                                @Parameter(description = "ACTIVE (default), RETIRED or ALL")
                                                @RequestParam(required = false) String status,
                                                @Parameter(description = PAGE) @RequestParam(required = false) Integer page,
                                                @Parameter(description = SIZE) @RequestParam(required = false) Integer size,
                                                @Parameter(description = SORT + "; name, updatedAt, createdAt, memberCount, versionNumber")
                                                @RequestParam(required = false) String sort) {
            return golden.list(q, mobile, district, state, status, page, size, sort);
        }

        @Operation(operationId = "getGoldenResident",
                summary = "Golden 360: values with provenance, members and versions; retired IDs carry redirectTo")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown Golden ID",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/{id}")
        @PreAuthorize("hasAuthority('PERM_RESIDENT_VIEW')")
        @Audited(action = "RESIDENT_360_VIEW", resource = "GOLDEN_RESIDENT", idVariable = "id")
        public GoldenResident360 detail(@PathVariable UUID id) {
            return golden.detail(id);
        }

        @Operation(operationId = "getGoldenResidentByUddpId",
                summary = "Golden 360 by UDDP ID; a retired or merged ID carries redirectTo and redirectToUddpId")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown UDDP ID",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/by-uddp-id/{uddpId}")
        @PreAuthorize("hasAuthority('PERM_RESIDENT_VIEW')")
        @Audited(action = "RESIDENT_360_VIEW", resource = "GOLDEN_RESIDENT", idVariable = "uddpId")
        public GoldenResident360 byUddpId(@PathVariable String uddpId) {
            return golden.byUddpId(uddpId);
        }

        @Operation(operationId = "getGoldenResidentHistory",
                summary = "Every version with provenance and value changes, and every lifecycle event")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown Golden ID",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/{id}/history")
        @PreAuthorize("hasAuthority('PERM_RESIDENT_VIEW')")
        @Audited(action = "RESIDENT_HISTORY_VIEW", resource = "GOLDEN_RESIDENT", idVariable = "id")
        public GoldenResidentHistory history(@PathVariable UUID id) {
            return golden.history(id);
        }

        @Operation(operationId = "getGoldenResidentFamilies", summary = "The households a Golden Resident currently belongs to")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown Golden ID",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/{id}/families")
        @PreAuthorize("hasAuthority('PERM_FAMILY_VIEW')")
        public List<FamilySummary> families(@PathVariable UUID id) {
            return families.familiesOf(id);
        }
    }

    @RestController
    @RequestMapping("/api/v1/families")
    @Tag(name = "Families")
    @MaskPii
    public static class FamilyReadController {
        private final FamilyReadService families;

        public FamilyReadController(FamilyReadService families) {
            this.families = families;
        }

        @Operation(operationId = "listFamilies", summary = "Golden Families (one per source household)")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "400", description = "Invalid parameter",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping
        @PreAuthorize("hasAuthority('PERM_FAMILY_VIEW')")
        public Page<FamilySummary> list(@Parameter(description = STATE) @RequestParam(required = false) String state,
                                        @Parameter(description = "ACTIVE (default), RETIRED or ALL")
                                        @RequestParam(required = false) String status,
                                        @Parameter(description = "Source family ID contains (case-insensitive)")
                                        @RequestParam(required = false) String q,
                                        @Parameter(description = "Households this Golden Resident currently belongs to")
                                        @RequestParam(required = false) UUID goldenResidentId,
                                        @Parameter(description = PAGE) @RequestParam(required = false) Integer page,
                                        @Parameter(description = SIZE) @RequestParam(required = false) Integer size,
                                        @Parameter(description = SORT + "; sourceFamilyId, stateCode, memberCount, createdAt, updatedAt")
                                        @RequestParam(required = false) String sort) {
            return families.list(state, status, q, goldenResidentId, page, size, sort);
        }

        @Operation(operationId = "getFamily",
                summary = "One Golden Family: members with roles and lineage, related households, versions")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown Golden Family",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/{id}")
        @PreAuthorize("hasAuthority('PERM_FAMILY_VIEW')")
        public GoldenFamilyDetail detail(@PathVariable UUID id) {
            return families.detail(id);
        }

        @Operation(operationId = "getFamilyByUddpId", summary = "One Golden Family by its UDDP ID")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown UDDP ID",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/by-uddp-id/{uddpId}")
        @PreAuthorize("hasAuthority('PERM_FAMILY_VIEW')")
        public GoldenFamilyDetail byUddpId(@PathVariable String uddpId) {
            return families.byUddpId(uddpId);
        }

        @Operation(operationId = "getFamilyHistory", summary = "Every version with its members, and every event")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown Golden Family",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/{id}/history")
        @PreAuthorize("hasAuthority('PERM_FAMILY_VIEW')")
        public GoldenFamilyHistory history(@PathVariable UUID id) {
            return families.history(id);
        }
    }

    @RestController
    @RequestMapping("/api/v1/onboarding-runs")
    @Tag(name = "Onboarding")
    public static class OnboardingReadController {
        private final OnboardingReadService onboarding;

        public OnboardingReadController(OnboardingReadService onboarding) {
            this.onboarding = onboarding;
        }

        @Operation(operationId = "listOnboardingRuns", summary = "Onboarding runs across states, newest first")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "400", description = "Invalid parameter",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping
        @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
        public Page<OnboardingRunSummary> list(@Parameter(description = STATE) @RequestParam(required = false) String state,
                                               @Parameter(description = "RUNNING, FAILED or COMPLETED")
                                               @RequestParam(required = false) String status,
                                               @Parameter(description = PAGE) @RequestParam(required = false) Integer page,
                                               @Parameter(description = SIZE) @RequestParam(required = false) Integer size,
                                               @Parameter(description = SORT + "; startedAt, stateCode, status")
                                               @RequestParam(required = false) String sort) {
            return onboarding.runs(state, status, page, size, sort);
        }

        @Operation(operationId = "getOnboardingRun", summary = "One run as a stage timeline with every attempt")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown run",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/{runId}")
        @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
        public OnboardingRunDetail detail(@PathVariable UUID runId) {
            return onboarding.run(runId);
        }
    }
}
