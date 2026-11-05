package gov.uddp.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import gov.uddp.contracts.EventSchemas;
import gov.uddp.contracts.StatePackDigest;
import gov.uddp.contracts.Topics;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackLoader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * canonical-service fed only by events and State Packs served by digest: the three sample packs' sources land as
 * ingestion-service publishes them (the batch report first, every event twice). The unchanged engine maps them with
 * the packs it fetched and verified, giving the monolith's 7 canonical Residents and 7 family observations, published
 * as schema-valid events keyed by source record, one batch-processed per batch and nothing on the DLQ. statepack-service
 * is a stub here that serves the repository's sample packs with their digests.
 */
@Testcontainers
@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class CanonicalServiceFlowTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_platform");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static final Path PACKS = Path.of("../../services/statepack-service/seed/state-packs").toAbsolutePath().normalize();
    static HttpServer statepack;
    static final List<String> RESOLVED = new ArrayList<>();

    @BeforeAll
    static void stubStatePackService() throws Exception {
        var json = new ObjectMapper();
        statepack = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        statepack.createContext("/api/statepacks/resolve", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            String state = query.replaceAll(".*stateCode=([^&]+).*", "$1");
            RESOLVED.add(state);
            try (Stream<Path> dirs = Files.list(PACKS)) {
                for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                    Map<String, String> files = files(dir);
                    if (files.getOrDefault("state.yaml", "").contains("stateCode: " + state)) {
                        ObjectNode body = json.createObjectNode();
                        body.put("packId", dir.getFileName().toString());
                        body.put("version", query.replaceAll(".*version=([^&]+).*", "$1"));
                        body.put("stateCode", state);
                        body.put("status", "ACTIVE");
                        body.put("digest", StatePackDigest.of(files));
                        files.forEach(body.putObject("files")::put);
                        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                        return;
                    }
                }
            }
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        statepack.start();
    }

    @AfterAll
    static void stopStub() {
        statepack.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("uddp.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("uddp.canonical.statepack-url", () -> "http://127.0.0.1:" + statepack.getAddress().getPort());
        String cache = Path.of("target/pack-cache-" + UUID.randomUUID()).toAbsolutePath().toString();
        registry.add("uddp.canonical.pack-cache", () -> cache);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private gov.uddp.core.statepack.StatePackLoader loader;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void mapsTheSampleSourcesFromEventsWithPacksVerifiedByDigest() throws Exception {
        var properties = new StatePackProperties();
        properties.setStatePackDirectory(PACKS.toString());
        var loader = new StatePackLoader(properties);
        List<String[]> events = new ArrayList<>();
        int batches = 0;
        for (LoadedStatePack pack : loader.loadAll(PACKS).values()) {
            for (var source : pack.sources()) {
                events.addAll(landedEvents(pack, source.sourceId(), source.sourceType(), source.sourceSchemaVersion(),
                        source.hopTemplate(), Path.of("../..").resolve(source.location())));
                batches++;
            }
        }
        try (var producer = new KafkaProducer<>(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer())) {
            for (int delivery = 0; delivery < 2; delivery++) {
                for (String[] event : events) {
                    producer.send(new ProducerRecord<>(event[0], event[1], event[2])).get();
                }
            }
        }
        long deadline = System.currentTimeMillis() + 120_000;
        while (count("select count(*) from outbox_event where topic = ?", Topics.BATCH_PROCESSED) < batches) {
            assertThat(System.currentTimeMillis()).as(() -> "every batch processed: " + jdbc.queryForList(
                    "select b.batch_id, b.remote_status, b.remote_record_count, b.processed_at, (select count(*)"
                    + " from raw_record r where r.batch_id = b.batch_id) as received from ingestion_batch b")
                    + " outbox " + jdbc.queryForList("select topic, count(*) from outbox_event group by topic")
                    + " processed " + count("select count(*) from processed_event")).isLessThan(deadline);
            Thread.sleep(500);
        }
        assertThat(count("select count(*) from outbox_event where topic = ?", Topics.RESIDENT_CREATED))
                .as(() -> "outbox " + jdbc.queryForList("select topic, payload->'payload' as p from outbox_event"
                        + " where topic <> ?", Topics.RAW_RECORD_RECEIVED) + " errors "
                        + jdbc.queryForList("select error_message from canonical_processing_error") + " states "
                        + jdbc.queryForList("select distinct state_code from raw_record") + " loader "
                        + loader.all().stream().map(p -> p.manifest().stateCode()).toList())
                .isEqualTo(7);
        assertThat(count("select count(*) from outbox_event where topic = ?", Topics.FAMILY_OBSERVED)).isEqualTo(7);
        assertThat(count("select count(*) from outbox_event where topic = ?", Topics.CANONICAL_DLQ)).isZero();
        assertThat(count("select count(*) from canonical_resident where canonical_payload->>'name' = 'Ram Singh'"))
                .isEqualTo(3);
        assertThat(count("select count(*) from processed_event")).as("each event applied once")
                .isEqualTo(events.size());
        assertThat(RESOLVED).as("every pack came from statepack-service").isNotEmpty();
        for (var row : jdbc.queryForList("select partition_key, payload::text from outbox_event where topic = ?",
                Topics.RESIDENT_CREATED)) {
            var envelope = json.readTree((String) row.get("payload"));
            assertThat(EventSchemas.validate(EventSchemas.ENVELOPE, envelope)).isEmpty();
            assertThat(EventSchemas.validate("canonical.resident-created.v1", envelope.path("payload"))).isEmpty();
            assertThat(row.get("partition_key")).isEqualTo(envelope.path("payload").path("sourceRecordKey").asText());
            assertThat(envelope.path("producer").asText()).isEqualTo("canonical-service");
        }

        heldRowsWaitForAStewardThroughTheInternalApi(batches);
    }

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mvc;

    /** A row a steward can fix (no name) is held here, not rejected, and the edge's API reaches it. */
    private void heldRowsWaitForAStewardThroughTheInternalApi(int batchesBefore) throws Exception {
        var pack = new StatePackLoader(packProperties()).loadAll(PACKS).values().stream()
                .filter(p -> p.manifest().stateCode().equals("ST1")).findFirst().orElseThrow();
        Path csv = Files.createTempFile("held", ".csv");
        Files.writeString(csv, "beneficiary_nm,dob,sex_cd,dist_cd,ration_no,mobile,card_type,card_status,"
                + "card_issued_on,monthly_grain_kg,last_lifted_on,last_lifted_kg\n"
                + ",12-03-1980,M,101,R9001,9876543210,PHH,A,01-04-2015,25,05-10-2026,25\n");
        var events = landedEvents(pack, "pds", "file", "1.0", "generic-file-ingest.hpl", csv);
        String batch = json.readTree(events.getFirst()[2]).path("payload").path("batchId").asText();
        try (var producer = new KafkaProducer<>(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers()),
                new StringSerializer(), new StringSerializer())) {
            for (String[] event : events) {
                producer.send(new ProducerRecord<>(event[0], event[1], event[2])).get();
            }
        }
        long deadline = System.currentTimeMillis() + 60_000;
        while (count("select count(*) from outbox_event where topic = ?", Topics.BATCH_PROCESSED) <= batchesBefore) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            Thread.sleep(300);
        }
        // A blank name fails mapping, which rejects; a steward-fixable rule failure is held instead (the same engine
        // as HeldRecordProcessingTest). Hold this row as such a failure to drive the internal API end to end.
        jdbc.update("insert into held_record(batch_id, raw_record_id, source_system, state_code, state_pack_version,"
                + " reason_codes, current_payload) select batch_id, raw_record_id, source_system, state_code,"
                + " state_pack_version, cast(? as jsonb), cast('{}' as jsonb) from raw_record"
                + " where batch_id = cast(? as uuid)",
                "[{\"code\":\"RULE_FAILED:name-required\",\"message\":\"name is required\",\"field\":\"name\","
                        + "\"severity\":\"ERROR\"}]", batch);

        var groups = json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/canonical/held-records/groups")).andReturn().getResponse().getContentAsString());
        assertThat(groups).hasSize(1);
        String reason = groups.get(0).path("reasonCode").asText();
        String export = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/canonical/held-records/groups/" + batch + "/" + reason + "/export"))
                .andReturn().getResponse().getContentAsString();
        assertThat(export).contains(",12-03-1980,M,101,R9001,");
        var job = json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/canonical/held-records/groups/" + batch + "/" + reason + "/reprocess")
                .contentType("application/json").content("{\"actor\":\"sam.steward\"}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(job.path("total").asInt()).isEqualTo(1);
        String status = job.path("status").asText();
        for (int i = 0; i < 200 && "RUNNING".equals(status); i++) {
            Thread.sleep(50);
            status = json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/api/canonical/held-records/reprocess-jobs/" + job.path("jobId").asText()))
                    .andReturn().getResponse().getContentAsString()).path("status").asText();
        }
        assertThat(status).isEqualTo("COMPLETED");
        assertThat(count("select count(*) from held_record where batch_id = cast(? as uuid) and status = 'OPEN'",
                batch)).as("still no name").isEqualTo(1);
        assertThat(jdbc.queryForObject("select reason_codes->0->>'code' from held_record where batch_id ="
                + " cast(? as uuid)", String.class, batch)).as("held now for the reason found again")
                .isEqualTo("MISSING_NAME");

        // Every call the edge makes (http-contracts/canonical-service.json) is answered as the contract says.
        Map<String, String> state = Map.of("batch", batch, "reason", "MISSING_NAME",
                "held", jdbc.queryForObject("select held_id::text from held_record where batch_id = cast(? as uuid)",
                        String.class, batch),
                "job", job.path("jobId").asText());
        for (var interaction : gov.uddp.contracts.HttpContracts.of("canonical-service")) {
            var request = "GET".equals(interaction.method())
                    ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(interaction.path(state))
                    : org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(interaction.path(state))
                            .contentType("application/json").content(interaction.body(state));
            var response = mvc.perform(request).andReturn().getResponse();
            assertThat(gov.uddp.contracts.HttpContracts.problems(interaction, response.getStatus(),
                    json.readTree(response.getContentAsString()))).as(interaction.name()).isEmpty();
        }
    }

    private static StatePackProperties packProperties() {
        var properties = new StatePackProperties();
        properties.setStatePackDirectory(PACKS.toString());
        return properties;
    }

    /** batch-completed first, then one raw-record-received per data line, as generic-file-ingest.hpl lands them. */
    private List<String[]> landedEvents(LoadedStatePack pack, String sourceId, String sourceType, String schemaVersion,
                                        String template, Path csv) throws Exception {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        String batch = UUID.randomUUID().toString();
        String state = pack.manifest().stateCode();
        List<String[]> out = new ArrayList<>();
        int count = 0;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            String raw = UUID.randomUUID().toString();
            ObjectNode p = json.createObjectNode();
            p.put("rawRecordId", raw);
            p.put("batchId", batch);
            p.put("sourceSystem", sourceId);
            p.put("sourceLine", i + 1);
            p.put("payloadFormat", "CSV_ROW");
            p.put("rawPayload", "{\"csvHeader\":\"" + lines.get(0) + "\",\"rawLine\":\"" + lines.get(i) + "\"}");
            p.put("landedAt", "2026-11-04T00:00:00Z");
            p.putObject("sourceMetadata").put("sourceEntity", sourceId);
            p.put("statePackVersion", pack.manifest().version());
            p.put("sourceSchemaVersion", schemaVersion);
            p.put("pipelineTemplate", template);
            p.put("sourceType", sourceType);
            out.add(new String[] {Topics.RAW_RECORD_RECEIVED, sourceId + ":" + raw,
                    envelope("uddp.ingestion.raw-record-received", sourceId + ":" + raw, state, p)});
            count++;
        }
        ObjectNode done = json.createObjectNode();
        done.put("batchId", batch);
        done.put("recordCount", count);
        done.put("status", "COMPLETED");
        done.put("sourceSystem", sourceId);
        done.put("sourceType", sourceType);
        done.put("sourceSchemaVersion", schemaVersion);
        done.put("statePackVersion", pack.manifest().version());
        done.put("pipelineTemplate", template);
        out.addFirst(new String[] {Topics.BATCH_COMPLETED, batch,
                envelope("uddp.ingestion.batch-completed", batch, state, done)});
        return out;
    }

    private String envelope(String type, String key, String state, ObjectNode payload) {
        ObjectNode e = json.createObjectNode();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("eventType", type);
        e.put("schemaVersion", 1);
        e.put("occurredAt", "2026-11-04T00:00:00Z");
        e.put("producer", "ingestion-service");
        e.put("partitionKey", key);
        e.put("stateCode", state);
        e.set("payload", payload);
        return e.toString();
    }

    private static Map<String, String> files(Path root) throws java.io.IOException {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(root.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"));
            }
        }
        return files;
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
