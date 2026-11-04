package gov.uddp.core.api;

import gov.uddp.core.canonical.CanonicalProcessingSummary;
import gov.uddp.core.canonical.CanonicalResidentProcessingService;
import gov.uddp.core.security.Audited;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/canonical/residents")
public class CanonicalResidentController {
    private final CanonicalResidentProcessingService processing;
    public CanonicalResidentController(CanonicalResidentProcessingService processing) { this.processing = processing; }
    @PostMapping("/process/batches/{batchId}") @ResponseStatus(HttpStatus.OK)
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN') and @access.batchInScope(#batchId)")
    @Audited(action = "CANONICAL_PROCESS_BATCH", resource = "INGESTION_BATCH", idVariable = "batchId")
    public CanonicalProcessingSummary processBatch(@PathVariable UUID batchId) { return processing.processBatch(batchId); }
}
