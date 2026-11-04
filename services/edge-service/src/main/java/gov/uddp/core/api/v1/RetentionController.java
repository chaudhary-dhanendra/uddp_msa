package gov.uddp.core.api.v1;

import gov.uddp.core.privacy.RetentionService;
import gov.uddp.core.privacy.RetentionService.Policy;
import gov.uddp.core.privacy.RetentionService.RunResult;
import gov.uddp.core.security.AuditContext;
import gov.uddp.core.security.Audited;
import gov.uddp.core.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Data retention (P1 Part 5b), for platform administrators: the configured days per data category, and a manual
 * purge. A dry run (the default) only reports what would be purged; every run is audited with its counts.
 */
@RestController
@RequestMapping("/api/v1/retention")
@Tag(name = "Retention")
public class RetentionController {
    private final RetentionService retention;

    public RetentionController(RetentionService retention) {
        this.retention = retention;
    }

    @Operation(operationId = "getRetentionPolicy", summary = "Days to keep per data category (null keeps forever)")
    @GetMapping("/policy")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    public Policy policy() {
        return retention.policy();
    }

    @Operation(operationId = "purgeRetention",
            summary = "Purges expired payloads, keeping IDs and lineage; dryRun (default) only counts")
    @PostMapping("/purge")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    @Audited(action = "RETENTION_PURGE_REQUEST", resource = "RETENTION")
    public RunResult purge(@RequestParam(defaultValue = "true") boolean dryRun) {
        // The service audits the run itself (RETENTION_PURGE, with its counts); this records the request.
        RunResult result = retention.purge(dryRun, CurrentUser.username());
        AuditContext.resource(result.runId());
        AuditContext.detail("dryRun", dryRun);
        return result;
    }
}
