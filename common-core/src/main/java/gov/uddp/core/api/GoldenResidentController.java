package gov.uddp.core.api;

import gov.uddp.core.golden.GoldenBuildSummary;
import gov.uddp.core.golden.GoldenResidentService;
import gov.uddp.core.privacy.MaskPii;
import gov.uddp.core.security.Audited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/golden-residents")
@Tag(name = "Golden Residents")
@MaskPii
public class GoldenResidentController {
    private final GoldenResidentService service;

    public GoldenResidentController(GoldenResidentService service) {
        this.service = service;
    }

    public record BuildRequest(UUID matchingRunId) { }

    @Operation(operationId = "buildGoldenResidents", summary = "Build Golden Residents from a completed matching run")
    @PostMapping("/build")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN') and @access.everyState()")
    @Audited(action = "GOLDEN_BUILD", resource = "MATCHING_RUN")
    public GoldenBuildSummary build(@RequestBody BuildRequest request) {
        return service.build(request.matchingRunId());
    }

    /**
     * Active Golden Residents with their current version; {@code includeRetired} adds retired IDs. This pre-v1 view
     * is not state-scoped, so it is limited to users who see every state (ADR-0019).
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_RESIDENT_VIEW') and @access.everyState()")
    public List<Map<String, Object>> list(@RequestParam(required = false) UUID matchingRunId,
                                          @RequestParam(required = false) String survivorshipPolicyVersion,
                                          @RequestParam(defaultValue = "false") boolean includeRetired) {
        return service.list(matchingRunId, survivorshipPolicyVersion, includeRetired);
    }

    /** Current version of the identity; a retired ID returns 200 with its merge target and resolved active ID. */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_RESIDENT_VIEW') and @access.everyState()")
    @Audited(action = "RESIDENT_360_VIEW", resource = "GOLDEN_RESIDENT", idVariable = "id")
    public Map<String, Object> detail(@PathVariable UUID id) {
        try {
            return service.detail(id);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Golden Resident does not exist");
        }
    }

    /** Every version and lifecycle event of the identity, oldest first. */
    @GetMapping("/{id}/history")
    @PreAuthorize("hasAuthority('PERM_RESIDENT_VIEW') and @access.everyState()")
    @Audited(action = "RESIDENT_HISTORY_VIEW", resource = "GOLDEN_RESIDENT", idVariable = "id")
    public Map<String, Object> history(@PathVariable UUID id) {
        try {
            return service.history(id);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Golden Resident does not exist");
        }
    }
}
