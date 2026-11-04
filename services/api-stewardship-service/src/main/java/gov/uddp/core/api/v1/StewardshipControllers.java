package gov.uddp.core.api.v1;

import gov.uddp.core.privacy.MaskPii;
import gov.uddp.core.read.Page;
import gov.uddp.core.read.StewardshipReadService.DecisionView;
import gov.uddp.core.read.StewardshipReadService.ReviewTaskDetail;
import gov.uddp.core.read.StewardshipReadService.ReviewTaskSummary;
import gov.uddp.core.read.StewardshipReadService;
import gov.uddp.core.security.AuditContext;
import gov.uddp.core.security.Audited;
import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.stewardship.StewardshipProposalService.Proposal;
import gov.uddp.core.stewardship.StewardshipProposalService.ProposalRequest;
import gov.uddp.core.stewardship.StewardshipProposalService;
import gov.uddp.core.stewardship.StewardshipService.DecisionResult;
import gov.uddp.core.stewardship.StewardshipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stewardship (M13, docs/stewardship.md): the review queue and decision log under {@code /api/v1/stewardship}, and
 * the decision endpoints under {@code /api/stewardship}. Every decision rebuilds Golden Residents against the latest
 * completed matching run and returns that build. Errors are {@link ApiError}: 400, 404, 409.
 */
public final class StewardshipControllers {
    private StewardshipControllers() {
    }

    public record ReviewDecisionRequest(
            @Schema(description = "ACCEPT (same person) or REJECT (different people)") String decision,
            @Schema(description = "Ignored; the authenticated user is always recorded as the reviewer") String reviewer,
            String reason) {
    }

    public record MergeRequest(List<UUID> goldenResidentIds, String reviewer, String reason) {
    }

    public record SplitRequest(UUID goldenResidentId,
                               @Schema(description = "Current members to detach into their own identity")
                               List<String> sourceRecordKeys, String reviewer, String reason) {
    }

    @RestController
    @RequestMapping("/api/v1/stewardship")
    @Tag(name = "Stewardship")
    @MaskPii
    public static class StewardshipReadController {
        private final StewardshipReadService stewardship;

        public StewardshipReadController(StewardshipReadService stewardship) {
            this.stewardship = stewardship;
        }

        @Operation(operationId = "listReviewTasks", summary = "The POSSIBLE_MATCH review queue")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "400", description = "Invalid parameter",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/reviews")
        @PreAuthorize("hasAuthority('PERM_STEWARDSHIP_VIEW')")
        public Page<ReviewTaskSummary> reviews(@Parameter(description = "OPEN (default), DECIDED or ALL")
                                               @RequestParam(required = false) String status,
                                               @Parameter(description = ReadApiControllers.PAGE)
                                               @RequestParam(required = false) Integer page,
                                               @Parameter(description = ReadApiControllers.SIZE)
                                               @RequestParam(required = false) Integer size,
                                               @Parameter(description = ReadApiControllers.SORT
                                                       + "; matchProbability, createdAt, decidedAt")
                                               @RequestParam(required = false) String sort) {
            return stewardship.reviews(status, page, size, sort);
        }

        @Operation(operationId = "getReviewTask", summary = "One review task side by side with its match evidence")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "404", description = "Unknown task",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/reviews/{id}")
        @PreAuthorize("hasAuthority('PERM_STEWARDSHIP_VIEW')")
        public ReviewTaskDetail review(@PathVariable UUID id) {
            return stewardship.review(id);
        }

        @Operation(operationId = "listStewardshipDecisions", summary = "The append-only decision log with constraints")
        @ApiResponse(responseCode = "200", description = "OK")
        @ApiResponse(responseCode = "400", description = "Invalid parameter",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @GetMapping("/decisions")
        @PreAuthorize("hasAuthority('PERM_STEWARDSHIP_VIEW')")
        public Page<DecisionView> decisions(@Parameter(description = "Decisions involving this Golden Resident")
                                            @RequestParam(required = false) UUID goldenResidentId,
                                            @Parameter(description = "ACCEPT_MATCH, REJECT_MATCH, MERGE or SPLIT")
                                            @RequestParam(required = false) String action,
                                            @Parameter(description = ReadApiControllers.PAGE)
                                            @RequestParam(required = false) Integer page,
                                            @Parameter(description = ReadApiControllers.SIZE)
                                            @RequestParam(required = false) Integer size,
                                            @Parameter(description = ReadApiControllers.SORT + "; sequence, createdAt, action")
                                            @RequestParam(required = false) String sort) {
            return stewardship.decisions(goldenResidentId, action, page, size, sort);
        }
    }

    @RestController
    @RequestMapping("/api/stewardship")
    @Tag(name = "Stewardship")
    public static class StewardshipDecisionController {
        private final StewardshipService stewardship;
        private final StewardshipProposalService proposals;

        public StewardshipDecisionController(StewardshipService stewardship, StewardshipProposalService proposals) {
            this.stewardship = stewardship;
            this.proposals = proposals;
        }

