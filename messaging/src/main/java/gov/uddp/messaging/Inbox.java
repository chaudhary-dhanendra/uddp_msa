package gov.uddp.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Idempotent consumption (docs/msa-design.md, section 5.2): an event's effect and its {@code processed_event} row
 * commit in one transaction, so a redelivered event is recognised and skipped.
 */
public final class Inbox {

    /** The effect of one event, applied on the transaction that also records the event as processed. */
    @FunctionalInterface
    public interface Handler {
        void apply(Connection c, JsonNode envelope) throws Exception;
    }

    private final ConnectionSource connections;
    private final String consumer;

    public Inbox(ConnectionSource connections, String consumer) {
        this.connections = connections;
        this.consumer = consumer;
    }

    /** Applies the record's effect once; returns false when this consumer had already processed the event. */
    public boolean handle(ConsumerRecord<String, String> record, Handler handler) throws Exception {
        JsonNode envelope = Outbox.json().readTree(record.value());
        UUID eventId = UUID.fromString(envelope.path("eventId").asText());
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                if (!firstDelivery(c, consumer, eventId)) {
                    c.rollback();
                    return false;
                }
                handler.apply(c, envelope);
                c.commit();
                return true;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Records the event as processed by {@code consumer}; false when it already was. */
    public static boolean firstDelivery(Connection c, String consumer, UUID eventId) throws Exception {
        try (var s = c.prepareStatement("insert into processed_event (consumer, event_id) values (?, ?)"
                + " on conflict do nothing")) {
            s.setString(1, consumer);
            s.setObject(2, eventId);
            return s.executeUpdate() == 1;
        }
    }
}
