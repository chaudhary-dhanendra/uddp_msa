package gov.uddp.core.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.matching.MatchingImpactService;
import gov.uddp.core.matching.MatchingInsightService;
import gov.uddp.core.matching.MatchingLabelService;
import gov.uddp.core.matching.MatchingMonitoringService;
import gov.uddp.core.matching.MatchingProfileService;
import gov.uddp.core.matching.MatchingProfileService.MatchingProfile;
import gov.uddp.core.matching.MatchingProfileService.MatchingTrainingJob;
import gov.uddp.core.privacy.MaskPii;
import gov.uddp.core.security.Audited;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Matching Studio: versioned profiles with training and maker-checker approval, steward labels, threshold
 * suggestions, impact preview, data readiness, pair explanations, monitoring and threshold-change proposals.
 */
@RestController
@RequestMapping("/api/v1/matching")
@Tag(name = "Matching Studio")
@MaskPii
public class MatchingStudioController {
    private final MatchingProfileService profiles;
    private final MatchingLabelService labels;
    private final MatchingImpactService impact;
    private final MatchingInsightService insight;
    private final MatchingMonitoringService monitoring;

    public MatchingStudioController(MatchingProfileService profiles, MatchingLabelService labels,
                                    MatchingImpactService impact, MatchingInsightService insight,
                                    MatchingMonitoringService monitoring) {
        this.profiles = profiles;
        this.labels = labels;
        this.impact = impact;
        this.insight = insight;
        this.monitoring = monitoring;
    }

    public record MatchingProfileRequest(String name, Map<String, Object> config) {
    }

    public record MatchingActiveModel(String modelVersion, MatchingProfile profile) {
    }

    public record MatchingLabelRequest(UUID recordA, UUID recordB, String label, UUID runId) {
    }

    public record MatchingImpactRequest(UUID baselineRunId, Double autoMatch, Double possibleMatch) {
    }

    public record MatchingBoundRequest(Double minValue, Double maxValue, Double maxRelativeChange) {
    }

    public record MatchingSuggestionRequest(Integer minDecisions, Double minRate) {
    }

    // ---- profiles

    @GetMapping("/profiles")
    @PreAuthorize("hasAuthority('PERM_MATCHING_VIEW')")
    public List<MatchingProfile> list() {
        return profiles.list();
    }

    @GetMapping("/profiles/{id}")
    @PreAuthorize("hasAuthority('PERM_MATCHING_VIEW')")
    public MatchingProfile get(@PathVariable UUID id) {
        return profiles.get(id);
    }

    /** The model matching runs use now: the approved profile, or the built-in default with no profile. */
    @GetMapping("/active-model")
    @PreAuthorize("hasAuthority('PERM_MATCHING_VIEW')")
    public MatchingActiveModel activeModel() {
        return new MatchingActiveModel(profiles.effectiveModelVersion(), profiles.approved().orElse(null));
    }

    @PostMapping("/profiles")
    @PreAuthorize("hasAuthority('PERM_MATCHING_MANAGE')")
    @Audited(action = "MATCHING_PROFILE_CREATE", resource = "MATCHING_PROFILE")
    public MatchingProfile create(@RequestBody MatchingProfileRequest request, Authentication actor) {
        return profiles.create(request.name(), request.config(), actor.getName());
    }

    @PutMapping("/profiles/{id}")
    @PreAuthorize("hasAuthority('PERM_MATCHING_MANAGE')")
    @Audited(action = "MATCHING_PROFILE_UPDATE", resource = "MATCHING_PROFILE", idVariable = "id")
    public MatchingProfile update(@PathVariable UUID id, @RequestBody MatchingProfileRequest request) {
        return profiles.update(id, request.config());
    }

    @PostMapping("/profiles/{id}/train")
    @PreAuthorize("hasAuthority('PERM_MATCHING_MANAGE')")
    @Audited(action = "MATCHING_PROFILE_TRAIN", resource = "MATCHING_PROFILE", idVariable = "id")
    public MatchingTrainingJob train(@PathVariable UUID id, Authentication actor) {
        return profiles.startTraining(id, actor.getName());
    }

    @GetMapping("/profiles/{id}/training-jobs")
    @PreAuthorize("hasAuthority('PERM_MATCHING_VIEW')")
    public List<MatchingTrainingJob> trainingJobs(@PathVariable UUID id) {
        return profiles.jobs(id);
    }