        @Operation(operationId = "decideReviewTask", summary = "Accept or reject a POSSIBLE_MATCH review task")
        @ApiResponse(responseCode = "200", description = "Recorded and applied")
        @ApiResponse(responseCode = "400", description = "Invalid request",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "404", description = "Unknown task",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "409", description = "Already decided, or no completed matching run",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @PostMapping("/reviews/{id}/decision")
        @PreAuthorize("hasAuthority('PERM_STEWARDSHIP_DECIDE')")
        @Audited(action = "STEWARDSHIP_DECIDE", resource = "REVIEW_TASK", idVariable = "id")
        public DecisionResult decide(@PathVariable UUID id, @RequestBody ReviewDecisionRequest request, Authentication actor) {
            proposals.requireDirectAllowed();
            return stewardship.decide(id, request.decision(), actor.getName(), request.reason());
        }

        @Operation(operationId = "mergeGoldenResidents", summary = "Merge two or more active Golden Residents")
        @ApiResponse(responseCode = "200", description = "Recorded and applied")
        @ApiResponse(responseCode = "400", description = "Invalid request",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "404", description = "Unknown Golden ID",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "409", description = "A retired Golden ID, or no completed matching run",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @PostMapping("/merge")
        @PreAuthorize("hasAuthority('PERM_STEWARDSHIP_DECIDE')")
        @Audited(action = "STEWARDSHIP_MERGE", resource = "GOLDEN_RESIDENT")
        public DecisionResult merge(@RequestBody MergeRequest request, Authentication actor) {
            proposals.requireDirectAllowed();
            return stewardship.merge(request.goldenResidentIds(), actor.getName(), request.reason());
        }

        @Operation(operationId = "splitGoldenResident", summary = "Detach members of a Golden Resident into their own identity")
        @ApiResponse(responseCode = "200", description = "Recorded and applied")
        @ApiResponse(responseCode = "400", description = "Invalid request",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "404", description = "Unknown Golden ID",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "409", description = "A retired Golden ID, or no completed matching run",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @PostMapping("/split")
        @PreAuthorize("hasAuthority('PERM_STEWARDSHIP_DECIDE')")
        @Audited(action = "STEWARDSHIP_SPLIT", resource = "GOLDEN_RESIDENT")
        public DecisionResult split(@RequestBody SplitRequest request, Authentication actor) {
            proposals.requireDirectAllowed();
            return stewardship.split(request.goldenResidentId(), request.sourceRecordKeys(), actor.getName(),
                    request.reason());
        }

        @Operation(operationId = "proposeStewardshipDecision",
                summary = "Propose an accept, reject, merge or split; nothing changes until a different user approves")
        @ApiResponse(responseCode = "201", description = "Proposed, PENDING_APPROVAL")
        @ApiResponse(responseCode = "400", description = "Invalid request",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "409", description = "Already decided, or a retired Golden ID",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @PostMapping("/proposals")
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('PERM_STEWARDSHIP_DECIDE')")
        @Audited(action = "STEWARDSHIP_PROPOSE", resource = "STEWARDSHIP_PROPOSAL")
        public Proposal propose(@RequestBody ProposalRequest request, Authentication actor) {
            requireInScope(proposals.states(proposals.involvedGoldens(request)));
            Proposal proposal = proposals.propose(request, actor.getName());
            AuditContext.resource(proposal.proposalId());
            AuditContext.detail("action", proposal.action());
            AuditContext.detail("goldenResidentIds", proposal.goldenResidentIds());
            return proposal;
        }

        @Operation(operationId = "listStewardshipProposals", summary = "Stewardship proposals, newest first")
        @GetMapping("/proposals")
        @PreAuthorize("hasAuthority('PERM_STEWARDSHIP_VIEW')")
        public List<Proposal> proposals(@Parameter(description = "PENDING_APPROVAL, APPROVED, REJECTED or STALE")
                                        @RequestParam(required = false) String status) {
            var scope = CurrentUser.scope();
            String filter = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
            return proposals.list(filter).stream().filter(p -> scope.allowsAll(p.stateCodes())).toList();
        }

        @Operation(operationId = "approveStewardshipProposal",
                summary = "Approve a proposal (not your own): the decision is recorded and Golden Residents rebuilt")
        @ApiResponse(responseCode = "200", description = "Applied")
        @ApiResponse(responseCode = "403", description = "Your own proposal, or no STEWARDSHIP_APPROVE",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "409", description = "Stale (its Golden Residents changed) or already decided",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @PostMapping("/proposals/{proposalId}/approve")
        @PreAuthorize("hasAuthority('PERM_STEWARDSHIP_APPROVE')")
        @Audited(action = "STEWARDSHIP_APPROVE", resource = "STEWARDSHIP_PROPOSAL", idVariable = "proposalId")
        public DecisionResult approve(@PathVariable UUID proposalId,
                                      @RequestBody(required = false) ProposalDecision decision, Authentication actor) {
            requireInScope(proposals.find(proposalId).stateCodes());
            return proposals.approve(proposalId, actor.getName(), decision == null ? null : decision.reason());
        }

        @Operation(operationId = "rejectStewardshipProposal", summary = "Reject a proposal (not your own) with a reason")
        @ApiResponse(responseCode = "200", description = "Rejected; nothing changed")
        @ApiResponse(responseCode = "400", description = "No reason given",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @ApiResponse(responseCode = "403", description = "Your own proposal, or no STEWARDSHIP_APPROVE",
                content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        @PostMapping("/proposals/{proposalId}/reject")
        @PreAuthorize("hasAuthority('PERM_STEWARDSHIP_APPROVE')")
        @Audited(action = "STEWARDSHIP_REJECT", resource = "STEWARDSHIP_PROPOSAL", idVariable = "proposalId")
        public Proposal reject(@PathVariable UUID proposalId,
                               @RequestBody(required = false) ProposalDecision decision, Authentication actor) {
            requireInScope(proposals.find(proposalId).stateCodes());
            return proposals.reject(proposalId, actor.getName(), decision == null ? null : decision.reason());
        }

        /** Every state the decision touches must be in the user's scope (ADR-0019). */
        private static void requireInScope(List<String> states) {
            if (!CurrentUser.scope().allowsAll(states)) {
                throw new AccessDeniedException("The decision touches a state outside your assigned scope");
            }
            AuditContext.state(states.isEmpty() ? null : states.getFirst());
        }
    }

    public record ProposalDecision(@Schema(nullable = true) String reason) {
    }
}
