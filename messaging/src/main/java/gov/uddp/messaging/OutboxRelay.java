package gov.uddp.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
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
 *
 * <p>The relay reports its outbox as metrics (docs/observability.md): {@code uddp_outbox_backlog_events} (rows not yet
 * published), {@code uddp_outbox_oldest_unpublished_age_seconds}, {@code uddp_outbox_published_events_total} and
 * {@code uddp_outbox_relay_failures_total}. The backlog is measured after a drain, at most every few seconds.
 */
public final class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    /** How often the backlog is measured at most (drains run far more often). */
    static final long MEASURE_INTERVAL_MILLIS = 5_000;
    /** How often published events past their retention are removed, and how many at most per pass. */
    static final long PURGE_INTERVAL_MILLIS = 60 * 60_000;
    static final int PURGE_ROWS = 50_000;

    private final ConnectionSource connections;
    private final Producer<String, String> producer;
    private final int batchSize;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong oldestAgeSeconds = new AtomicLong();
    private final Counter published;
    private final Counter failures;
    private volatile long lastMeasured;
    private volatile long lastPurged = System.currentTimeMillis();
    private volatile java.time.Duration retention = java.time.Duration.ofDays(7);

    /** A relay that reports to Micrometer's global registry (Spring Boot adds its own registry to it). */
    public OutboxRelay(ConnectionSource connections, Producer<String, String> producer, int batchSize) {
        this(connections, producer, batchSize, Metrics.globalRegistry);
    }

    public OutboxRelay(ConnectionSource connections, Producer<String, String> producer, int batchSize,
                       MeterRegistry registry) {
        this.connections = connections;
        this.producer = producer;
        this.batchSize = batchSize;
        Gauge.builder("uddp.outbox.backlog", backlog, AtomicLong::get)
                .description("Outbox events not yet published to Kafka")
                .baseUnit("events")
                .register(registry);
        Gauge.builder("uddp.outbox.oldest.unpublished.age", oldestAgeSeconds, AtomicLong::get)
                .description("Age of the oldest unpublished outbox event (0 when the outbox is empty)")
                .baseUnit("seconds")
                .register(registry);
        this.published = Counter.builder("uddp.outbox.published")
                .description("Outbox events published to Kafka")
                .baseUnit("events")
                .register(registry);
        this.failures = Counter.builder("uddp.outbox.relay.failures")
                .description("Relay batches that failed and were left for the next drain")
                .register(registry);
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
                published.increment(rows.size());
                return rows.size();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    /**
     * How long published events stay in the outbox (default 7 days). Large batches put millions of events through it;
     * consumers deduplicate by event id in their inbox, so an event re-appended after removal is still applied once.
     */
    public OutboxRelay retainPublished(java.time.Duration keep) {
        this.retention = keep;
        return this;
    }

    /** Removes up to {@link #PURGE_ROWS} published events older than the retention; returns how many. */
    public int purgePublished() throws Exception {
        try (var c = connections.open();
             var s = c.prepareStatement("delete from outbox_event where event_id in (select event_id from outbox_event"
                     + " where published_at is not null and published_at < now() - make_interval(secs => ?)"
                     + " limit ?)")) {
            s.setLong(1, retention.toSeconds());
            s.setInt(2, PURGE_ROWS);
            return s.executeUpdate();
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
            failures.increment();
            LOG.warn("Outbox relay failed; unpublished events stay in the outbox and are retried", e);
        }
        long now = System.currentTimeMillis();
        if (now - lastPurged >= PURGE_INTERVAL_MILLIS) {
            lastPurged = now;
            try {
                purgePublished();
            } catch (Exception e) {
                LOG.warn("Removing published outbox events failed; retried on the next pass", e);
            }
        }
        if (now - lastMeasured >= MEASURE_INTERVAL_MILLIS) {
            lastMeasured = now;
            measureBacklog();
        }
        return total;
    }

    /** Reads how many events wait in the outbox and how old the oldest is; keeps the last values on failure. */
    public void measureBacklog() {
        try (var c = connections.open();
             var s = c.prepareStatement("select count(*), coalesce(extract(epoch from now() - min(created_at)), 0)"
                     + " from outbox_event where published_at is null");
             var r = s.executeQuery()) {
            if (r.next()) {
                backlog.set(r.getLong(1));
                oldestAgeSeconds.set(Math.max(0, (long) r.getDouble(2)));
            }
        } catch (Exception e) {
            LOG.debug("Cannot measure the outbox backlog", e);
        }
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
