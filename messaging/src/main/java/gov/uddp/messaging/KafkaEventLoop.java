package gov.uddp.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One consumer group reading a set of topics and handing each record to its {@link Inbox} handler (section 5.2).
 * Offsets are committed after the handler's transaction, so a crash redelivers and the inbox skips what was done.
 * A record that keeps failing is parked on {@code <topic>.dlt.<consumer>} with its error (section 3.4), never blocking
 * the partition.
 */
public final class KafkaEventLoop implements Runnable, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventLoop.class);
    private static final int ATTEMPTS = 3;
    /** How long a record whose prerequisite has not arrived is retried before it is parked (2 minutes). */
    private static final int WAIT_ATTEMPTS = 240;
    private static final long WAIT_MILLIS = 500;

    private final KafkaConsumer<String, String> consumer;
    private final Producer<String, String> deadLetters;
    private final Inbox inbox;
    private final String consumerName;
    private final Map<String, Inbox.Handler> handlers;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public KafkaEventLoop(String bootstrapServers, String consumerName, ConnectionSource connections,
                          Map<String, Inbox.Handler> handlers, Producer<String, String> deadLetters) {
        this(bootstrapServers, consumerName, connections, handlers, deadLetters, Map.of());
    }

    /** As above, with extra consumer settings (for example a longer {@code max.poll.interval.ms} for commands). */
    public KafkaEventLoop(String bootstrapServers, String consumerName, ConnectionSource connections,
                          Map<String, Inbox.Handler> handlers, Producer<String, String> deadLetters,
                          Map<String, String> consumerSettings) {
        Map<String, Object> settings = new java.util.HashMap<>(Map.of(
                "bootstrap.servers", bootstrapServers,
                "group.id", consumerName,
                "enable.auto.commit", "false",
                "auto.offset.reset", "earliest",
                "max.poll.records", "200"));
        settings.putAll(consumerSettings);
        this.consumer = new KafkaConsumer<>(settings, new StringDeserializer(), new StringDeserializer());
        this.deadLetters = deadLetters;
        this.inbox = new Inbox(connections, consumerName);
        this.consumerName = consumerName;
        this.handlers = Map.copyOf(handlers);
    }

    @Override
    public void run() {
        try {
            consumer.subscribe(List.copyOf(handlers.keySet()));
            while (running.get()) {
                var records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    traced(record);
                }
                if (!records.isEmpty()) {
                    consumer.commitSync();
                }
            }
        } catch (WakeupException e) {
            if (running.get()) {
                throw e;
            }
        } finally {
            consumer.close();
        }
    }

    /** Handles the record in a span that continues the trace the event was written in (TraceContext). */
    private void traced(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader(TraceContext.FIELD);
        String trace = header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
        try {
            TraceContext.inSpan(trace, "consume " + record.topic(), () -> handle(record));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void handle(ConsumerRecord<String, String> record) {
        Exception last = null;
        int waits = 0;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                inbox.handle(record, handlers.get(record.topic()));
                return;
            } catch (Inbox.NotYetApplicable e) {
                last = e;
                if (++waits < WAIT_ATTEMPTS && running.get()) {
                    attempt--;
                    try {
                        Thread.sleep(WAIT_MILLIS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } catch (Exception e) {
                last = e;
                LOG.warn("{} failed on {} offset {} (attempt {})", consumerName, record.topic(), record.offset(),
                        attempt, e);
            }
        }
        String topic = record.topic() + ".dlt." + consumerName;
        var parked = new ProducerRecord<>(topic, record.key(), record.value());
        parked.headers().add(new RecordHeader("error", String.valueOf(last).getBytes(StandardCharsets.UTF_8)));
        parked.headers().add(new RecordHeader("sourceOffset",
                (record.partition() + ":" + record.offset()).getBytes(StandardCharsets.UTF_8)));
        try {
            deadLetters.send(parked).get();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot park a failed event on " + topic, e);
        }
    }

    @Override
    public void close() {
        running.set(false);
        consumer.wakeup();
    }
}
