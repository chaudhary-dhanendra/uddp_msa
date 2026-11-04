package gov.uddp.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.contracts.EventSchemas;
import gov.uddp.contracts.Topics;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * registry-service fed only by events: the sample's 7 canonical Residents, 7 family observations and a matching run
 * whose only AUTO_MATCH group is the three "Ram Singh" records (as the monolith publishes them), each delivered twice,
 * then a golden-build-requested command. The unchanged Golden engine builds the monolith's results (5 Golden
 * Residents, 6 Golden Families, Ram Singh in one identity with a UDDP ID), publishes one golden-resident-changed per
 * identity, and a second build keeps every Golden ID and writes no new version.
 */
@Testcontainers
@SpringBootTest
class RegistryGoldenBuildTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_registry");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("uddp.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void buildsTheMonolithsGoldenRecordsFromEventsAlone() throws Exception {
        List<String[]> events = sampleEvents();
        UUID run = null;
        try (var producer = new KafkaProducer<>(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer())) {
            for (int delivery = 0; delivery < 2; delivery++) {
                for (String[] event : events) {
                    JsonNode envelope = json.readTree(event[1]);
                    if (event[0].equals(Topics.MATCH_RUN_COMPLETED)) {
                        run = UUID.fromString(envelope.path("payload").path("matchingRunId").asText());
                    }
                    producer.send(new ProducerRecord<>(event[0], envelope.path("partitionKey").asText(), event[1]))
                            .get();
                }
            }
            producer.send(new ProducerRecord<>(Topics.CMD_GOLDEN_BUILD_REQUESTED, run.toString(), command(run))).get();
            JsonNode first = awaitBuild(run, 1);
            assertThat(first.path("payload").path("created").asInt()).isEqualTo(5);

            assertThat(count("select count(*) from golden_resident where status = 'ACTIVE'")).isEqualTo(5);
            assertThat(count("select count(*) from golden_family where status = 'ACTIVE'")).isEqualTo(6);
            assertThat(count("select count(*) from uddp_id where kind = 'RESIDENT'")).isEqualTo(5);
            assertThat(count("select count(distinct m.golden_resident_id) from golden_resident_membership m"
                    + " join canonical_resident c using (canonical_resident_id) where m.effective_to is null"
                    + " and c.canonical_payload->>'name' = 'Ram Singh'")).isEqualTo(1);
            assertThat(count("select count(*) from outbox_event where topic = ?", Topics.GOLDEN_RESIDENT_CHANGED))
                    .isEqualTo(5);
            assertThat(count("select count(*) from outbox_event where topic like 'uddp.identity.%'"))
                    .as("matching runs are identity-service's to publish").isZero();
            assertThat(count("select count(*) from processed_event where consumer = ?",
                    RegistryMessaging.PROJECTION_CONSUMER)).isEqualTo(events.size());
            List<String> ids = jdbc.queryForList("select golden_resident_id::text from golden_resident order by 1",
                    String.class);

            producer.send(new ProducerRecord<>(Topics.CMD_GOLDEN_BUILD_REQUESTED, run.toString(), command(run))).get();
            awaitBuild(run, 2);
        }
        assertThat(jdbc.queryForList("select golden_resident_id::text from golden_resident order by 1", String.class))
                .hasSize(5);
        assertThat(count("select count(*) from golden_resident_version")).as("an unchanged rebuild versions nothing")
                .isEqualTo(5);
        assertThat(count("select count(*) from outbox_event where topic = ?", Topics.GOLDEN_RESIDENT_CHANGED))
                .isEqualTo(5);
        for (String envelope : jdbc.queryForList("select payload::text from outbox_event", String.class)) {
            assertThat(EventSchemas.validate(EventSchemas.ENVELOPE, json.readTree(envelope))).isEmpty();
        }
        assertThat(count("select count(*) from lineage.lineage_event")).isEqualTo(events.size());
    }

    private String command(UUID run) {
        return """
                {"eventId":"%s","eventType":"uddp.cmd.registry.golden-build-requested","schemaVersion":1,
                 "occurredAt":"2026-11-04T00:00:00Z","producer":"test","partitionKey":"%s",
                 "payload":{"matchingRunId":"%s"}}
                """.formatted(UUID.randomUUID(), run, run);
    }

    /** Waits for the n-th golden-build-completed of this run. */
    private JsonNode awaitBuild(UUID run, int n) throws Exception {
        List<JsonNode> builds = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", "test-" + UUID.randomUUID(), "auto.offset.reset", "earliest"), new StringDeserializer(),
                new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.GOLDEN_BUILD_COMPLETED));
            long deadline = System.currentTimeMillis() + 120_000;
            while (builds.size() < n && System.currentTimeMillis() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode envelope = json.readTree(record.value());
                    if (run.toString().equals(envelope.path("payload").path("matchingRunId").asText())) {
                        builds.add(envelope);
                    }
                }
            }
        }
        assertThat(builds).hasSizeGreaterThanOrEqualTo(n);
        return builds.get(n - 1);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static List<String[]> sampleEvents() throws Exception {
        List<String[]> out = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("src/test/resources/fixtures/registry-sample-events.txt"),
                StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                int space = line.indexOf(' ');
                out.add(new String[] {line.substring(0, space), line.substring(space + 1)});
            }
        }
        return out;
    }
}
