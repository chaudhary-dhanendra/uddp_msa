package gov.uddp.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import gov.uddp.contracts.EventEnvelope;
import gov.uddp.contracts.EventSchemas;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Writes events to the owning service's {@code outbox_event} table on the caller's connection, so the event commits
 * or rolls back with the domain write (docs/msa-design.md, section 5.1). The relay publishes it later.
 */
public final class Outbox {

    /** The DDL every producing service puts in its own Flyway history (unqualified: it lands in the own schema). */
    public static final String DDL = """
            CREATE TABLE outbox_event (
              event_id uuid PRIMARY KEY,
              topic text NOT NULL,
              partition_key text NOT NULL,
              event_type text NOT NULL,
              payload jsonb NOT NULL,
              created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
              published_at timestamptz
            );
            CREATE INDEX outbox_event_unpublished_idx ON outbox_event (created_at) WHERE published_at IS NULL;
            CREATE TABLE processed_event (
              consumer text NOT NULL,
              event_id uuid NOT NULL,
              processed_at timestamptz NOT NULL DEFAULT now(),
              PRIMARY KEY (consumer, event_id)
            );
            """;

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Outbox() {
    }

    /**
     * Validates the envelope (and its payload, when a schema for {@code <eventType minus uddp.>.v<n>} exists) and
     * appends it. Returns false when an event with the same id is already in the outbox, which makes producers that
     * use {@link #deterministicId} idempotent.
     */
    public static boolean append(Connection c, String topic, EventEnvelope envelope) throws SQLException {
        JsonNode document = JSON.valueToTree(envelope);
        var problems = EventSchemas.validate(EventSchemas.ENVELOPE, document);
        String payloadSchema = envelope.eventType().replaceFirst("^uddp\\.", "") + ".v" + envelope.schemaVersion();
        if (problems.isEmpty() && EventSchemas.exists(payloadSchema)) {
            problems = EventSchemas.validate(payloadSchema, envelope.payload());
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid " + envelope.eventType() + " event: " + problems);
        }
        try (var s = c.prepareStatement("insert into outbox_event (event_id, topic, partition_key, event_type, payload)"
                + " values (?, ?, ?, ?, cast(? as jsonb)) on conflict (event_id) do nothing")) {
            s.setObject(1, envelope.eventId());
            s.setString(2, topic);
            s.setString(3, envelope.partitionKey());
            s.setString(4, envelope.eventType());
            s.setString(5, document.toString());
            return s.executeUpdate() == 1;
        }
    }

    /** A name-based id for an event derived from an immutable row, so a rerun produces the same id. */
    public static UUID deterministicId(String eventType, String sourceId) {
        return UUID.nameUUIDFromBytes((eventType + "|" + sourceId).getBytes(StandardCharsets.UTF_8));
    }

    /** The mapper used for envelopes (ISO-8601 dates). */
    public static ObjectMapper json() {
        return JSON;
    }
}
