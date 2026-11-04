package gov.uddp.core.api;

import gov.uddp.core.onboarding.OnboardingNotFoundException;
import gov.uddp.core.onboarding.OnboardingService;
import gov.uddp.core.onboarding.OnboardingStage;
import gov.uddp.core.onboarding.StatePackLifecycleService;
import gov.uddp.core.security.AuditContext;
import gov.uddp.core.security.Audited;
import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.statepack.StatePackLoader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Minimal synchronous control plane; work state is persisted so a later worker can use the same contract. */
@RestController
@RequestMapping("/api/onboarding")
@Tag(name = "Onboarding")
public class OnboardingController {
    private final OnboardingService onboarding;
    private final StatePackLifecycleService lifecycle;
    private final StatePackLoader packs;
    private gov.uddp.core.onboarding.LandingFolder landing;
    private final java.util.concurrent.Executor background = java.util.concurrent.Executors.newCachedThreadPool();

    /** Enables landing-folder sources (large deliveries copied into the source's folder instead of uploaded). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLandingFolder(gov.uddp.core.onboarding.LandingFolder landing) {
        this.landing = landing;
    }

    public OnboardingController(OnboardingService onboarding, StatePackLifecycleService lifecycle,
                                StatePackLoader packs) {
        this.onboarding = onboarding;
        this.lifecycle = lifecycle;
        this.packs = packs;
    }

    @PostMapping("/state-packs/{packId}/register")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_PUBLISH')")
    @Audited(action = "STATE_PACK_REGISTER", resource = "STATE_PACK", idVariable = "packId")
    public Map<String, UUID> register(@PathVariable String packId) {
        requirePackInScope(packId);
        return Map.of("statePackId", lifecycle.register(packId));
    }

    @PostMapping("/state-packs/{packId}/{version}/transitions/{target}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_PUBLISH')")
    @Audited(action = "STATE_PACK_TRANSITION", resource = "STATE_PACK", idVariable = "packId")
    public void transition(@PathVariable String packId, @PathVariable String version, @PathVariable String target) {
        requirePackInScope(packId);
        lifecycle.transition(packId, version, target);
    }

    /**
     * Creates a run; mode FULL also executes every stage, STEP (the default) executes none. processingMode FULL (the
     * default) or INCREMENTAL selects full or incremental matching and Golden build.
     */
    @Operation(operationId = "startOnboardingRun", summary = "Create a run; mode FULL also executes every stage")
    @ApiResponse(responseCode = "201", description = "Run created",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WriteApiSchemas.StartRunResponse.class)))
    @ApiResponse(responseCode = "default", description = "Error",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WriteApiSchemas.OnboardingErrorBody.class)))
    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN')")
    @Audited(action = "ONBOARDING_START", resource = "ONBOARDING_RUN")
    public Map<String, Object> start(@RequestBody Start request) {
        if (blank(request.packId()) || blank(request.version()) || blank(request.sourceSystem())) {
            throw new IllegalArgumentException("packId, version and sourceSystem are required");
        }
        requirePackInScope(request.packId());
        Mode mode = request.mode() == null ? Mode.STEP : request.mode();
        var processing = request.processingMode() == null
                ? OnboardingService.ProcessingMode.FULL : request.processingMode();
        UUID run = onboarding.start(request.packId(), request.version(), request.sourceSystem(), processing);
        if (mode == Mode.FULL) {
            onboarding.runToCompletion(run);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run);
        out.put("mode", mode);
        out.put("processingMode", processing);
        out.put("status", onboarding.detail(run).get("status"));
        return out;
    }

    @GetMapping("/runs/{runId}")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
    public Map<String, Object> detail(@PathVariable UUID runId) {
        return runInScope(runId);
    }

    /** Run history for a state, newest first; the path value is a State Pack ID or state code. */
    @GetMapping("/states/{state}/runs")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
    public List<Map<String, Object>> history(@PathVariable String state) {
        requirePackInScope(state);
        return onboarding.history(state);
    }

    @Operation(operationId = "runNextOnboardingStage", summary = "Execute the run's next stage")
    @ApiResponse(responseCode = "204", description = "Stage executed")
    @ApiResponse(responseCode = "default", description = "Error",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WriteApiSchemas.OnboardingErrorBody.class)))
    @PostMapping("/runs/{runId}/next")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN')")
    @Audited(action = "ONBOARDING_NEXT_STAGE", resource = "ONBOARDING_RUN", idVariable = "runId")
    public void next(@PathVariable UUID runId) {
        runInScope(runId);
        onboarding.executeNext(runId);
    }

    /** Executes one named stage; it must be the run's next stage. */
    @PostMapping("/runs/{runId}/stages/{stage}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN')")
    @Audited(action = "ONBOARDING_STAGE", resource = "ONBOARDING_RUN", idVariable = "runId")
    public void stage(@PathVariable UUID runId, @PathVariable OnboardingStage stage) {
        runInScope(runId);
        onboarding.executeStage(runId, stage);
    }

    /** The files waiting in a landing-folder source's folder. */
    @GetMapping("/state-packs/{packId}/sources/{sourceSystem}/landing")
    @Operation(operationId = "getLandingFolder", summary = "Files waiting in a source's landing folder")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
    public Map<String, Object> landingFolder(@PathVariable String packId, @PathVariable String sourceSystem) {
        requirePackInScope(packId);
        var source = landingSource(packId, sourceSystem);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("location", source.location());
        out.put("waiting", landing.waiting(source).stream().map(f -> f.getFileName().toString()).toList());
        return out;
    }

    /**
     * Starts a run over the oldest file waiting in the landing folder and returns at once (202); the run proceeds in the
     * background and its progress is read from the run. 409 when no file is waiting.
     */
    @PostMapping("/state-packs/{packId}/sources/{sourceSystem}/landing/start")
    @Operation(operationId = "startLandingRun", summary = "Ingest the oldest file waiting in the landing folder")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN')")
    @Audited(action = "ONBOARDING_START", resource = "ONBOARDING_RUN")
    public Map<String, Object> startLanding(@PathVariable String packId, @PathVariable String sourceSystem) {
        requirePackInScope(packId);
        var source = landingSource(packId, sourceSystem);
        if (landing.waiting(source).isEmpty()) {
            throw new gov.uddp.core.onboarding.OnboardingConflictException(
                    "No file is waiting in the landing folder " + source.location());
        }
        var pack = packs.find(packId).orElseThrow();
        UUID run = onboarding.start(pack.manifest().packId(), pack.manifest().version(), sourceSystem,
                OnboardingService.ProcessingMode.FULL);
        background.execute(() -> {
            try {
                onboarding.runToCompletion(run);
            } catch (RuntimeException failed) {
                // The failed stage is recorded on the run, which stays resumable.
            }
        });
        return Map.of("runId", run);
    }

    private gov.uddp.core.statepack.StatePackAssets.SourceDefinition landingSource(String packId, String sourceSystem) {
        var pack = packs.find(packId).orElseThrow(() -> new OnboardingNotFoundException("Unknown State Pack: " + packId));
        var source = pack.sources().stream().filter(s -> s.sourceId().equals(sourceSystem)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown State Pack source: " + sourceSystem));
        if (landing == null || !landing.isLandingFolder(source)) {
            throw new IllegalArgumentException("Source " + sourceSystem + " has no landing folder");
        }
        return source;
    }

    /** The rows the run rejected while mapping to canonical records: record ID, reason and original row, as CSV. */
    @GetMapping(value = "/runs/{runId}/rejected-rows.csv", produces = "text/csv")
    @Operation(operationId = "downloadRejectedRows", summary = "CSV of the rows the run rejected")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN')")
    @Audited(action = "ONBOARDING_REJECTED_ROWS_DOWNLOAD", resource = "ONBOARDING_RUN", idVariable = "runId")
    public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody>
            rejectedRows(@PathVariable UUID runId) {
        runInScope(runId);
        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body = out ->
                onboarding.writeRejectedRows(runId, new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8));
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"rejected-rows-" + runId + ".csv\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(body);
    }

    /** Retries the failed stage of a FAILED run as a new attempt. */
    @Operation(operationId = "resumeOnboardingRun", summary = "Retry the failed stage of a FAILED run")
    @ApiResponse(responseCode = "204", description = "Stage retried")
    @ApiResponse(responseCode = "default", description = "Error",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = WriteApiSchemas.OnboardingErrorBody.class)))
    @PostMapping("/runs/{runId}/resume")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN')")
    @Audited(action = "ONBOARDING_RESUME", resource = "ONBOARDING_RUN", idVariable = "runId")
    public void resume(@PathVariable UUID runId) {
        runInScope(runId);
        onboarding.resume(runId);
    }

    /**
     * The pack's state must be in the user's scope (ADR-0019); an unknown pack is left to the service's 404. The
     * state becomes the audited state of the request.
     */
    private void requirePackInScope(String packIdOrStateCode) {
        if (packIdOrStateCode == null) {
            return;
        }
        packs.find(packIdOrStateCode).ifPresent(pack -> {
            String state = pack.manifest().stateCode();
            if (!CurrentUser.scope().allows(state)) {
                throw new OnboardingNotFoundException("Unknown State Pack: " + packIdOrStateCode);
            }
            AuditContext.state(state);
        });
    }

    /** The run, when its state is in the user's scope; otherwise 404 as for an unknown run. */
    private Map<String, Object> runInScope(UUID runId) {
        Map<String, Object> run = onboarding.detail(runId);
        String state = (String) run.get("stateCode");
        if (!CurrentUser.scope().allows(state)) {
            throw new OnboardingNotFoundException("Unknown onboarding run: " + runId);
        }
        AuditContext.state(state);
        return run;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public enum Mode { STEP, FULL }

    public record Start(String packId, String version, String sourceSystem, Mode mode,
                        OnboardingService.ProcessingMode processingMode) {
    }
}
