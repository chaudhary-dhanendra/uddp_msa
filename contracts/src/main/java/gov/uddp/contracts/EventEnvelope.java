package gov.uddp.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/**
 * The envelope every UDDP event and command carries (docs/msa-design.md, section 3.1). Its JSON Schema is
 * {@code events/envelope.v1.schema.json}; the payload has its own schema per event type and version.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String producer,
        String partitionKey,
        UUID correlationId,
        UUID causationId,
        String stateCode,
        StatePackRef statePack,
        JsonNode payload) {
}
