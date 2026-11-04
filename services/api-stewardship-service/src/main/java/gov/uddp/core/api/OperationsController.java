package gov.uddp.core.api;

import gov.uddp.core.connector.ConnectorService;
import gov.uddp.core.gates.QualityGateService;
import gov.uddp.core.gates.ReleaseService;
import gov.uddp.core.onboarding.DeliveryChangeService;
import gov.uddp.core.onboarding.DeliveryChangeService.RemovalHandling;
import gov.uddp.core.onboarding.OnboardingNotFoundException;
import gov.uddp.core.onboarding.OnboardingService;
import gov.uddp.core.onboarding.SchedulerService;
import gov.uddp.core.onboarding.SchedulerService.CatchUp;
import gov.uddp.core.onboarding.SchedulerService.Schedule;
import gov.uddp.core.security.AuditContext;
import gov.uddp.core.security.Audited;
import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.statepack.StatePackLoader;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Continuous operation of a source (Track C): its schedule and what happens to records that stop being delivered. */
@RestController
@RequestMapping("/api/operations/sources/{state}/{source}")
@Tag(name = "Operations")
public class OperationsController {
    private final SchedulerService scheduler;
    private final DeliveryChangeService changes;
    private final StatePackLoader packs;
    private final ConnectorService connectors;
    private final QualityGateService gates;
    private final ReleaseService releases;
    private final OnboardingService onboarding;

    public OperationsController(SchedulerService scheduler, DeliveryChangeService changes, StatePackLoader packs,
                                ConnectorService connectors, QualityGateService gates, ReleaseService releases,
                                OnboardingService onboarding) {
        this.connectors = connectors;
        this.gates = gates;
        this.releases = releases;
        this.onboarding = onboarding;
        this.scheduler = scheduler;
        this.changes = changes;
        this.packs = packs;
    }

    public record ScheduleRequest(String cron, String timezone, Boolean enabled, CatchUp catchUp) {
    }

    public record DeliveryPolicyRequest(RemovalHandling removalHandling) {
    }

    @GetMapping("/schedule")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
    public Schedule schedule(@PathVariable String state, @PathVariable String source) {
        String code = inScope(state, source);
        return scheduler.find(code, source).orElse(new Schedule(code, source, "", "UTC", false, CatchUp.RUN_ONCE));
    }

    @PutMapping("/schedule")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN')")
    @Audited(action = "SOURCE_SCHEDULE_SAVE", resource = "SOURCE_SCHEDULE", idVariable = "source")
    public Schedule saveSchedule(@PathVariable String state, @PathVariable String source,
                                 @RequestBody ScheduleRequest request) {
        String code = inScope(state, source);
        if (request.cron() == null || request.cron().isBlank()) {
            throw new IllegalArgumentException("cron is required");
        }
        try {
            return scheduler.save(new Schedule(code, source, request.cron().trim(),
                    request.timezone() == null || request.timezone().isBlank() ? "UTC" : request.timezone().trim(),
                    request.enabled() == null || request.enabled(),
                    request.catchUp() == null ? CatchUp.RUN_ONCE : request.catchUp()));
        } catch (java.time.DateTimeException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid cron expression or timezone: " + e.getMessage());
        }
    }

    @GetMapping("/schedule/triggers")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
    public List<Map<String, Object>> triggers(@PathVariable String state, @PathVariable String source) {
        return scheduler.triggers(inScope(state, source), source);
    }

    @GetMapping("/delivery-policy")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
    public DeliveryPolicyRequest deliveryPolicy(@PathVariable String state, @PathVariable String source) {
        return new DeliveryPolicyRequest(changes.removalHandling(inScope(state, source), source));
    }

    @PutMapping("/delivery-policy")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN')")
    @Audited(action = "SOURCE_DELIVERY_POLICY_SAVE", resource = "SOURCE_DELIVERY_POLICY", idVariable = "source")
    public DeliveryPolicyRequest saveDeliveryPolicy(@PathVariable String state, @PathVariable String source,
                                                    @RequestBody DeliveryPolicyRequest request) {
        if (request.removalHandling() == null) {
            throw new IllegalArgumentException("removalHandling is required");
        }
        changes.setRemovalHandling(inScope(state, source), source, request.removalHandling());
        return request;
    }

    /** Connector settings; credentials are write-only and only reach the encrypted settings store. */
    public record ConnectorRequest(String type, Map<String, String> settings, Map<String, String> credentials) {
    }

    @GetMapping("/connector")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
    public ConnectorService.Saved connector(@PathVariable String state, @PathVariable String source) {
        return connectors.find(inScope(state, source), source)
                .orElseThrow(() -> new OnboardingNotFoundException("The source has no connector"));
    }

