package gov.uddp.core.model;

import java.time.OffsetDateTime;

public record RecordMetadata(String stateCode, String sourceSystem, String sourceRecordId,
                             String ingestionBatchId, OffsetDateTime ingestionTimestamp,
                             String statePackVersion, String mappingVersion, String lineageId) { }
