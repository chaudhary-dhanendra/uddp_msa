package gov.uddp.core.canonical;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Immutable canonical Resident version and direct Raw lineage. */
public record CanonicalResidentRecord(UUID canonicalResidentId, UUID rawRecordId, UUID batchId,
                                     String stateCode, String sourceSystem, String sourceEntity,
                                     String statePackVersion, String canonicalPayload,
                                     OffsetDateTime mappedAt, OffsetDateTime createdAt) { }
