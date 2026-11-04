package gov.uddp.identity;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.contracts.Topics;
import gov.uddp.messaging.KafkaEventLoop;
import gov.uddp.messaging.OutboxRelay;
import java.util.ArrayList;
import java.util.List;
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
 * identity-service's Kafka side, on when {@code uddp.kafka.bootstrap-servers} is set: the outbox relay, the projection
 * consumer (canonical residents and Golden membership) and the command consumer (matching-requested). Commands run in
 * their own consumer group, one at a time, because a matching run can take longer than a projection poll may wait.
 */
@Component
@ConditionalOnExpression("'${uddp.kafka.bootstrap-servers:}' != ''")
public class IdentityMessaging implements DisposableBean {

    static final String PROJECTION_CONSUMER = "identity-service-projections";
    static final String COMMAND_CONSUMER = "identity-service-commands";

    private final KafkaProducer<String, String> producer;
    private final OutboxRelay relay;
    private final List<KafkaEventLoop> loops = new ArrayList<>();
    private final String bootstrapServers;
    private final DataSource dataSource;
    private final MatchingRuns runs;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(3, r -> {
        Thread thread = new Thread(r, "identity-messaging");
        thread.setDaemon(true);
        return thread;
    });

    public IdentityMessaging(@Value("${uddp.kafka.bootstrap-servers}") String bootstrapServers, DataSource dataSource,
                             MatchingRuns runs) {
        this.bootstrapServers = bootstrapServers;
        this.dataSource = dataSource;
        this.runs = runs;
        this.producer = new KafkaProducer<>(Map.of("bootstrap.servers", bootstrapServers, "enable.idempotence", "true",
                "acks", "all", "client.id", "identity-service"), new StringSerializer(), new StringSerializer());
        this.relay = new OutboxRelay(dataSource::getConnection, producer, 500);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        executor.scheduleWithFixedDelay(relay::drain, 500, 500, TimeUnit.MILLISECONDS);
        var projections = new KafkaEventLoop(bootstrapServers, PROJECTION_CONSUMER, dataSource::getConnection, Map.of(
                Topics.RESIDENT_CREATED, Projections::residentCreated,
                Topics.GOLDEN_RESIDENT_CHANGED, Projections::goldenResidentChanged), producer);
        var commands = new KafkaEventLoop(bootstrapServers, COMMAND_CONSUMER, dataSource::getConnection, Map.of(
                Topics.CMD_MATCHING_REQUESTED, (c, envelope) -> matchingRequested(envelope)), producer,
                Map.of("max.poll.records", "1", "max.poll.interval.ms", String.valueOf(TimeUnit.HOURS.toMillis(2))));
        loops.add(projections);
        loops.add(commands);
        executor.execute(projections);
        executor.execute(commands);
    }

    /**
     * cmd.identity.matching-requested: {@code payload.mode} FULL or INCREMENTAL; the envelope's correlationId is echoed
     * on the run's events so the requester can recognise its run.
     */
    private void matchingRequested(JsonNode envelope) {
        boolean incremental = "INCREMENTAL".equals(envelope.path("payload").path("mode").asText("FULL"));
        String correlation = envelope.path("correlationId").asText(null);
        runs.run(incremental, correlation == null ? null : UUID.fromString(correlation));
    }

    @Override
    public void destroy() {
        loops.forEach(KafkaEventLoop::close);
        executor.shutdownNow();
        producer.close();
    }
}
