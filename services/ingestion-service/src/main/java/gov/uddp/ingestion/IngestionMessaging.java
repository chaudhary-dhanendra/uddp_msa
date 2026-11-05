package gov.uddp.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.contracts.Topics;
import gov.uddp.messaging.KafkaEventLoop;
import gov.uddp.messaging.OutboxRelay;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * ingestion-service's Kafka side, on when {@code uddp.kafka.bootstrap-servers} is set: the outbox relay and the command
 * consumer (ingest-requested, replay-requested), one command at a time because a Hop run can take long.
 */
@Component
@ConditionalOnExpression("'${uddp.kafka.bootstrap-servers:}' != ''")
public class IngestionMessaging implements DisposableBean {

    static final String COMMAND_CONSUMER = "ingestion-service-commands";

    private final KafkaProducer<String, String> producer;
    private final OutboxRelay relay;
    private final KafkaEventLoop commands;
    private final Ingestions ingestions;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(3, r -> {
        Thread thread = new Thread(r, "ingestion-messaging");
        thread.setDaemon(true);
        return thread;
    });

    public IngestionMessaging(@Value("${uddp.kafka.bootstrap-servers}") String bootstrapServers, DataSource dataSource,
                              Ingestions ingestions) {
        this.producer = new KafkaProducer<>(Map.of("bootstrap.servers", bootstrapServers, "enable.idempotence", "true",
                "acks", "all", "client.id", "ingestion-service"), new StringSerializer(), new StringSerializer());
        this.ingestions = ingestions;
        this.relay = new OutboxRelay(dataSource::getConnection, producer, 500);
        this.commands = new KafkaEventLoop(bootstrapServers, COMMAND_CONSUMER, dataSource::getConnection, Map.of(
                Topics.CMD_INGEST_REQUESTED, (c, envelope) -> ingestions.ingest(envelope.path("payload"),
                        correlation(envelope)),
                Topics.CMD_REPLAY_REQUESTED, (c, envelope) -> ingestions.replay(envelope.path("payload"),
                        correlation(envelope))), producer,
                Map.of("max.poll.records", "1", "max.poll.interval.ms", String.valueOf(TimeUnit.HOURS.toMillis(2))));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        executor.scheduleWithFixedDelay(relay::drain, 500, 500, TimeUnit.MILLISECONDS);
        // Batches a stopped process left half announced continue from their last committed chunk.
        executor.execute(ingestions::resumePublishing);
        executor.execute(commands);
    }

    private static UUID correlation(JsonNode envelope) {
        String value = envelope.path("correlationId").asText(null);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    @Override
    public void destroy() {
        commands.close();
        executor.shutdownNow();
        producer.close();
    }
}