    /** Candidate pairs each blocking rule would generate on the current canonical Residents. */
    @PostMapping("/profiles/{id}/blocking-estimate")
    @PreAuthorize("hasAuthority('PERM_MATCHING_VIEW')")
    @Audited(action = "MATCHING_PROFILE_ESTIMATE", resource = "MATCHING_PROFILE", idVariable = "id")
    public JsonNode blockingEstimate(@PathVariable UUID id) {
        return profiles.estimateBlocking(id);
    }

    @PostMapping("/profiles/{id}/submit")
    @PreAuthorize("hasAuthority('PERM_MATCHING_MANAGE')")
    @Audited(action = "MATCHING_PROFILE_SUBMIT", resource = "MATCHING_PROFILE", idVariable = "id")
    public MatchingProfile submit(@PathVariable UUID id, Authentication actor) {
        return profiles.submit(id, actor.getName());
    }

    @PostMapping("/profiles/{id}/approve")
    @PreAuthorize("hasAuthority('PERM_MATCHING_APPROVE')")
    @Audited(action = "MATCHING_PROFILE_APPROVE", resource = "MATCHING_PROFILE", idVariable = "id")
    public MatchingProfile approve(@PathVariable UUID id, Authentication actor) {
        return profiles.approve(id, actor.getName());
    }

    @PostMapping("/profiles/{id}/reject")
    @PreAuthorize("hasAuthority('PERM_MATCHING_APPROVE')")
    @Audited(action = "MATCHING_PROFILE_REJECT", resource = "MATCHING_PROFILE", idVariable = "id")
    public MatchingProfile reject(@PathVariable UUID id) {
        return profiles.reject(id);
    }

    // ---- labelling, thresholds, impact

    /** Unlabelled pairs sampled across the score bands of a run (the latest completed run by default). */
    @GetMapping("/labels/sample")
    @PreAuthorize("hasAuthority('PERM_MATCHING_VIEW')")
    public List<MatchingLabelService.MatchingSamplePair> sample(@RequestParam(required = false) UUID runId,
                                                        @RequestParam(defaultValue = "5") int perBand) {
        return labels.sample(runId, perBand);
    }

    @PostMapping("/labels")
    @PreAuthorize("hasAuthority('PERM_MATCHING_MANAGE')")
    @Audited(action = "MATCHING_LABEL", resource = "MATCHING_PAIR")
    public MatchingLabelService.MatchingLabel label(@RequestBody MatchingLabelRequest request, Authentication actor) {
        return labels.label(request.recordA(), request.recordB(), request.label(), request.runId(), actor.getName());
    }

    @GetMapping("/labels/summary")
    @PreAuthorize("hasAuthority('PERM_MATCHING_VIEW')")
    public MatchingLabelService.MatchingLabelSummary labelSummary() {
        return labels.summary();
    }

    /** AUTO_MATCH / review thresholds for a target precision, estimated from the labels. */
    @GetMapping("/thresholds/suggestion")
    @PreAuthorize("hasAuthority('PERM_MATCHING_VIEW')")
    public JsonNode suggestion(@RequestParam(required = false) UUID runId,
                               @RequestParam(defaultValue = "0.99") double targetPrecision) {
        return labels.suggestThresholds(runId, targetPrecision);
    }

    /** Dry run of a trained profile against the last FULL run: nothing is written. */
    @PostMapping("/profiles/{id}/impact-preview")
    @PreAuthorize("hasAuthority('PERM_MATCHING_VIEW')")
    @Audited(action = "MATCHING_IMPACT_PREVIEW", resource = "MATCHING_PROFILE", idVariable = "id")
    public MatchingImpactService.MatchingImpact impactPreview(@PathVariable UUID id, @RequestBody MatchingImpactRequest request) {
        return impact.preview(id, request.baselineRunId(), request.autoMatch(), request.possibleMatch());
    }

    // ---- readiness and explanation

    /** Share of canonical Residents with a value for each matching field, per source. */
    @GetMapping("/readiness")
    @PreAuthorize("hasAuthority('PERM_MATCHING_VIEW')")
    public List<MatchingInsightService.MatchingSourceReadiness> readiness() {
        return insight.readiness();
    }

    /** The waterfall that explains why a scored pair matched (or not). */
    @GetMapping("/pairs/explanation")
    @PreAuthorize("hasAnyAuthority('PERM_MATCHING_VIEW', 'PERM_MATCH_EVIDENCE_VIEW')")
    public MatchingInsightService.MatchingExplanation explanation(@RequestParam(required = false) UUID runId,
                                                                  @RequestParam(required = false) UUID recordA,
                                                                  @RequestParam(required = false) UUID recordB) {
        // Checked here, not by the binder, so a caller without the permission is refused (403) before any 400.
        if (runId == null || recordA == null || recordB == null) {
            throw new IllegalArgumentException("runId, recordA and recordB are required");
        }
        return insight.explain(runId, recordA, recordB);
    }

