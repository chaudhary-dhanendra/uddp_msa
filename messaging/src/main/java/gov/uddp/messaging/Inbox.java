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

    /**
     * Thrown by a handler whose event depends on another event that has not arrived yet (events of different topics
     * are not ordered with each other). The loop retries the record after a short wait instead of parking it.
     */
    public static class NotYetApplicable extends RuntimeException {
        public NotYetApplicable(String message) {
            super(message);
        }
    }

    /** The effect of one event, applied on the transaction that also records the event as processed. */
    @FunctionalInterface
    public interface Handler {
        void apply(Connection c, JsonNode envelope) throws Exception;
    }

    /**
     * The effect of many events of one topic, applied on one transaction (high-volume topics). It receives only the
     * events this consumer has not processed yet.
     */
    @FunctionalInterface
    public interface BatchHandler {
        void apply(Connection c, java.util.List<JsonNode> envelopes) throws Exception;
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

    /**
     * Applies the records' effects in one transaction, each event once; returns how many were new. If anything fails
     * nothing is recorded, and the caller handles the records one by one instead.
     */
    public int handleBatch(java.util.List<ConsumerRecord<String, String>> records, BatchHandler handler)
            throws Exception {
        var envelopes = new java.util.ArrayList<JsonNode>();
        var ids = new java.util.ArrayList<UUID>();
        for (var record : records) {
            JsonNode envelope = Outbox.json().readTree(record.value());
            envelopes.add(envelope);
            ids.add(UUID.fromString(envelope.path("eventId").asText()));
        }
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                var fresh = new java.util.HashSet<UUID>();
                try (var s = c.prepareStatement("insert into processed_event (consumer, event_id)"
                        + " select ?, id from unnest(?) as ids(id) on conflict do nothing returning event_id")) {
                    s.setString(1, consumer);
                    s.setArray(2, c.createArrayOf("uuid", ids.toArray()));
                    try (var r = s.executeQuery()) {
                        while (r.next()) {
                            fresh.add(r.getObject(1, UUID.class));
                        }
                    }
                }
                var apply = new java.util.ArrayList<JsonNode>();
                var seen = new java.util.HashSet<UUID>();
                for (int i = 0; i < envelopes.size(); i++) {
                    if (fresh.contains(ids.get(i)) && seen.add(ids.get(i))) {
                        apply.add(envelopes.get(i));
                    }
                }
                if (!apply.isEmpty()) {
                    handler.apply(c, apply);
                }
                c.commit();
                return apply.size();
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
