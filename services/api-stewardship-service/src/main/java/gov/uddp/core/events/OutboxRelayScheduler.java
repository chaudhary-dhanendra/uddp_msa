package gov.uddp.core.events;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.messaging.OutboxRelay;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Publishes the monolith's outbox to Kafka (MSA Phase 2). Runs only when both {@code uddp.events.enabled} is true and
 * {@code uddp.events.kafka.bootstrap-servers} is set; otherwise outbox rows simply wait.
 */
@Component
@ConditionalOnExpression("${uddp.events.enabled:false} and '${uddp.events.kafka.bootstrap-servers:}' != ''")
public class OutboxRelayScheduler implements DisposableBean {

    private final KafkaProducer<String, String> producer;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "uddp-outbox-relay");
        thread.setDaemon(true);
        return thread;
    });

    public OutboxRelayScheduler(PostgresConnectionFactory connections,
                                @Value("${uddp.events.kafka.bootstrap-servers}") String bootstrapServers,
                                @Value("${uddp.events.relay.interval-ms:1000}") long intervalMs,
                                @Value("${uddp.events.relay.batch-size:500}") int batchSize) {
        this.producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", bootstrapServers,
                "enable.idempotence", "true",
                "acks", "all",
                "client.id", "uddp-common-core-outbox"), new StringSerializer(), new StringSerializer());
        OutboxRelay relay = new OutboxRelay(connections::open, producer, batchSize);
        executor.scheduleWithFixedDelay(relay::drain, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
        producer.close();
    }
}
