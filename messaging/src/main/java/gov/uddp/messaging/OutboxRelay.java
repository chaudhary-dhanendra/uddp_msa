package gov.uddp.messaging;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes unpublished outbox rows to Kafka in creation order, then marks them published (section 5.1). Rows are
 * locked with {@code FOR UPDATE SKIP LOCKED}, so several relay instances may run. Delivery is at least once: a crash
 * after the send and before the commit resends the rows, and consumers absorb that through {@link Inbox}.
 */
public final class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    private final ConnectionSource connections;
    private final Producer<String, String> producer;
    private final int batchSize;

    public OutboxRelay(ConnectionSource connections, Producer<String, String> producer, int batchSize) {
        this.connections = connections;
        this.producer = producer;
        this.batchSize = batchSize;
    }

    /** Publishes up to one batch; returns how many rows were published. */
    public int publishBatch() throws Exception {
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                List<Row> rows = new ArrayList<>();
                try (var s = c.prepareStatement("select event_id, topic, partition_key, event_type, payload::text"
                        + " from outbox_event where published_at is null order by created_at, event_id"
                        + " limit ? for update skip locked")) {
                    s.setInt(1, batchSize);
                    try (var r = s.executeQuery()) {
                        while (r.next()) {
                            rows.add(new Row(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
                                    r.getString(5)));
                        }
                    }
                }
                List<Future<RecordMetadata>> sent = new ArrayList<>();
                for (Row row : rows) {
                    var record = new ProducerRecord<>(row.topic(), row.partitionKey(), row.payload());
                    record.headers().add(new RecordHeader("eventId", bytes(row.eventId().toString())));
                    record.headers().add(new RecordHeader("eventType", bytes(row.eventType())));
                    String trace = traceOf(row.payload());
                    if (trace != null) {
                        record.headers().add(new RecordHeader(TraceContext.FIELD, bytes(trace)));
                    }
                    sent.add(producer.send(record));
                }
                for (Future<RecordMetadata> future : sent) {
                    future.get();
                }
                try (var s = c.prepareStatement("update outbox_event set published_at = now() where event_id = ?")) {
                    for (Row row : rows) {
                        s.setObject(1, row.eventId());
                        s.addBatch();
                    }
                    s.executeBatch();
                }
                c.commit();
                return rows.size();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** Publishes until the outbox is empty or a batch fails; failures are logged and retried on the next call. */
    public int drain() {
        int total = 0;
        try {
            int published;
            do {
                published = publishBatch();
                total += published;
            } while (published == batchSize);
        } catch (Exception e) {
            LOG.warn("Outbox relay failed; unpublished events stay in the outbox and are retried", e);
        }
        return total;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Row(UUID eventId, String topic, String partitionKey, String eventType, String payload) {
    }

    private static String traceOf(String envelope) {
        try {
            return TraceContext.of(Outbox.json().readTree(envelope));
        } catch (Exception e) {
            return null;
        }
    }
}
