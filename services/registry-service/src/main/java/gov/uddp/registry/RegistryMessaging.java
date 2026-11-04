package gov.uddp.registry;

import gov.uddp.contracts.Topics;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.messaging.KafkaEventLoop;
import gov.uddp.messaging.OutboxRelay;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * registry-service's Kafka side, on when {@code uddp.kafka.bootstrap-servers} is set: the outbox relay, the projection
 * consumer (canonical Residents, families, matching runs) and the command consumer (golden-build-requested), which
 * runs one build at a time in its own consumer group.
 */
@Component
@ConditionalOnExpression("'${uddp.kafka.bootstrap-servers:}' != ''")
public class RegistryMessaging implements DisposableBean {

    static final String PROJECTION_CONSUMER = "registry-service-projections";
    static final String COMMAND_CONSUMER = "registry-service-commands";

    private final KafkaProducer<String, String> producer;
    private final OutboxRelay relay;
    private final List<KafkaEventLoop> loops = new ArrayList<>();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(3, r -> {
        Thread thread = new Thread(r, "registry-messaging");
        thread.setDaemon(true);
        return thread;
    });

    public RegistryMessaging(@Value("${uddp.kafka.bootstrap-servers}") String bootstrapServers,
                             PostgresConnectionFactory registryConnections, GoldenBuilds builds) {
        this.producer = new KafkaProducer<>(Map.of("bootstrap.servers", bootstrapServers, "enable.idempotence", "true",
                "acks", "all", "client.id", "registry-service"), new StringSerializer(), new StringSerializer());
        this.relay = new OutboxRelay(registryConnections::open, producer, 500);
        loops.add(new KafkaEventLoop(bootstrapServers, PROJECTION_CONSUMER, registryConnections::open, Map.of(
                Topics.RESIDENT_CREATED, RegistryProjections::residentCreated,
                Topics.FAMILY_OBSERVED, RegistryProjections::familyObserved,
                Topics.MATCH_DECIDED, RegistryProjections.MATCH_DECIDED,
                Topics.CLUSTER_ASSIGNED, RegistryProjections.CLUSTER_ASSIGNED,
                Topics.MATCH_RUN_COMPLETED, RegistryProjections.MATCH_RUN_COMPLETED), producer));
        loops.add(new KafkaEventLoop(bootstrapServers, COMMAND_CONSUMER, registryConnections::open, Map.of(
                Topics.CMD_GOLDEN_BUILD_REQUESTED, (c, envelope) -> builds.build(
                        UUID.fromString(envelope.path("payload").path("matchingRunId").asText()))), producer,
                Map.of("max.poll.records", "1", "max.poll.interval.ms", String.valueOf(TimeUnit.HOURS.toMillis(2)))));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        executor.scheduleWithFixedDelay(relay::drain, 500, 500, TimeUnit.MILLISECONDS);
        loops.forEach(executor::execute);
    }

    @Override
    public void destroy() {
        loops.forEach(KafkaEventLoop::close);
        executor.shutdownNow();
        producer.close();
    }
}
