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

/** Consumes identity-service's run events into {@link RemoteMatchingProjection} when matching is remote. */
@Component
@ConditionalOnExpression("${uddp.identity.remote:false} and '${uddp.events.kafka.bootstrap-servers:}' != ''")
public class RemoteIdentityMessaging implements DisposableBean {

    static final String CONSUMER = "common-core-identity-projection";

    private final KafkaProducer<String, String> deadLetters;
    private final KafkaEventLoop loop;
    private final Thread thread;

    public RemoteIdentityMessaging(PostgresConnectionFactory connections,
                                   @Value("${uddp.events.kafka.bootstrap-servers}") String bootstrapServers) {
        this.deadLetters = new KafkaProducer<>(Map.of("bootstrap.servers", bootstrapServers, "acks", "all"),
                new StringSerializer(), new StringSerializer());
        this.loop = new KafkaEventLoop(bootstrapServers, CONSUMER, connections::open, Map.of(
                Topics.MATCH_DECIDED, RemoteMatchingProjection::matchDecided,
                Topics.CLUSTER_ASSIGNED, RemoteMatchingProjection::clusterAssigned,
                Topics.MATCH_RUN_COMPLETED, RemoteMatchingProjection::matchRunCompleted), deadLetters);
        this.thread = new Thread(loop, "uddp-identity-projection");
        this.thread.setDaemon(true);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        thread.start();
    }

    @Override
    public void destroy() {
        loop.close();
        deadLetters.close();
    }
}
