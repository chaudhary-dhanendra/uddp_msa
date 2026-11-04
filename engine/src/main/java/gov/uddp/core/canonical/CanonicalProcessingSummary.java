package gov.uddp.core.canonical;

import java.util.UUID;

public record CanonicalProcessingSummary(UUID batchId, int recordsRead, int recordsMapped, int recordsSkipped,
                                         int recordsFailed, int recordsHeld) {
    public CanonicalProcessingSummary(UUID batchId, int recordsRead, int recordsMapped, int recordsSkipped,
                                      int recordsFailed) {
        this(batchId, recordsRead, recordsMapped, recordsSkipped, recordsFailed, 0);
    }
}
