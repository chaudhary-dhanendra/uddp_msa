package gov.uddp.core.api.v1;

import gov.uddp.core.security.AuditContext;
import gov.uddp.core.security.Audited;
import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.studio.StudioMetadata;
import gov.uddp.core.studio.StudioModels;
import gov.uddp.core.studio.StudioNotFoundException;
import gov.uddp.core.studio.StudioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The Onboarding Studio API: drafts, upload, profiling, suggestions, preview, publication, onboarding, evidence. */
@RestController
@RequestMapping("/api/studio")
@Tag(name = "Onboarding Studio")
public class StudioController {
    private final StudioService studio;
    private final StudioMetadata metadata;

    public StudioController(StudioService studio, StudioMetadata metadata) {
        this.studio = studio;
        this.metadata = metadata;
    }

    @GetMapping("/status")
    @Operation(operationId = "getStudioStatus", summary = "Studio packs directory, demo mode and upload limit")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_VIEW')")
    public StudioModels.StudioStatus status() {
        return studio.status();
    }

    @GetMapping("/mapping-targets")
    @Operation(operationId = "getMappingTargets", summary = "Resident, Family and Benefit targets and code sets")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_VIEW')")
    public StudioModels.MappingTargets mappingTargets() {
        return new StudioModels.MappingTargets(metadata.mappingTargets(), metadata.codeSets(),
                StudioMetadata.DATE_CONVERSIONS);
    }

    @GetMapping("/states")
    @Operation(operationId = "listStudioStates", summary = "Every Studio state with its draft/published/onboarded status")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_VIEW')")
    public List<StudioModels.StateEntry> states() {
        var scope = CurrentUser.scope();
        return studio.states().stream().filter(state -> scope.allows(state.stateCode())).toList();
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createDraft", summary = "Start a draft for a new state")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_CREATE')")
    @Audited(action = "STUDIO_DRAFT_CREATE", resource = "STUDIO_DRAFT")
    public StudioModels.Draft create(@RequestBody StudioModels.DraftDetails details) {
        requireStateInScope(details == null ? null : details.stateCode());
        return studio.create(details);
    }

    @GetMapping("/drafts/{draftId}")
    @Operation(operationId = "getDraft", summary = "One draft")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_VIEW')")
    public StudioModels.Draft draft(@PathVariable String draftId) {
        return inScope(draftId);
    }

    @PutMapping("/drafts/{draftId}/details")
    @Operation(operationId = "updateDraftDetails", summary = "Change the state details")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_EDIT')")
    @Audited(action = "STUDIO_DRAFT_DETAILS", resource = "STUDIO_DRAFT", idVariable = "draftId")
    public StudioModels.Draft details(@PathVariable String draftId, @RequestBody StudioModels.DraftDetails details) {
        inScope(draftId);
        requireStateInScope(details == null ? null : details.stateCode());
        return studio.updateDetails(draftId, details);
    }

