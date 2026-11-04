package gov.uddp.core.canonical;

import gov.uddp.core.held.FixTarget;
import gov.uddp.core.validation.ReasonCodes;
import java.util.UUID;

/**
 * A row that could not become canonical, with its reason code and where in the pack it is fixed: the pack and source
 * it was mapped with and the fix target (V110; null when the pack was not found).
 */
public record CanonicalProcessingError(UUID rawRecordId, UUID batchId, String statePackVersion, String message,
                                       String reasonCode, String packId, String sourceId, FixTarget fix) {
    public CanonicalProcessingError(UUID rawRecordId, UUID batchId, String statePackVersion, String message,
                                    String reasonCode) {
        this(rawRecordId, batchId, statePackVersion, message, reasonCode, null, null,
                FixTarget.of(reasonCode, null));
    }

    public CanonicalProcessingError(UUID rawRecordId, UUID batchId, String statePackVersion, String message) {
        this(rawRecordId, batchId, statePackVersion, message, ReasonCodes.OTHER);
    }
}