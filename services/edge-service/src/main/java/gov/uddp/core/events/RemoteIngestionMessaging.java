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

/** Consumes ingestion-service's Raw events into {@link RemoteRawProjection} when ingestion is remote. */
@Component
@ConditionalOnExpression("${uddp.ingestion.remote:false} and '${uddp.events.kafka.bootstrap-servers:}' != ''")
public class RemoteIngestionMessaging implements DisposableBean {

    static final String CONSUMER = "edge-raw-projection";

    private final KafkaProducer<String, String> deadLetters;
    private final KafkaEventLoop loop;
    private final Thread thread;

    public RemoteIngestionMessaging(PostgresConnectionFactory connections,
                                    @Value("${uddp.events.kafka.bootstrap-servers}") String bootstrapServers) {
        this.deadLetters = new KafkaProducer<>(Map.of("bootstrap.servers", bootstrapServers, "acks", "all"),
                new StringSerializer(), new StringSerializer());
        this.loop = new KafkaEventLoop(bootstrapServers, CONSUMER, connections::open, Map.of(
                Topics.RAW_RECORD_RECEIVED, RemoteRawProjection::rawRecordReceived,
                Topics.RAW_RECORD_REPLAYED, RemoteRawProjection::rawRecordReceived,
                Topics.BATCH_COMPLETED, RemoteRawProjection::batchCompleted), deadLetters);
        this.thread = new Thread(loop, "uddp-raw-projection");
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
