package gov.uddp.core.onboarding;

import gov.uddp.core.canonical.CanonicalProcessingSummary;
import java.util.UUID;

/**
 * The onboarding MAPPED stage's boundary to canonical processing. {@link gov.uddp.core.canonical.CanonicalResidentProcessingService} maps the
 * batch in-process (the default); {@code RemoteCanonicalProcessor} waits for canonical-service to have mapped it
 * (MSA Phase 3). Either way the batch's canonical records are in this database when it returns.
 */
public interface CanonicalBatchProcessor {

    CanonicalProcessingSummary processBatch(UUID batchId);
}
