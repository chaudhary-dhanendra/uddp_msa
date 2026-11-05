package gov.uddp.statepack;

import gov.uddp.messaging.OutboxRelay;
import java.util.Map;
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
 * Publishes the pack lifecycle events from the outbox, and keeps the Studio's geography master in step with the edge's
 * LGD snapshots (geography.snapshot-published), when {@code uddp.kafka.bootstrap-servers} is set.
 */
@Component
@ConditionalOnExpression("'${uddp.kafka.bootstrap-servers:}' != ''")
public class StatePackMessaging implements DisposableBean {

    private final KafkaProducer<String, String> producer;
    private final OutboxRelay relay;
    static final String GEOGRAPHY_CONSUMER = "statepack-service-geography";

    private final gov.uddp.messaging.KafkaEventLoop geography;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "statepack-outbox-relay");
        thread.setDaemon(true);
        return thread;
    });

    public StatePackMessaging(@Value("${uddp.kafka.bootstrap-servers}") String bootstrapServers, DataSource dataSource,
                              gov.uddp.core.canonical.PostgresConnectionFactory statepackConnections) {
        this.producer = new KafkaProducer<>(Map.of("bootstrap.servers", bootstrapServers, "enable.idempotence", "true",
                "acks", "all", "client.id", "statepack-service"), new StringSerializer(), new StringSerializer());
        this.relay = new OutboxRelay(dataSource::getConnection, producer, 500);
        var lgd = new gov.uddp.core.geography.LgdImportService(statepackConnections,
                new com.fasterxml.jackson.databind.ObjectMapper());
        this.geography = new gov.uddp.messaging.KafkaEventLoop(bootstrapServers, GEOGRAPHY_CONSUMER,
                dataSource::getConnection, Map.of(gov.uddp.contracts.Topics.GEOGRAPHY_SNAPSHOT_PUBLISHED,
                (c, envelope) -> gov.uddp.core.geography.GeographyReplication.apply(c, envelope, lgd)), producer);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        executor.scheduleWithFixedDelay(relay::drain, 500, 500, TimeUnit.MILLISECONDS);
        executor.execute(geography);
    }

    @Override
    public void destroy() {
        geography.close();
        executor.shutdownNow();
        producer.close();
    }
}
