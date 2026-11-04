package gov.uddp.core.api.v1;

import gov.uddp.core.held.HeldRecord;
import gov.uddp.core.held.HeldRecordService;
import gov.uddp.core.held.HeldRecordService.Detail;
import gov.uddp.core.held.HeldRecordService.GroupActionResult;
import gov.uddp.core.held.HeldRecordService.Group;
import gov.uddp.core.held.HeldRecordService.PageOf;
import gov.uddp.core.held.HeldRecordService.RectifyResult;
import gov.uddp.core.privacy.MaskPii;
import gov.uddp.core.security.AuditContext;
import gov.uddp.core.security.Audited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Held records (V108): rows kept out of canonical, matching and golden until a steward decides. Viewing needs
 * STEWARD_HELD_VIEW and deciding STEWARD_HELD_DECIDE; both are enforced by the URL rules of the security chain before
 * the body is read, and again on each handler. Personal data in the payloads is masked like every resident response.
 */
@RestController
@RequestMapping("/api/v1/held-records")
@Tag(name = "Held records")
@MaskPii
public class HeldRecordController {
    private static final String VIEW = "hasAuthority('PERM_STEWARD_HELD_VIEW')";
    private static final String DECIDE = "hasAuthority('PERM_STEWARD_HELD_DECIDE')";

    public record RectifyRequest(@Schema(description = "Canonical field path to the corrected value") Map<String, Object> fields,
                                 String note) {
    }

    public record ReleaseOverrideRequest(String justification) {
    }

    public record RejectRequest(@Schema(description = "SOURCE_ERROR, DUPLICATE_OF_OTHER_SOURCE, OUT_OF_SCOPE, UNREADABLE"
            + " or OTHER (a note is then required)") String reasonCode, String note) {
    }

    public record ReopenRequest(String reason) {
    }

    public record BulkDecision(@Schema(nullable = true) String reason) {
    }

    private final HeldRecordService held;

    public HeldRecordController(HeldRecordService held) {
        this.held = held;
    }

