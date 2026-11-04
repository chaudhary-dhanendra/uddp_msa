package gov.uddp.core.events;

import gov.uddp.contracts.Topics;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.messaging.KafkaEventLoop;
import java.util.Map;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Consumes canonical-service's events into {@link RemoteCanonicalProjection} when canonical processing is remote.
 * Residents have their own consumer, so a family observation that waits for its Resident never blocks it.
 */
@Component
@ConditionalOnExpression("${uddp.canonical.remote:false} and '${uddp.events.kafka.bootstrap-servers:}' != ''")
public class RemoteCanonicalMessaging implements DisposableBean {

    static final String RESIDENTS = "common-core-canonical-residents";
    static final String OBSERVATIONS = "common-core-canonical-observations";

    private final KafkaProducer<String, String> deadLetters;
    private final java.util.List<KafkaEventLoop> loops;

    public RemoteCanonicalMessaging(PostgresConnectionFactory connections,
                                    @Value("${uddp.events.kafka.bootstrap-servers}") String bootstrapServers) {
        this.deadLetters = new KafkaProducer<>(Map.of("bootstrap.servers", bootstrapServers, "acks", "all"),
                new StringSerializer(), new StringSerializer());
        this.loops = java.util.List.of(
                new KafkaEventLoop(bootstrapServers, RESIDENTS, connections::open, Map.of(
                        Topics.RESIDENT_CREATED, RemoteCanonicalProjection::residentCreated), deadLetters),
                new KafkaEventLoop(bootstrapServers, OBSERVATIONS, connections::open, Map.of(
                        Topics.FAMILY_OBSERVED, RemoteCanonicalProjection::familyObserved,
                        Topics.BATCH_PROCESSED, RemoteCanonicalProjection::batchProcessed), deadLetters));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        for (KafkaEventLoop loop : loops) {
            Thread thread = new Thread(loop, "uddp-canonical-projection");
            thread.setDaemon(true);
            thread.start();
        }
    }

    @Override
    public void destroy() {
        loops.forEach(KafkaEventLoop::close);
        deadLetters.close();
    }
}
