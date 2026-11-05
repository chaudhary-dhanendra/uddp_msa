package gov.uddp.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * ingestion-service over Kafka: an ingest command (delivered twice) runs the template once, lands the rows
 * append-only, and publishes one schema-valid raw-record-received per row before batch-completed with the command's
 * correlation id; a failing template reports FAILED; replay re-publishes the same rows without touching them. Hop is
 * a fixture that lands rows exactly as generic-file-ingest.hpl does (real Hop runs only outside unit tests).
 */
@Testcontainers
@SpringBootTest
class IngestionFlowTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_raw");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("uddp.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    static final AtomicInteger HOP_RUNS = new AtomicInteger();

    /** Lands each data line of SOURCE_FILE as Hop does; SOURCE_SYSTEM "broken-source" simulates a failing run. */
    @TestConfiguration
    static class FixtureHop {
        @Bean
        @Primary
        HopExecutor fixtureHop(JdbcTemplate jdbc) {
            return (template, p) -> {
                HOP_RUNS.incrementAndGet();
                if ("broken-source".equals(p.get("SOURCE_SYSTEM"))) {
                    return new HopExecutor.Result(1, "file not readable");
                }
                try {
                    List<String> lines = Files.readAllLines(Path.of(p.get("SOURCE_FILE")), StandardCharsets.UTF_8);
                    for (int i = 1; i < lines.size(); i++) {
                        jdbc.update("insert into raw_record (batch_id, state_code, source_system, raw_payload,"
                                + " source_metadata, state_pack_version, source_schema_version, pipeline_template,"
                                + " source_line) values (?::uuid, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)",
                                p.get("BATCH_ID"), p.get("STATE_CODE"), p.get("SOURCE_SYSTEM"),
                                "{\"csvHeader\":\"" + p.get("CSV_HEADER") + "\",\"rawLine\":\"" + lines.get(i) + "\"}",
                                "{\"sourceEntity\":\"" + p.get("SOURCE_ENTITY") + "\"}", p.get("STATE_PACK_VERSION"),
                                p.get("SOURCE_SCHEMA_VERSION"), template, i + 1);
                    }
                    return new HopExecutor.Result(0, "ok");
                } catch (Exception e) {
                    return new HopExecutor.Result(2, e.getMessage());
                }
            };
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path work;

    @Test
    void ingestsOncePublishesEveryRowThenTheBatchAndReplaysWithoutRewriting() throws Exception {
        Path csv = work.resolve("people.csv");
        Files.writeString(csv, "name,born\nAsha,1990-01-01\nRavi,1985-05-05\nMeera,1979-09-09\n");
        UUID batch = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        String command = command(batch, correlation, "people-register", csv);
        int runsBefore = HOP_RUNS.get();

        try (var producer = producer()) {
            producer.send(new ProducerRecord<>(Topics.CMD_INGEST_REQUESTED, batch.toString(), command)).get();
            producer.send(new ProducerRecord<>(Topics.CMD_INGEST_REQUESTED, batch.toString(), command)).get();

            List<JsonNode> received = consume(Topics.RAW_RECORD_RECEIVED, correlation, 3);
            JsonNode completed = consume(Topics.BATCH_COMPLETED, correlation, 1).getFirst();
            assertThat(completed.path("payload").path("status").asText()).isEqualTo("COMPLETED");
            assertThat(completed.path("payload").path("recordCount").asInt()).isEqualTo(3);
            for (JsonNode event : received) {
                assertThat(EventSchemas.validate(EventSchemas.ENVELOPE, event)).isEmpty();
                assertThat(EventSchemas.validate("ingestion.raw-record-received.v1", event.path("payload"))).isEmpty();
            }
            assertThat(received).extracting(e -> e.path("payload").path("sourceLine").asInt()).containsExactly(2, 3, 4);
            Thread.sleep(1000);
            assertThat(HOP_RUNS.get() - runsBefore).as("a redelivered command runs Hop once").isEqualTo(1);

            UUID replayRun = UUID.randomUUID();
            String replay = """
                    {"eventId":"%s","eventType":"uddp.cmd.ingestion.replay-requested","schemaVersion":1,
                     "occurredAt":"2026-11-04T00:00:00Z","producer":"test","partitionKey":"%s",
                     "correlationId":"%s","payload":{"batchId":"%s","replayRunId":"%s","statePackVersion":"2.0.0"}}
                    """.formatted(UUID.randomUUID(), batch, correlation, batch, replayRun);
            producer.send(new ProducerRecord<>(Topics.CMD_REPLAY_REQUESTED, batch.toString(), replay)).get();
            List<JsonNode> replayed = consume(Topics.RAW_RECORD_REPLAYED, correlation, 3);
            assertThat(replayed).allSatisfy(e -> {
                assertThat(e.path("payload").path("replayRunId").asText()).isEqualTo(replayRun.toString());
                assertThat(e.path("payload").path("statePackVersion").asText()).isEqualTo("2.0.0");
            });
        }
        assertThat(jdbc.queryForObject("select count(*) from raw_record where batch_id = ?", Integer.class, batch))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(distinct state_pack_version) from raw_record", Integer.class))
                .as("replay never rewrites Raw").isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update raw_record set raw_payload = 'x'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from raw_record")).hasMessageContaining("append-only");
    }

    @Autowired
    private Ingestions ingestions;

    @Test
    void aLargeBatchIsAnnouncedInChunksAndAStoppedPublisherResumesWhereItCommitted() throws Exception {
        StringBuilder rows = new StringBuilder("name,born\n");
        for (int i = 0; i < 23; i++) {
            rows.append("Person ").append(i).append(",1990-01-01\n");
        }
        Path csv = work.resolve("many.csv");
        Files.writeString(csv, rows);
        UUID batch = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        ingestions.setChunkRows(5);
        try {
            assertThat(ingestions.ingest(json.readTree(command(batch, correlation, "many-people", csv))
                    .path("payload"), correlation)).isEqualTo("COMPLETED");
            assertThat(events(batch, "raw-record-received")).isEqualTo(23);
            assertThat(events(batch, "batch-completed")).isEqualTo(1);
            assertThat(jdbc.queryForObject("select record_count from ingestion_batch where batch_id = ?",
                    Integer.class, batch)).isEqualTo(23);

            // A crash after two committed chunks: the batch is PUBLISHING at row 10 and the later events are missing.
            var tenth = jdbc.queryForMap("select source_line, raw_record_id from raw_record where batch_id = ?"
                    + " order by source_line, raw_record_id offset 9 limit 1", batch);
            jdbc.update("update ingestion_batch set status = 'PUBLISHING', record_count = 0, published_line = ?,"
                    + " published_record_id = ?, published_count = 10 where batch_id = ?",
                    tenth.get("source_line"), tenth.get("raw_record_id"), batch);
            jdbc.update("delete from outbox_event where payload->'payload'->>'batchId' = ? and (event_type ="
                    + " 'uddp.ingestion.batch-completed' or (payload->'payload'->>'sourceLine')::int > ?)",
                    batch.toString(), tenth.get("source_line"));
            assertThat(events(batch, "raw-record-received")).isEqualTo(10);

            assertThat(ingestions.resumePublishing()).isEqualTo(1);
            assertThat(events(batch, "raw-record-received")).isEqualTo(23);
            assertThat(events(batch, "batch-completed")).isEqualTo(1);
            assertThat(jdbc.queryForMap("select status, record_count from ingestion_batch where batch_id = ?", batch))
                    .containsEntry("status", "COMPLETED").containsEntry("record_count", 23);
        } finally {
            ingestions.setChunkRows(Ingestions.CHUNK_ROWS);
        }
    }

    private int events(UUID batch, String type) {
        return jdbc.queryForObject("select count(*) from outbox_event where event_type = ? and"
                + " payload->'payload'->>'batchId' = ?", Integer.class, "uddp.ingestion." + type, batch.toString());
    }

    @Test
    void aFailingTemplateReportsAFailedBatch() throws Exception {
        UUID batch = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        try (var producer = producer()) {
            producer.send(new ProducerRecord<>(Topics.CMD_INGEST_REQUESTED, batch.toString(),
                    command(batch, correlation, "broken-source", work.resolve("none.csv")))).get();
            JsonNode completed = consume(Topics.BATCH_COMPLETED, correlation, 1).getFirst();
            assertThat(completed.path("payload").path("status").asText()).isEqualTo("FAILED");
            assertThat(completed.path("payload").path("message").asText()).contains("file not readable");
        }
        assertThat(jdbc.queryForObject("select status from ingestion_batch where batch_id = ?", String.class, batch))
                .isEqualTo("FAILED");
    }

    private String command(UUID batch, UUID correlation, String source, Path csv) throws Exception {
        var payload = json.createObjectNode();
        payload.put("batchId", batch.toString());
        payload.put("sourceSystem", source);
        payload.put("sourceType", "FILE");
        payload.put("sourceSchemaVersion", "1.0.0");
        payload.put("statePackVersion", "1.0.0");
        payload.put("pipelineTemplate", "generic-file-ingest.hpl");
        var p = payload.putObject("parameters");
        p.put("SOURCE_FILE", csv.toString());
        p.put("CSV_HEADER", "name,born");
        p.put("STATE_CODE", "XX");
        p.put("SOURCE_SYSTEM", source);
        p.put("SOURCE_ENTITY", source);
        p.put("SOURCE_SCHEMA_VERSION", "1.0.0");
        p.put("STATE_PACK_VERSION", "1.0.0");
        p.put("BATCH_ID", batch.toString());
        var envelope = json.createObjectNode();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "uddp.cmd.ingestion.ingest-requested");
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", "2026-11-04T00:00:00Z");
        envelope.put("producer", "test");
        envelope.put("partitionKey", batch.toString());
        envelope.put("correlationId", correlation.toString());
        envelope.set("payload", payload);
        return envelope.toString();
    }

    private KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer());
    }

    private List<JsonNode> consume(String topic, UUID correlation, int expected) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", "test-" + UUID.randomUUID(), "auto.offset.reset", "earliest"), new StringDeserializer(),
                new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 60_000;
            while (out.size() < expected && System.currentTimeMillis() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode envelope = json.readTree(record.value());
                    if (correlation.toString().equals(envelope.path("correlationId").asText())) {
                        out.add(envelope);
                    }
                }
            }
        }
        assertThat(out).as(topic).hasSize(expected);
        return out;
    }
}
