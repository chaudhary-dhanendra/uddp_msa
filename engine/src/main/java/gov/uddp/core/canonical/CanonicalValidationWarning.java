package gov.uddp.core.canonical;

import java.util.UUID;

public record CanonicalValidationWarning(UUID canonicalResidentId, UUID rawRecordId, UUID batchId, String statePackVersion,
                                         String fieldPath, String rule, String message) { }
