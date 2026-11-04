package gov.uddp.canonical;

import gov.uddp.contracts.Topics;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.events.RemoteRawProjection;
import gov.uddp.messaging.KafkaEventLoop;
import gov.uddp.messaging.OutboxRelay;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * canonical-service's Kafka side, on when {@code uddp.kafka.bootstrap-servers} is set: the Raw projection (records and
 * batch reports from ingestion-service), a single processing worker that maps each completed batch, and the outbox
 * relay. After a restart, batches that were completed but not processed are picked up again.
 */
@Component
@ConditionalOnExpression("'${uddp.kafka.bootstrap-servers:}' != ''")
public class CanonicalMessaging implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(CanonicalMessaging.class);
    static final String CONSUMER = "canonical-service-raw";

    private final KafkaProducer<String, String> producer;
    private final OutboxRelay relay;
    private final KafkaEventLoop raw;
    private final CanonicalBatches batches;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "canonical-worker"));
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2,
            r -> daemon(r, "canonical-messaging"));

    public CanonicalMessaging(@Value("${uddp.kafka.bootstrap-servers}") String bootstrapServers,
                              PostgresConnectionFactory canonicalConnections, CanonicalBatches batches) {
        this.batches = batches;
        this.producer = new KafkaProducer<>(Map.of("bootstrap.servers", bootstrapServers, "enable.idempotence", "true",
                "acks", "all", "client.id", "canonical-service"), new StringSerializer(), new StringSerializer());
        this.relay = new OutboxRelay(canonicalConnections::open, producer, 500);
        this.raw = new KafkaEventLoop(bootstrapServers, CONSUMER, canonicalConnections::open, Map.of(
                Topics.RAW_RECORD_RECEIVED, RemoteRawProjection::rawRecordReceived,
                Topics.BATCH_COMPLETED, (c, envelope) -> {
                    RemoteRawProjection.batchCompleted(c, envelope);
                    String batch = envelope.path("payload").path("batchId").asText();
                    if ("COMPLETED".equals(envelope.path("payload").path("status").asText())) {
                        // After this transaction commits the worker sees the report; it then waits for the records.
                        worker.execute(() -> process(UUID.fromString(batch)));
                    }
                }), producer);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        executor.scheduleWithFixedDelay(relay::drain, 500, 500, TimeUnit.MILLISECONDS);
        executor.execute(raw);
        batches.pending().forEach(batch -> worker.execute(() -> process(batch)));
    }

    private void process(UUID batch) {
        try {
            sleepBriefly();
            batches.process(batch);
        } catch (RuntimeException e) {
            LOG.error("Canonical processing of batch {} failed; it is retried on restart", batch, e);
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread thread = new Thread(r, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public void destroy() {
        raw.close();
        worker.shutdownNow();
        executor.shutdownNow();
        producer.close();
    }
}
