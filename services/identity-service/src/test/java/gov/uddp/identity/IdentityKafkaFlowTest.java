package gov.uddp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.contracts.Topics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * identity-service over Kafka only: canonical residents and Golden membership arrive as events (each delivered twice),
 * a matching-requested command starts a run, and the run's events come back with the command's correlation id. The
 * engine is a fixture here; the real Splink adapter is proven by {@link IdentitySplinkMatchingTest}.
 */
@Testcontainers
@SpringBootTest
class IdentityKafkaFlowTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_identity");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("uddp.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    static final AtomicInteger RUNS = new AtomicInteger();

    /** Pairs every two current candidates in canonical id order as one AUTO_MATCH cluster. */
    @TestConfiguration
    static class FixtureEngine {
        @Bean
        @Primary
        MatchingEngine fixtureEngine(JdbcTemplate jdbc) {
            return incremental -> {
                RUNS.incrementAndGet();
                UUID run = UUID.randomUUID();
                jdbc.update("insert into matching_run (run_id, model_version, status, threshold, mode)"
                        + " values (?, 'fixture', 'RUNNING', 0.95, ?)", run, incremental ? "INCREMENTAL" : "FULL");
                List<UUID> ids = jdbc.queryForList("select canonical_resident_id from canonical_resident_current"
                        + " order by canonical_resident_id", UUID.class);
                for (int i = 0; i + 1 < ids.size(); i += 2) {
                    jdbc.update("insert into resident_match_pair values (?, ?, ?, 0.99, 10, 'AUTO_MATCH', '{}', now())",
                            run, ids.get(i), ids.get(i + 1));
                }
                for (int i = 0; i < ids.size(); i++) {
                    jdbc.update("insert into resident_match_cluster_member values (?, ?, ?)", run, "c" + (i / 2),
                            ids.get(i));
                }
                jdbc.update("update matching_run set status = 'COMPLETED', completed_at = now() where run_id = ?", run);
                return run;
            };
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void projectsEventsOnceRunsOnCommandAndPublishesTheRun() throws Exception {
        List<JsonNode> residents = IdentitySplinkMatchingTest.sampleEvents();
        UUID golden = UUID.randomUUID();
        JsonNode first = residents.getFirst().path("payload");
        String goldenChanged = """
                {"eventId":"%s","eventType":"uddp.registry.golden-resident-changed","schemaVersion":1,
                 "occurredAt":"2026-11-04T00:00:00Z","producer":"test","partitionKey":"%s",
                 "payload":{"goldenResidentId":"%s","changeType":"CREATED",
                 "members":[{"sourceRecordKey":"%s","canonicalResidentId":"%s"}]}}
                """.formatted(UUID.randomUUID(), golden, golden, first.path("sourceRecordKey").asText(),
                first.path("canonicalResidentId").asText());

        try (var producer = new KafkaProducer<>(Map.<String, Object>of("bootstrap.servers",
                KAFKA.getBootstrapServers()), new StringSerializer(), new StringSerializer())) {
            for (int delivery = 0; delivery < 2; delivery++) {
                for (JsonNode event : residents) {
                    producer.send(new ProducerRecord<>(Topics.RESIDENT_CREATED, event.path("partitionKey").asText(),
                            event.toString())).get();
                }
                producer.send(new ProducerRecord<>(Topics.GOLDEN_RESIDENT_CHANGED, golden.toString(), goldenChanged))
                        .get();
            }
            await(() -> jdbc.queryForObject("select count(*) from candidate_resident", Integer.class) == 7
                    && jdbc.queryForObject("select count(*) from current_membership", Integer.class) == 1);

            UUID correlation = UUID.randomUUID();
            String command = """
                    {"eventId":"%s","eventType":"uddp.cmd.identity.matching-requested","schemaVersion":1,
                     "occurredAt":"2026-11-04T00:00:00Z","producer":"test","partitionKey":"k",
                     "correlationId":"%s","payload":{"mode":"FULL"}}
                    """.formatted(UUID.randomUUID(), correlation);
            producer.send(new ProducerRecord<>(Topics.CMD_MATCHING_REQUESTED, "k", command)).get();
            producer.send(new ProducerRecord<>(Topics.CMD_MATCHING_REQUESTED, "k", command)).get();

            JsonNode completed = consumeCompleted(correlation);
            assertThat(completed.path("payload").path("pairCount").asInt()).isEqualTo(3);
            assertThat(completed.path("payload").path("clusterMemberCount").asInt()).isEqualTo(7);
        }
        assertThat(jdbc.queryForObject("select count(*) from candidate_resident", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from processed_event", Integer.class)).isEqualTo(9);
        assertThat(RUNS.get()).as("a redelivered command runs matching once").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from golden_resident_membership where effective_to is null",
                Integer.class)).isEqualTo(1);
    }

    private JsonNode consumeCompleted(UUID correlation) throws Exception {
        try (var consumer = new KafkaConsumer<>(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", "test-" + UUID.randomUUID(), "auto.offset.reset", "earliest"), new StringDeserializer(),
                new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.MATCH_RUN_COMPLETED));
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode envelope = json.readTree(record.value());
                    if (correlation.toString().equals(envelope.path("correlationId").asText())) {
                        return envelope;
                    }
                }
            }
        }
        throw new AssertionError("No match-run-completed for " + correlation);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for the projections");
            }
            Thread.sleep(200);
        }
    }
}