    @PutMapping("/connector")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN')")
    @Audited(action = "SOURCE_CONNECTOR_SAVE", resource = "SOURCE_CONNECTOR", idVariable = "source")
    public ConnectorService.Saved saveConnector(@PathVariable String state, @PathVariable String source,
                                                @RequestBody ConnectorRequest request) {
        if (request.type() == null || request.settings() == null) {
            throw new IllegalArgumentException("type and settings are required");
        }
        return connectors.save(inScope(state, source), source, request.type(), request.settings(),
                request.credentials() == null ? Map.of() : request.credentials());
    }

    @PostMapping("/connector/test")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN')")
    @Audited(action = "SOURCE_CONNECTOR_TEST", resource = "SOURCE_CONNECTOR", idVariable = "source")
    public Map<String, Object> testConnector(@PathVariable String state, @PathVariable String source) {
        connectors.test(inScope(state, source), source);
        return Map.of("ok", true);
    }

    @GetMapping("/connector/preview")
    @PreAuthorize("hasAuthority('PERM_SOURCE_PROFILE')")
    public gov.uddp.core.studio.CsvProfiler.Table previewConnector(@PathVariable String state,
                                                                  @PathVariable String source,
                                                                  @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int rows) {
        return connectors.preview(inScope(state, source), source, rows);
    }

    @GetMapping("/connector/profile")
    @PreAuthorize("hasAuthority('PERM_SOURCE_PROFILE')")
    public gov.uddp.core.studio.StudioModels.Profile profileConnector(@PathVariable String state,
                                                                     @PathVariable String source) {
        return connectors.profile(inScope(state, source), source);
    }

    public record GateRequest(List<QualityGateService.Threshold> gates) {
    }

    public record ReleaseRequest(String reason) {
    }

    @GetMapping("/gates")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
    public List<QualityGateService.Threshold> gates(@PathVariable String state, @PathVariable String source) {
        return gates.thresholds(inScope(state, source), source);
    }

    @PutMapping("/gates")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN')")
    @Audited(action = "SOURCE_GATES_SAVE", resource = "SOURCE_QUALITY_GATE", idVariable = "source")
    public List<QualityGateService.Threshold> saveGates(@PathVariable String state, @PathVariable String source,
                                                        @RequestBody GateRequest request) {
        if (request.gates() == null) {
            throw new IllegalArgumentException("gates are required");
        }
        gates.saveThresholds(inScope(state, source), source, request.gates());
        return request.gates();
    }

    @PostMapping("/gates/dry-run")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN')")
    @Audited(action = "SOURCE_GATES_DRY_RUN", resource = "SOURCE_QUALITY_GATE", idVariable = "source")
    public List<QualityGateService.Result> dryRun(@PathVariable String state, @PathVariable String source) {
        return gates.dryRun(inScope(state, source), source);
    }

    /** Gate results and release decisions of a run, for the run history screen. */
    @GetMapping("/runs/{runId}/release")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
    public Map<String, Object> release(@PathVariable String state, @PathVariable String source, @PathVariable UUID runId) {
        runInScope(state, source, runId);
        return Map.of("gates", gates.results(runId), "decisions", releases.decisions(runId));
    }

    @PostMapping("/runs/{runId}/release/approve")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_APPROVE')")
    @Audited(action = "ONBOARDING_RELEASE_APPROVE", resource = "ONBOARDING_RUN", idVariable = "runId")
    public Map<String, Object> approveRelease(@PathVariable String state, @PathVariable String source,
                                              @PathVariable UUID runId, @RequestBody(required = false) ReleaseRequest body) {
        runInScope(state, source, runId);
        releases.approve(runId, body == null ? null : body.reason());
        onboarding.runToCompletion(runId);
        return onboarding.detail(runId);
    }

    @PostMapping("/runs/{runId}/release/reject")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_APPROVE')")
    @Audited(action = "ONBOARDING_RELEASE_REJECT", resource = "ONBOARDING_RUN", idVariable = "runId")
    public Map<String, Object> rejectRelease(@PathVariable String state, @PathVariable String source,
                                             @PathVariable UUID runId, @RequestBody ReleaseRequest body) {
        runInScope(state, source, runId);
        releases.reject(runId, body == null ? null : body.reason());
        return onboarding.detail(runId);
    }

    private void runInScope(String state, String source, UUID runId) {
        String code = inScope(state, source);
        Map<String, Object> run = onboarding.detail(runId);
        if (!code.equals(run.get("stateCode")) || !source.equals(run.get("sourceSystem"))) {
            throw new OnboardingNotFoundException("Unknown onboarding run: " + runId);
        }
    }

    /** The state code, when the pack and its source exist and the state is in the user's scope; otherwise 404. */
    private String inScope(String state, String source) {
        var pack = packs.find(state)
                .filter(p -> p.sources().stream().anyMatch(s -> s.sourceId().equals(source)))
                .orElseThrow(() -> new OnboardingNotFoundException("Unknown source: " + state + "/" + source));
        String code = pack.manifest().stateCode();
        if (!CurrentUser.scope().allows(code)) {
            throw new OnboardingNotFoundException("Unknown source: " + state + "/" + source);
        }
        AuditContext.state(code);
        return code;
    }
}
