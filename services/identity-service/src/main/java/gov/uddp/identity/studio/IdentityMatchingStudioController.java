package gov.uddp.identity.studio;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.matching.MatchingImpactService;
import gov.uddp.core.matching.MatchingInsightService;
import gov.uddp.core.matching.MatchingLabelService;
import gov.uddp.core.matching.MatchingMonitoringService;
import gov.uddp.core.matching.MatchingProfileService;
import gov.uddp.core.matching.MatchingProfileService.MatchingProfile;
import gov.uddp.core.matching.MatchingProfileService.MatchingTrainingJob;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Matching Studio in identity-service, which owns the matching tables (moved from the edge, MSA). The edge keeps
 * its own endpoint, checks permissions there and forwards here with the user's name in X-UDDP-Actor.
 *
 * Matching Studio: versioned profiles with training and maker-checker approval, steward labels, threshold
 * suggestions, impact preview, data readiness, pair explanations, monitoring and threshold-change proposals.
 */
@RestController
@RequestMapping("/api/v1/matching")
public class IdentityMatchingStudioController {
    private final MatchingProfileService profiles;
    private final MatchingLabelService labels;
    private final MatchingImpactService impact;
    private final MatchingInsightService insight;
    private final MatchingMonitoringService monitoring;

    public IdentityMatchingStudioController(MatchingProfileService profiles, MatchingLabelService labels,
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
    public List<MatchingProfile> list() {
        return profiles.list();
    }

    @GetMapping("/profiles/{id}")
    public MatchingProfile get(@PathVariable UUID id) {
        return profiles.get(id);
    }

    /** The model matching runs use now: the approved profile, or the built-in default with no profile. */
    @GetMapping("/active-model")
    public MatchingActiveModel activeModel() {
        return new MatchingActiveModel(profiles.effectiveModelVersion(), profiles.approved().orElse(null));
    }

    @PostMapping("/profiles")
    public MatchingProfile create(@RequestBody MatchingProfileRequest request, @org.springframework.web.bind.annotation.RequestHeader(value = "X-UDDP-Actor", defaultValue = "system") String actor) {
        return profiles.create(request.name(), request.config(), actor);
    }

    @PutMapping("/profiles/{id}")
    public MatchingProfile update(@PathVariable UUID id, @RequestBody MatchingProfileRequest request) {
        return profiles.update(id, request.config());
    }

    @PostMapping("/profiles/{id}/train")
    public MatchingTrainingJob train(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestHeader(value = "X-UDDP-Actor", defaultValue = "system") String actor) {
        return profiles.startTraining(id, actor);
    }

    @GetMapping("/profiles/{id}/training-jobs")
    public List<MatchingTrainingJob> trainingJobs(@PathVariable UUID id) {
        return profiles.jobs(id);
    }

    /** Candidate pairs each blocking rule would generate on the current canonical Residents. */
    @PostMapping("/profiles/{id}/blocking-estimate")
    public JsonNode blockingEstimate(@PathVariable UUID id) {
        return profiles.estimateBlocking(id);
    }

    @PostMapping("/profiles/{id}/submit")
    public MatchingProfile submit(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestHeader(value = "X-UDDP-Actor", defaultValue = "system") String actor) {
        return profiles.submit(id, actor);
    }

    @PostMapping("/profiles/{id}/approve")
    public MatchingProfile approve(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestHeader(value = "X-UDDP-Actor", defaultValue = "system") String actor) {
        return profiles.approve(id, actor);
    }

    @PostMapping("/profiles/{id}/reject")
    public MatchingProfile reject(@PathVariable UUID id) {
        return profiles.reject(id);
    }

    // ---- labelling, thresholds, impact

    /** Unlabelled pairs sampled across the score bands of a run (the latest completed run by default). */
    @GetMapping("/labels/sample")
    public List<MatchingLabelService.MatchingSamplePair> sample(@RequestParam(required = false) UUID runId,
                                                        @RequestParam(defaultValue = "5") int perBand) {
        return labels.sample(runId, perBand);
    }

    @PostMapping("/labels")
    public MatchingLabelService.MatchingLabel label(@RequestBody MatchingLabelRequest request, @org.springframework.web.bind.annotation.RequestHeader(value = "X-UDDP-Actor", defaultValue = "system") String actor) {
        return labels.label(request.recordA(), request.recordB(), request.label(), request.runId(), actor);
    }

    @GetMapping("/labels/summary")
    public MatchingLabelService.MatchingLabelSummary labelSummary() {
        return labels.summary();
    }

    /** AUTO_MATCH / review thresholds for a target precision, estimated from the labels. */
    @GetMapping("/thresholds/suggestion")
    public JsonNode suggestion(@RequestParam(required = false) UUID runId,
                               @RequestParam(defaultValue = "0.99") double targetPrecision) {
        return labels.suggestThresholds(runId, targetPrecision);
    }

    /** Dry run of a trained profile against the last FULL run: nothing is written. */
    @PostMapping("/profiles/{id}/impact-preview")
    public MatchingImpactService.MatchingImpact impactPreview(@PathVariable UUID id, @RequestBody MatchingImpactRequest request) {
        return impact.preview(id, request.baselineRunId(), request.autoMatch(), request.possibleMatch());
    }

    // ---- readiness and explanation

    /** Share of canonical Residents with a value for each matching field, per source. */
    @GetMapping("/readiness")
    public List<MatchingInsightService.MatchingSourceReadiness> readiness() {
        return insight.readiness();
    }

    /** The waterfall that explains why a scored pair matched (or not). */
    @GetMapping("/pairs/explanation")
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
    public List<MatchingMonitoringService.MatchingRunMetrics> metrics(@RequestParam(defaultValue = "20") int limit) {
        return monitoring.metrics(Math.max(1, Math.min(limit, 200)));
    }

    @GetMapping("/monitoring/alerts")
    public List<MatchingMonitoringService.MatchingAlert> alerts(@RequestParam(defaultValue = "true") boolean openOnly) {
        return monitoring.alerts(openOnly);
    }

    @PostMapping("/monitoring/alerts/{alertId}/acknowledge")
    public void acknowledge(@PathVariable UUID alertId, @org.springframework.web.bind.annotation.RequestHeader(value = "X-UDDP-Actor", defaultValue = "system") String actor) {
        monitoring.acknowledge(alertId, actor);
    }

    @GetMapping("/monitoring/bounds")
    public List<MatchingMonitoringService.MatchingBound> bounds() {
        return monitoring.bounds();
    }

    @PutMapping("/monitoring/bounds/{metric}")
    public MatchingMonitoringService.MatchingBound updateBound(@PathVariable String metric, @RequestBody MatchingBoundRequest request,
                                                       @org.springframework.web.bind.annotation.RequestHeader(value = "X-UDDP-Actor", defaultValue = "system") String actor) {
        return monitoring.updateBound(metric, request.minValue(), request.maxValue(), request.maxRelativeChange(),
                actor);
    }

    @GetMapping("/monitoring/proposals")
    public List<MatchingMonitoringService.MatchingProposal> proposals(@RequestParam(defaultValue = "false") boolean pendingOnly) {
        return monitoring.proposals(pendingOnly);
    }

    /** Recomputes threshold-change proposals from steward behaviour; nothing changes until a different user approves. */
    @PostMapping("/monitoring/proposals/refresh")
    public List<MatchingMonitoringService.MatchingProposal> refreshProposals(@RequestBody MatchingSuggestionRequest request,
                                                                     @org.springframework.web.bind.annotation.RequestHeader(value = "X-UDDP-Actor", defaultValue = "system") String actor) {
        return monitoring.refreshSuggestions(actor,
                request.minDecisions() == null ? MatchingMonitoringService.MIN_DECISIONS : request.minDecisions(),
                request.minRate() == null ? MatchingMonitoringService.MIN_RATE : request.minRate());
    }

    @PostMapping("/monitoring/proposals/{proposalId}/approve")
    public MatchingMonitoringService.MatchingProposal approveProposal(@PathVariable UUID proposalId, @org.springframework.web.bind.annotation.RequestHeader(value = "X-UDDP-Actor", defaultValue = "system") String actor) {
        return monitoring.approve(proposalId, actor);
    }

    @PostMapping("/monitoring/proposals/{proposalId}/reject")
    public MatchingMonitoringService.MatchingProposal rejectProposal(@PathVariable UUID proposalId, @org.springframework.web.bind.annotation.RequestHeader(value = "X-UDDP-Actor", defaultValue = "system") String actor) {
        return monitoring.reject(proposalId, actor);
    }
}
