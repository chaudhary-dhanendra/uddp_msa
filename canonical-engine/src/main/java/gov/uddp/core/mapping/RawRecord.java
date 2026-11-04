package gov.uddp.core.mapping;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Source-faithful landing record, before semantic interpretation. */
public record RawRecord(UUID rawRecordId, UUID batchId, String stateCode, String sourceSystem,
                        OffsetDateTime ingestedAt, String rawPayload, Map<String, Object> sourceMetadata,
                        String statePackVersion, String sourceSchemaVersion, String pipelineTemplate) {
    public String sourceEntity() {
        Object value = sourceMetadata == null ? null : sourceMetadata.get("sourceEntity");
        return value == null ? sourceSystem : value.toString();
    }
}
