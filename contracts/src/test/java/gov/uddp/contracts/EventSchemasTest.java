package gov.uddp.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventSchemasTest {

    private final ObjectMapper json = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void envelopeRecordSerialisesToAValidEnvelope() {
        ObjectNode payload = matchDecided("AUTO_MATCH");

        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                "uddp.identity.match-decided",
                1,
                Instant.parse("2026-11-04T05:00:00Z"),
                "identity-service",
                "source-a:record-1",
                UUID.randomUUID(),
                null,
                "XX",
                new StatePackRef("pack-x", "1.0.0", "sha256:" + "a".repeat(64)),
                payload);

        assertThat(EventSchemas.validate(EventSchemas.ENVELOPE, json.valueToTree(envelope))).isEmpty();
        assertThat(EventSchemas.validate("identity.match-decided.v1", payload)).isEmpty();
    }

    @Test
    void rejectsAPayloadWithAnUnknownDecision() {
        assertThat(EventSchemas.validate("identity.match-decided.v1", matchDecided("MAYBE"))).isNotEmpty();
    }

    @Test
    void rejectsAnEnvelopeWithoutAPayload() {
        ObjectNode envelope = json.createObjectNode();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "uddp.canonical.resident-created");
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", "2026-11-04T05:00:00Z");
        envelope.put("producer", "canonical-service");
        envelope.put("partitionKey", "k");

        assertThat(EventSchemas.validate(EventSchemas.ENVELOPE, envelope)).isNotEmpty();
    }

    @Test
    void rawAndCanonicalAndRejectedSchemasLoad() {
        for (String name : new String[] {
                "ingestion.raw-record-received.v1", "canonical.resident-created.v1", "canonical.record-rejected.v1"}) {
            assertThat(EventSchemas.validate(name, json.createObjectNode())).isNotEmpty();
        }
    }

    @Test
    void unknownSchemaFailsLoudly() {
        assertThatThrownBy(() -> EventSchemas.validate("no-such.v1", json.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyTopicIsVersionedAndNamespaced() throws IllegalAccessException {
        for (Field field : Topics.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                assertThat((String) field.get(null)).matches("^uddp\\.[a-z.-]+\\.v\\d+$");
            }
        }
    }

    private ObjectNode matchDecided(String decision) {
        ObjectNode payload = json.createObjectNode();
        payload.put("matchingRunId", UUID.randomUUID().toString());
        payload.put("canonicalRecordA", UUID.randomUUID().toString());
        payload.put("canonicalRecordB", UUID.randomUUID().toString());
        payload.put("matchProbability", 0.97);
        payload.put("matchWeight", 11.2);
        payload.put("decision", decision);
        return payload;
    }
}