    @Operation(operationId = "listHeldRecords", summary = "Held records, oldest first")
    @ApiResponse(responseCode = "200", description = "OK")
    @ApiResponse(responseCode = "400", description = "Invalid filter",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @GetMapping
    @PreAuthorize(VIEW)
    public PageOf list(@Parameter(description = "Ingestion batch") @RequestParam(required = false) UUID batch,
                       @RequestParam(required = false) String source,
                       @RequestParam(required = false) String department,
                       @Parameter(description = "A reason code such as UNMAPPED_CODE or RULE_FAILED:<ruleId>")
                       @RequestParam(required = false) String reason,
                       @Parameter(description = "OPEN, IN_REVIEW, RECTIFIED, REJECTED_FINAL, RELEASED_OVERRIDE or WAITING"
                               + " (open or in review)") @RequestParam(required = false) String status,
                       @Parameter(description = "Held for at least this many hours")
                       @RequestParam(required = false) Integer minAgeHours,
                       @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return held.list(batch, source, department, reason, status, minAgeHours, page, size);
    }

    @Operation(operationId = "listHeldRecordGroups",
            summary = "Waiting held records grouped by batch and reason, with share of the batch and 5 samples")
    @ApiResponse(responseCode = "200", description = "OK")
    @GetMapping("/groups")
    @PreAuthorize(VIEW)
    public List<Group> groups() {
        return held.groups();
    }

    @Operation(operationId = "getHeldRecord", summary = "One held record with its reasons, original, current and history")
    @ApiResponse(responseCode = "200", description = "OK")
    @ApiResponse(responseCode = "404", description = "Unknown or out of scope",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public Detail get(@PathVariable UUID id) {
        return held.detail(id);
    }

    @Operation(operationId = "rectifyHeldRecord",
            summary = "Store a correction and validate again; a passing record is released, a failing one stays open")
    @ApiResponse(responseCode = "200", description = "RECTIFIED, or still OPEN with the reasons found now")
    @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Already decided",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/rectify")
    @PreAuthorize(DECIDE)
    @Audited(action = "HELD_RECTIFY", resource = "HELD_RECORD", idVariable = "id")
    public RectifyResult rectify(@PathVariable UUID id, @RequestBody RectifyRequest request, Authentication actor) {
        return held.rectify(id, request.fields(), request.note(), actor.getName());
    }

    @Operation(operationId = "releaseHeldRecordOverride",
            summary = "Release a record held only for steward-routed reasons as it is; never for a hard error")
    @ApiResponse(responseCode = "200", description = "RELEASED_OVERRIDE")
    @ApiResponse(responseCode = "409", description = "A hard error is among the reasons, or already decided",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/release-override")
    @PreAuthorize(DECIDE)
    @Audited(action = "HELD_RELEASE_OVERRIDE", resource = "HELD_RECORD", idVariable = "id")
    public RectifyResult releaseOverride(@PathVariable UUID id, @RequestBody ReleaseOverrideRequest request,
                                         Authentication actor) {
        return held.releaseOverride(id, request.justification(), actor.getName());
    }

    @Operation(operationId = "rejectHeldRecord", summary = "Reject a held record for good (REJECTED_FINAL)")
    @ApiResponse(responseCode = "200", description = "REJECTED_FINAL")
    @ApiResponse(responseCode = "400", description = "Unknown reason code, or OTHER without a note",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/reject")
    @PreAuthorize(DECIDE)
    @Audited(action = "HELD_REJECT", resource = "HELD_RECORD", idVariable = "id")
    public HeldRecord reject(@PathVariable UUID id, @RequestBody RejectRequest request, Authentication actor) {
        return held.reject(id, request.reasonCode(), request.note(), actor.getName());
    }

    @Operation(operationId = "reopenHeldRecord", summary = "Reopen a rejected record (platform administrators only)")
    @ApiResponse(responseCode = "200", description = "OPEN again")
    @ApiResponse(responseCode = "409", description = "Not rejected",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/{id}/reopen")
    @PreAuthorize(DECIDE + " and hasAuthority('PERM_PLATFORM_SETTINGS')")
    @Audited(action = "HELD_REOPEN", resource = "HELD_RECORD", idVariable = "id")
    public HeldRecord reopen(@PathVariable UUID id, @RequestBody ReopenRequest request, Authentication actor) {
        return held.reopen(id, request.reason(), actor.getName());
    }

    @Operation(operationId = "rejectHeldRecordGroup",
            summary = "Reject every waiting record of a batch and reason; a large group waits for a second steward")
    @ApiResponse(responseCode = "200", description = "APPLIED, or PENDING_APPROVAL with the bulk action to approve")
    @PostMapping("/groups/{batch}/{reason}/reject")
    @PreAuthorize(DECIDE)
    @Audited(action = "HELD_BULK_REJECT", resource = "HELD_RECORD_GROUP")
    public GroupActionResult rejectGroup(@PathVariable UUID batch, @PathVariable String reason,
                                         @RequestBody RejectRequest request, Authentication actor) {
        AuditContext.resource(batch + "/" + reason);
        GroupActionResult result = held.rejectGroup(batch, reason, request.reasonCode(), request.note(), actor.getName());
        AuditContext.detail("records", result.records());
        AuditContext.detail("outcome", result.outcome());
        return result;
    }

    @Operation(operationId = "reprocessHeldRecordGroup",
            summary = "Run the waiting records of a batch and reason through mapping and validation again")
    @ApiResponse(responseCode = "200", description = "How many were released and how many are still held")
    @PostMapping("/groups/{batch}/{reason}/reprocess")
    @PreAuthorize(DECIDE)
    @Audited(action = "HELD_REPROCESS", resource = "HELD_RECORD_GROUP")
    public GroupActionResult reprocessGroup(@PathVariable UUID batch, @PathVariable String reason, Authentication actor) {
        AuditContext.resource(batch + "/" + reason);
        return held.reprocessGroup(batch, reason, actor.getName());
    }

    @Operation(operationId = "listHeldBulkActions",
            summary = "Bulk rejects in your scope, newest first, with five masked records of each group")
    @ApiResponse(responseCode = "200", description = "OK")
    @ApiResponse(responseCode = "400", description = "Invalid status",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/bulk-actions")
    @PreAuthorize(VIEW)
    public List<HeldRecordService.BulkAction> bulkActions(
            @Parameter(description = "PENDING_APPROVAL, APPROVED or REJECTED (all when omitted)")
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String department) {
        return held.bulkActions(status, department);
    }

    @Operation(operationId = "approveHeldBulkAction", summary = "Approve a pending bulk reject made by someone else")
    @ApiResponse(responseCode = "200", description = "Applied")
    @ApiResponse(responseCode = "409", description = "Your own bulk action, or already decided",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/bulk-actions/{actionId}/approve")
    @PreAuthorize(DECIDE)
    @Audited(action = "HELD_BULK_REJECT", resource = "HELD_BULK_ACTION", idVariable = "actionId")
    public GroupActionResult approveBulk(@PathVariable UUID actionId,
                                         @RequestBody(required = false) BulkDecision decision, Authentication actor) {
        return held.approveBulk(actionId, decision == null ? null : decision.reason(), actor.getName());
    }

    @Operation(operationId = "declineHeldBulkAction",
            summary = "Reject (decline) a pending bulk reject made by someone else; a reason is required")
    @ApiResponse(responseCode = "200", description = "Declined; the records stay held")
    @ApiResponse(responseCode = "409", description = "Your own bulk action, or already decided",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/bulk-actions/{actionId}/decline")
    @PreAuthorize(DECIDE)
    @Audited(action = "HELD_BULK_DECLINE", resource = "HELD_BULK_ACTION", idVariable = "actionId")
    public void declineBulk(@PathVariable UUID actionId, @RequestBody(required = false) BulkDecision decision,
                            Authentication actor) {
        held.declineBulk(actionId, decision == null ? null : decision.reason(), actor.getName());
    }
}
