package gov.uddp.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.contracts.EventEnvelope;
import gov.uddp.contracts.StatePackRef;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/** Outbox to Kafka to idempotent consumer, with duplicate appends, redelivery and a relay that crashed mid-batch. */
@Testcontainers
class OutboxKafkaIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    private static Producer<String, String> producer;

    private final ConnectionSource connections =
            () -> DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

    private String topic;

    @BeforeAll
    static void startProducer() {
        producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "enable.idempotence", "true",
                "acks", "all"), new StringSerializer(), new StringSerializer());
    }

    @AfterAll
    static void stopProducer() {
        producer.close();
    }

    @BeforeEach
    void freshTables() throws SQLException {
        topic = "uddp.test.events-" + UUID.randomUUID() + ".v1";
        try (Connection c = connections.open(); var s = c.createStatement()) {
            s.execute("drop table if exists outbox_event, processed_event, effect");
            s.execute(Outbox.DDL);
            s.execute("create table effect (event_id uuid not null)");
        }
    }

    @Test
    void publishesEachEventOnceAndConsumersApplyItOnce() throws Exception {
        UUID duplicateId = Outbox.deterministicId("uddp.identity.match-decided", "row-1");
        try (Connection c = connections.open()) {
            assertThat(Outbox.append(c, topic, envelope(duplicateId, "k1"))).isTrue();
            assertThat(Outbox.append(c, topic, envelope(duplicateId, "k1"))).isFalse();
            assertThat(Outbox.append(c, topic, envelope(UUID.randomUUID(), "k2"))).isTrue();
            assertThat(Outbox.append(c, topic, envelope(UUID.randomUUID(), "k3"))).isTrue();
        }

        OutboxRelay relay = new OutboxRelay(connections, producer, 2);
        assertThat(relay.drain()).isEqualTo(3);
        assertThat(relay.drain()).isZero();

        List<ConsumerRecord<String, String>> received = consume(3);
        Inbox inbox = new Inbox(connections, "test-consumer");
        for (var record : received) {
            assertThat(inbox.handle(record, this::recordEffect)).isTrue();
        }
        for (var record : received) {
            assertThat(inbox.handle(record, this::recordEffect)).isFalse();
        }
        assertThat(count("select count(*) from effect")).isEqualTo(3);
        assertThat(received).extracting(ConsumerRecord::key).containsExactly("k1", "k2", "k3");
        assertThat(new String(received.get(0).headers().lastHeader("eventId").value()))
                .isEqualTo(duplicateId.toString());
    }

    @Test
    void reportsBacklogAndPublishedEventsAsMetrics() throws Exception {
        var registry = new SimpleMeterRegistry();
        OutboxRelay relay = new OutboxRelay(connections, producer, 10, registry);
        try (Connection c = connections.open()) {
            Outbox.append(c, topic, envelope(UUID.randomUUID(), "k1"));
            Outbox.append(c, topic, envelope(UUID.randomUUID(), "k2"));
            try (var s = c.createStatement()) {
                s.execute("update outbox_event set created_at = now() - interval '90 seconds'");
            }
        }

        relay.measureBacklog();
        assertThat(registry.get("uddp.outbox.backlog").gauge().value()).isEqualTo(2);
        assertThat(registry.get("uddp.outbox.oldest.unpublished.age").gauge().value()).isGreaterThanOrEqualTo(90);

        assertThat(relay.drain()).isEqualTo(2);
        assertThat(registry.get("uddp.outbox.published").counter().count()).isEqualTo(2);
        assertThat(registry.get("uddp.outbox.backlog").gauge().value()).isZero();
        assertThat(registry.get("uddp.outbox.oldest.unpublished.age").gauge().value()).isZero();
        assertThat(registry.get("uddp.outbox.relay.failures").counter().count()).isZero();
    }

    @Test
    void publishedEventsPastTheirRetentionAreRemovedAndUnpublishedOnesStay() throws Exception {
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        UUID waiting = UUID.randomUUID();
        try (Connection c = connections.open()) {
            Outbox.append(c, topic, envelope(old, "k1"));
            Outbox.append(c, topic, envelope(recent, "k2"));
            Outbox.append(c, topic, envelope(waiting, "k3"));
            try (var s = c.createStatement()) {
                s.execute("update outbox_event set published_at = now() - interval '8 days' where event_id = '" + old
                        + "'");
                s.execute("update outbox_event set published_at = now() - interval '1 day' where event_id = '" + recent
                        + "'");
            }
        }
        OutboxRelay relay = new OutboxRelay(connections, producer, 10).retainPublished(java.time.Duration.ofDays(7));
        assertThat(relay.purgePublished()).isEqualTo(1);
        assertThat(count("select count(*) from outbox_event where event_id = '" + old + "'")).isZero();
        assertThat(count("select count(*) from outbox_event")).isEqualTo(2);
    }

    @Test
    void aRelayThatCrashedBeforeMarkingResendsAndTheInboxAbsorbsIt() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = connections.open()) {
            Outbox.append(c, topic, envelope(id, "k1"));
        }
        OutboxRelay relay = new OutboxRelay(connections, producer, 10);
        assertThat(relay.drain()).isEqualTo(1);
        try (Connection c = connections.open(); var s = c.createStatement()) {
            s.executeUpdate("update outbox_event set published_at = null");
        }
        assertThat(relay.drain()).isEqualTo(1);

        List<ConsumerRecord<String, String>> received = consume(2);
        Inbox inbox = new Inbox(connections, "test-consumer");
        int applied = 0;
        for (var record : received) {
            applied += inbox.handle(record, this::recordEffect) ? 1 : 0;
        }
        assertThat(applied).isEqualTo(1);
        assertThat(count("select count(*) from effect")).isEqualTo(1);
    }

    @Test
    void aFailedEffectLeavesTheEventUnprocessedForRetry() throws Exception {
        try (Connection c = connections.open()) {
            Outbox.append(c, topic, envelope(UUID.randomUUID(), "k1"));
        }
        new OutboxRelay(connections, producer, 10).drain();
        var record = consume(1).get(0);
        Inbox inbox = new Inbox(connections, "test-consumer");

        assertThatThrownBy(() -> inbox.handle(record, (c, e) -> {
            recordEffect(c, e);
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("select count(*) from effect")).isZero();
        assertThat(inbox.handle(record, this::recordEffect)).isTrue();
    }

    @Test
    void rejectsAnEventWhosePayloadBreaksItsSchema() throws Exception {
        ObjectNode payload = Outbox.json().createObjectNode().put("decision", "MAYBE");
        var bad = new EventEnvelope(UUID.randomUUID(), "uddp.identity.match-decided", 1, Instant.now(), "test", "k",
                null, null, null, null, payload);
        try (Connection c = connections.open()) {
            assertThatThrownBy(() -> Outbox.append(c, topic, bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(count("select count(*) from outbox_event")).isZero();
    }

    @Test
    void theTraceAnEventIsWrittenInContinuesWhereItIsConsumed() throws Exception {
        String parent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        List<String> spans = new java.util.concurrent.CopyOnWriteArrayList<>();
        TraceContext.use(new TraceContext.Bridge() {
            @Override
            public String current() {
                return parent;
            }

            @Override
            public void inSpan(String traceparent, String name, TraceContext.Work work) throws Exception {
                spans.add(name + " <- " + traceparent);
                work.run();
            }
        });
        try {
            try (Connection c = connections.open()) {
                Outbox.append(c, topic, envelope(UUID.randomUUID(), "k1"));
            }
            new OutboxRelay(connections, producer, 10).drain();
            var record = consume(1).get(0);
            assertThat(new String(record.headers().lastHeader(TraceContext.FIELD).value())).isEqualTo(parent);
            assertThat(TraceContext.of(Outbox.json().readTree(record.value()))).isEqualTo(parent);

            var loop = new KafkaEventLoop(KAFKA.getBootstrapServers(), "trace-" + UUID.randomUUID(), connections,
                    Map.of(topic, this::recordEffect), producer);
            var thread = new Thread(loop);
            thread.start();
            try {
                long deadline = System.currentTimeMillis() + 30_000;
                while (count("select count(*) from effect") == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(200);
                }
            } finally {
                loop.close();
                thread.join(10_000);
            }
            assertThat(count("select count(*) from effect")).isEqualTo(1);
            assertThat(spans).containsExactly("consume " + topic + " <- " + parent);
        } finally {
            TraceContext.use(null);
        }
    }

    private void recordEffect(Connection c, com.fasterxml.jackson.databind.JsonNode envelope) throws SQLException {
        try (var s = c.prepareStatement("insert into effect values (?)")) {
            s.setObject(1, UUID.fromString(envelope.path("eventId").asText()));
            s.executeUpdate();
        }
    }

    private EventEnvelope envelope(UUID id, String key) {
        ObjectNode payload = Outbox.json().createObjectNode();
        payload.put("matchingRunId", UUID.randomUUID().toString());
        payload.put("canonicalRecordA", UUID.randomUUID().toString());
        payload.put("canonicalRecordB", UUID.randomUUID().toString());
        payload.put("matchProbability", 0.99);
        payload.put("matchWeight", 12.0);
        payload.put("decision", "AUTO_MATCH");
        return new EventEnvelope(id, "uddp.identity.match-decided", 1, Instant.now(), "test", key, null, null, "XX",
                new StatePackRef("pack", "1.0.0", "sha256:" + "0".repeat(64)), payload);
    }

    private List<ConsumerRecord<String, String>> consume(int expected) {
        try (var consumer = new KafkaConsumer<>(Map.<String, Object>of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", "test-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest"), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 30_000;
            while (records.size() < expected && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
            return records;
        }
    }

    private long count(String sql) throws SQLException {
        try (Connection c = connections.open(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }
}