    // ---- monitoring and suggestions

    @GetMapping("/monitoring/metrics")
    @PreAuthorize("hasAuthority('PERM_MATCHING_VIEW')")
    public List<MatchingMonitoringService.MatchingRunMetrics> metrics(@RequestParam(defaultValue = "20") int limit) {
        return monitoring.metrics(Math.max(1, Math.min(limit, 200)));
    }

    @GetMapping("/monitoring/alerts")
    @PreAuthorize("hasAuthority('PERM_MATCHING_VIEW')")
    public List<MatchingMonitoringService.MatchingAlert> alerts(@RequestParam(defaultValue = "true") boolean openOnly) {
        return monitoring.alerts(openOnly);
    }

    @PostMapping("/monitoring/alerts/{alertId}/acknowledge")
    @PreAuthorize("hasAuthority('PERM_MATCHING_MANAGE')")
    @Audited(action = "MATCHING_ALERT_ACKNOWLEDGE", resource = "MATCHING_ALERT", idVariable = "alertId")
    public void acknowledge(@PathVariable UUID alertId, Authentication actor) {
        monitoring.acknowledge(alertId, actor.getName());
    }

    @GetMapping("/monitoring/bounds")
    @PreAuthorize("hasAuthority('PERM_MATCHING_VIEW')")
    public List<MatchingMonitoringService.MatchingBound> bounds() {
        return monitoring.bounds();
    }

    @PutMapping("/monitoring/bounds/{metric}")
    @PreAuthorize("hasAuthority('PERM_MATCHING_MANAGE')")
    @Audited(action = "MATCHING_BOUND_UPDATE", resource = "MATCHING_BOUND", idVariable = "metric")
    public MatchingMonitoringService.MatchingBound updateBound(@PathVariable String metric, @RequestBody MatchingBoundRequest request,
                                                       Authentication actor) {
        return monitoring.updateBound(metric, request.minValue(), request.maxValue(), request.maxRelativeChange(),
                actor.getName());
    }

    @GetMapping("/monitoring/proposals")
    @PreAuthorize("hasAuthority('PERM_MATCHING_VIEW')")
    public List<MatchingMonitoringService.MatchingProposal> proposals(@RequestParam(defaultValue = "false") boolean pendingOnly) {
        return monitoring.proposals(pendingOnly);
    }

    /** Recomputes threshold-change proposals from steward behaviour; nothing changes until a different user approves. */
    @PostMapping("/monitoring/proposals/refresh")
    @PreAuthorize("hasAuthority('PERM_MATCHING_MANAGE')")
    @Audited(action = "MATCHING_PROPOSALS_REFRESH", resource = "MATCHING_PROPOSAL")
    public List<MatchingMonitoringService.MatchingProposal> refreshProposals(@RequestBody MatchingSuggestionRequest request,
                                                                     Authentication actor) {
        return monitoring.refreshSuggestions(actor.getName(),
                request.minDecisions() == null ? MatchingMonitoringService.MIN_DECISIONS : request.minDecisions(),
                request.minRate() == null ? MatchingMonitoringService.MIN_RATE : request.minRate());
    }

    @PostMapping("/monitoring/proposals/{proposalId}/approve")
    @PreAuthorize("hasAuthority('PERM_MATCHING_APPROVE')")
    @Audited(action = "MATCHING_PROPOSAL_APPROVE", resource = "MATCHING_PROPOSAL", idVariable = "proposalId")
    public MatchingMonitoringService.MatchingProposal approveProposal(@PathVariable UUID proposalId, Authentication actor) {
        return monitoring.approve(proposalId, actor.getName());
    }

    @PostMapping("/monitoring/proposals/{proposalId}/reject")
    @PreAuthorize("hasAuthority('PERM_MATCHING_APPROVE')")
    @Audited(action = "MATCHING_PROPOSAL_REJECT", resource = "MATCHING_PROPOSAL", idVariable = "proposalId")
    public MatchingMonitoringService.MatchingProposal rejectProposal(@PathVariable UUID proposalId, Authentication actor) {
        return monitoring.reject(proposalId, actor.getName());
    }
}