    @PostMapping(value = "/drafts/{draftId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(operationId = "uploadSourceFile", summary = "Upload the source CSV into the landing folder")
    @PreAuthorize("hasAuthority('PERM_SOURCE_UPLOAD')")
    @Audited(action = "STUDIO_SOURCE_UPLOAD", resource = "STUDIO_DRAFT", idVariable = "draftId")
    public StudioModels.Draft upload(@PathVariable String draftId, @RequestPart("file") MultipartFile file) {
        inScope(draftId);
        try {
            return studio.upload(draftId, file.getOriginalFilename(), file.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @PostMapping(value = "/drafts/{draftId}/delivery", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(operationId = "uploadNewDelivery",
            summary = "Replace a published file source's landing file with a new delivery (C1); onboard it next")
    @PreAuthorize("hasAuthority('PERM_SOURCE_UPLOAD')")
    @Audited(action = "STUDIO_DELIVERY_UPLOAD", resource = "STUDIO_DRAFT", idVariable = "draftId")
    public StudioModels.Draft uploadDelivery(@PathVariable String draftId, @RequestPart("file") MultipartFile file) {
        inScope(draftId);
        try {
            return studio.uploadDelivery(draftId, file.getOriginalFilename(), file.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/connections")
    @Operation(operationId = "listSourceConnections", summary = "Named source connections (passwords never returned)")
    @PreAuthorize("hasAuthority('PERM_SOURCE_UPLOAD')")
    public List<StudioModels.SourceConnection> connections() {
        return studio.connections();
    }

    @PutMapping("/connections/{name}")
    @Operation(operationId = "saveSourceConnection",
            summary = "Test and save a named PostgreSQL source connection (password encrypted on this machine)")
    @PreAuthorize("hasAuthority('PERM_SOURCE_UPLOAD')")
    @Audited(action = "STUDIO_CONNECTION_SAVE", resource = "SOURCE_CONNECTION", idVariable = "name")
    public StudioModels.SourceConnection saveConnection(@PathVariable String name,
                                                        @RequestBody StudioModels.SourceConnectionRequest request) {
        return studio.saveConnection(name, request);
    }

    @PostMapping("/drafts/{draftId}/database-source")
    @Operation(operationId = "useDatabaseSource", summary = "Use a table or SELECT of a named connection as the source")
    @PreAuthorize("hasAuthority('PERM_SOURCE_UPLOAD')")
    @Audited(action = "STUDIO_DATABASE_SOURCE", resource = "STUDIO_DRAFT", idVariable = "draftId")
    public StudioModels.Draft databaseSource(@PathVariable String draftId,
                                             @RequestBody StudioModels.DatabaseSourceRequest request) {
        inScope(draftId);
        return studio.useDatabaseSource(draftId, request.connectionName(), request.table(), request.query());
    }

    @GetMapping("/drafts/{draftId}/profile")
    @Operation(operationId = "profileSource", summary = "Columns, types, blanks, distinct and top values, sample rows")
    @PreAuthorize("hasAuthority('PERM_SOURCE_PROFILE')")
    public StudioModels.Profile profile(@PathVariable String draftId) {
        inScope(draftId);
        return studio.profile(draftId);
    }

    @GetMapping("/drafts/{draftId}/suggestions")
    @Operation(operationId = "suggestMappings", summary = "Suggested targets per column and crosswalk values")
    @PreAuthorize("hasAuthority('PERM_SOURCE_PROFILE')")
    public StudioModels.Suggestions suggestions(@PathVariable String draftId) {
        inScope(draftId);
        return studio.suggestions(draftId);
    }

    @GetMapping("/drafts/{draftId}/shaping")
    @Operation(operationId = "getShaping", summary = "Layout and parser proposals with evidence, and the steps the draft keeps")
    @PreAuthorize("hasAuthority('PERM_SOURCE_PROFILE')")
    public StudioModels.ShapeState shaping(@PathVariable String draftId) {
        inScope(draftId);
        return studio.shaping(draftId);
    }

    @PostMapping("/drafts/{draftId}/shaping/preview")
    @Operation(operationId = "previewShaping", summary = "Before and after of 20 sample rows for these steps; nothing is saved")
    @PreAuthorize("hasAuthority('PERM_SOURCE_PROFILE')")
    @Audited(action = "STUDIO_DRAFT_SHAPING_PREVIEW", resource = "STUDIO_DRAFT", idVariable = "draftId")
    public StudioModels.ShapePreview previewShaping(@PathVariable String draftId,
                                                    @RequestBody StudioModels.ShapingConfig config) {
        inScope(draftId);
        return studio.shapePreview(draftId, config);
    }

    @PutMapping("/drafts/{draftId}/shaping")
    @Operation(operationId = "saveShaping", summary = "Keep the accepted layout steps and parsers in the draft's source")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_EDIT')")
    @Audited(action = "STUDIO_DRAFT_SHAPING", resource = "STUDIO_DRAFT", idVariable = "draftId")
    public StudioModels.Draft saveShaping(@PathVariable String draftId, @RequestBody StudioModels.ShapingConfig config) {
        inScope(draftId);
        return studio.saveShaping(draftId, config);
    }

    @PutMapping("/drafts/{draftId}/configuration")
    @Operation(operationId = "saveDraftConfiguration", summary = "Save mappings, crosswalks, rules and record key")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_EDIT')")
    @Audited(action = "STUDIO_DRAFT_CONFIGURATION", resource = "STUDIO_DRAFT", idVariable = "draftId")
    public StudioModels.Draft configuration(@PathVariable String draftId,
                                            @RequestBody StudioModels.DraftConfiguration configuration) {
        inScope(draftId);
        return studio.saveConfiguration(draftId, configuration);
    }

    @GetMapping("/drafts/{draftId}/preview")
    @Operation(operationId = "previewDraft", summary = "Dry run of the first rows with the existing engines; writes nothing")
    @PreAuthorize("hasAuthority('PERM_SOURCE_PROFILE')")
    public StudioModels.Preview preview(@PathVariable String draftId,
                                        @RequestParam(required = false) Integer rows) {
        inScope(draftId);
        return studio.preview(draftId, rows);
    }

    @GetMapping("/drafts/{draftId}/generated")
    @Operation(operationId = "getGeneratedPack", summary = "The State Pack YAML publishing would write")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_VIEW')")
    public StudioModels.GeneratedPack generated(@PathVariable String draftId) {
        inScope(draftId);
        return studio.generated(draftId);
    }

    @PostMapping("/drafts/{draftId}/publish")
    @ApiResponse(responseCode = "200", description = "The published draft")
    @ApiResponse(responseCode = "409", description = "Blocking preview errors, a run in progress or disagreeing thresholds",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @Operation(operationId = "publishDraft",
            summary = "Validate the draft and submit its next version for approval (or publish it when maker-checker is off)")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_EDIT')")
    @Audited(action = "STUDIO_PUBLISH_SUBMIT", resource = "STUDIO_DRAFT", idVariable = "draftId")
    public StudioModels.Draft publish(@PathVariable String draftId) {
        inScope(draftId);
        return studio.publish(draftId);
    }

    @PostMapping("/drafts/{draftId}/onboard")
    @ApiResponse(responseCode = "202", description = "The run was started")
    @ApiResponse(responseCode = "409", description = "Not published yet, or another run is in progress",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "onboardDraft", summary = "Start an onboarding run of the published pack in the background")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN')")
    @Audited(action = "ONBOARDING_START", resource = "STUDIO_DRAFT", idVariable = "draftId")
    public StudioModels.OnboardStarted onboard(@PathVariable String draftId,
                                               @RequestBody(required = false) StudioModels.OnboardRequest request) {
        inScope(draftId);
        return studio.onboard(draftId, request == null ? null : request.processingMode());
    }

    @GetMapping("/portability")
    @Operation(operationId = "getPortabilityEvidence", summary = "Generated files, Hop templates and platform code changes per state")
    @PreAuthorize("hasAuthority('PERM_PORTABILITY_VIEW')")
    public StudioModels.PortabilityEvidence portability() {
        var evidence = studio.portability();
        var scope = CurrentUser.scope();
        return new StudioModels.PortabilityEvidence(evidence.baselineRef(), evidence.baselineCommit(),
                evidence.currentCommit(), evidence.platformPaths(), evidence.changedSinceBaseline(), evidence.gitError(),
                evidence.guard(), evidence.states().stream().filter(state -> scope.allows(state.stateCode())).toList());
    }

    @GetMapping("/approvals")
    @Operation(operationId = "listPackApprovals",
            summary = "State Pack versions submitted for approval, newest first (PENDING_APPROVAL, APPROVED, REJECTED)")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_VIEW')")
    public List<StudioModels.Approval> approvals(@RequestParam(required = false) String status) {
        var scope = CurrentUser.scope();
        String filter = status == null || status.isBlank() ? null : status.strip().toUpperCase(java.util.Locale.ROOT);
        return studio.approvals(filter).stream().filter(approval -> scope.allows(approval.stateCode())).toList();
    }

    @PostMapping("/approvals/{approvalId}/approve")
    @Operation(operationId = "approvePackVersion",
            summary = "Approve a submitted version (not your own): it is persisted, registered and activated")
    @ApiResponse(responseCode = "200", description = "The published draft")
    @ApiResponse(responseCode = "403", description = "Your own submission, or no STATE_PACK_APPROVE",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Already decided, or a run is in progress",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_APPROVE')")
    @Audited(action = "STATE_PACK_APPROVE", resource = "STATE_PACK_APPROVAL", idVariable = "approvalId")
    public StudioModels.Draft approve(@PathVariable String approvalId,
                                      @RequestBody(required = false) StudioModels.ApprovalDecision decision) {
        approvalInScope(approvalId);
        return studio.approve(approvalId, decision == null ? null : decision.reason());
    }

    @PostMapping("/approvals/{approvalId}/reject")
    @Operation(operationId = "rejectPackVersion", summary = "Reject a submitted version (not your own) with a reason")
    @ApiResponse(responseCode = "200", description = "The draft, editable again")
    @ApiResponse(responseCode = "400", description = "No reason given",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Your own submission, or no STATE_PACK_APPROVE",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_APPROVE')")
    @Audited(action = "STATE_PACK_REJECT", resource = "STATE_PACK_APPROVAL", idVariable = "approvalId")
    public StudioModels.Draft reject(@PathVariable String approvalId,
                                     @RequestBody(required = false) StudioModels.ApprovalDecision decision) {
        approvalInScope(approvalId);
        return studio.reject(approvalId, decision == null ? null : decision.reason());
    }

    private void approvalInScope(String approvalId) {
        var approval = studio.approval(approvalId);
        if (!CurrentUser.scope().allows(approval.stateCode())) {
            throw new StudioNotFoundException("Unknown approval: " + approvalId);
        }
        AuditContext.state(approval.stateCode());
        AuditContext.detail("packId", approval.packId());
        AuditContext.detail("version", approval.version());
        AuditContext.detail("submittedBy", approval.submittedBy());
    }

    /**
     * The draft, when its state is in the user's scope (ADR-0019); otherwise 404 as for an unknown draft. The state
     * becomes the audited state of the request.
     */
    private StudioModels.Draft inScope(String draftId) {
        var draft = studio.draft(draftId);
        if (!CurrentUser.scope().allows(draft.stateCode())) {
            throw new StudioNotFoundException("Unknown draft: " + draftId);
        }
        AuditContext.state(draft.stateCode());
        return draft;
    }

    private static void requireStateInScope(String stateCode) {
        if (stateCode != null && !stateCode.isBlank() && !CurrentUser.scope().allows(stateCode.strip())) {
            throw new AccessDeniedException("State is outside your assigned scope: " + stateCode.strip());
        }
        AuditContext.state(stateCode == null ? null : stateCode.strip());
    }
}
