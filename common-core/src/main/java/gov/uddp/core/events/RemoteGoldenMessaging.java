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
 * Applies registry-service's Golden replica ({@link GoldenReplica}) when Golden builds are remote. A replica that
 * references canonical rows or a matching run not projected yet waits for them.
 */
@Component
@ConditionalOnExpression("${uddp.registry.remote:false} and '${uddp.events.kafka.bootstrap-servers:}' != ''")
public class RemoteGoldenMessaging implements DisposableBean {

    static final String CONSUMER = "api-stewardship-golden-replica";

    private final KafkaProducer<String, String> deadLetters;
    private final KafkaEventLoop loop;

    public RemoteGoldenMessaging(PostgresConnectionFactory connections,
                                 @Value("${uddp.events.kafka.bootstrap-servers}") String bootstrapServers) {
        this.deadLetters = new KafkaProducer<>(Map.of("bootstrap.servers", bootstrapServers, "acks", "all",
                "max.request.size", "8388608"), new StringSerializer(), new StringSerializer());
        this.loop = new KafkaEventLoop(bootstrapServers, CONSUMER, connections::open, Map.of(
                Topics.GOLDEN_REPLICA, GoldenReplica::apply), deadLetters,
                Map.of("max.partition.fetch.bytes", "8388608", "fetch.max.bytes", "8388608"));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread thread = new Thread(loop, "uddp-golden-replica");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void destroy() {
        loop.close();
        deadLetters.close();
    }
}
