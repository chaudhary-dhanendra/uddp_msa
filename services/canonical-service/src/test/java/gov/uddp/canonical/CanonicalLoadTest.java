package gov.uddp.canonical;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import gov.uddp.contracts.StatePackDigest;
import gov.uddp.contracts.Topics;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
 * Load test (opt-in: {@code -Dmsa.load=true}, rows with {@code -Dmsa.load.canonical-rows}, default 20,000): one
 * State 1 batch of distinct people arrives as raw-record-received events over Kafka, as ingestion-service publishes it,
 * and is mapped, validated and announced as resident-created. Prints the rows per second from the first event sent to
 * batch-processed.
 */
@Testcontainers
@SpringBootTest
@EnabledIfSystemProperty(named = "msa.load", matches = "true")
class CanonicalLoadTest {

    static final int ROWS = Integer.getInteger("msa.load.canonical-rows", 20_000);
    static final Path PACKS = CanonicalServiceFlowTest.PACKS;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_platform")
            .withCommand("postgres", "-c", "shared_buffers=512MB", "-c", "max_wal_size=4GB", "-c", "fsync=off");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static HttpServer statepack;

    @BeforeAll
    static void stubStatePackService() throws Exception {
        var json = new ObjectMapper();
        statepack = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        statepack.createContext("/api/statepacks/resolve", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            Path dir = PACKS.resolve("state-1");
            Map<String, String> files = files(dir);
            ObjectNode body = json.createObjectNode();
            body.put("packId", "state-1");
            body.put("version", query.replaceAll(".*version=([^&]+).*", "$1"));
            body.put("stateCode", "ST1");
            body.put("status", "ACTIVE");
            body.put("digest", StatePackDigest.of(files));
            files.forEach(body.putObject("files")::put);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
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

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void mapsALargeBatchArrivingOverKafka() throws Exception {
        String header = "beneficiary_nm,dob,sex_cd,dist_cd,ration_no,mobile,card_type,card_status,card_issued_on,"
                + "monthly_grain_kg,last_lifted_on,last_lifted_kg";
        String batch = UUID.randomUUID().toString();
        String version = Files.readString(PACKS.resolve("state-1/state.yaml")).replaceAll("(?s).*version: *([^\\s]+).*",
                "$1");
        long started = System.nanoTime();
        try (var producer = new KafkaProducer<>(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "linger.ms", "20", "batch.size", "262144"), new StringSerializer(), new StringSerializer())) {
            ObjectNode done = json.createObjectNode();
            done.put("batchId", batch);
            done.put("recordCount", ROWS);
            done.put("status", "COMPLETED");
            done.put("sourceSystem", "pds");
            done.put("sourceType", "file");
            done.put("sourceSchemaVersion", "1.0");
            done.put("statePackVersion", version);
            done.put("pipelineTemplate", "generic-file-ingest.hpl");
            producer.send(new ProducerRecord<>(Topics.BATCH_COMPLETED, batch,
                    envelope("uddp.ingestion.batch-completed", batch, done)));
            for (int i = 0; i < ROWS; i++) {
                String raw = UUID.randomUUID().toString();
                String line = "Person " + i + ",12-03-1980," + (i % 2 == 0 ? "M" : "F") + ",101,R" + (100000 + i)
                        + ",98765" + String.format("%05d", i % 100000) + ",PHH,A,01-04-2015,25,05-10-2026,25";
                ObjectNode p = json.createObjectNode();
                p.put("rawRecordId", raw);
                p.put("batchId", batch);
                p.put("sourceSystem", "pds");
                p.put("sourceLine", i + 2);
                p.put("payloadFormat", "CSV_ROW");
                p.put("rawPayload", "{\"csvHeader\":\"" + header + "\",\"rawLine\":\"" + line + "\"}");
                p.put("landedAt", "2026-11-06T00:00:00Z");
                p.putObject("sourceMetadata").put("sourceEntity", "pds");
                p.put("statePackVersion", version);
                p.put("sourceSchemaVersion", "1.0");
                p.put("pipelineTemplate", "generic-file-ingest.hpl");
                p.put("sourceType", "file");
                producer.send(new ProducerRecord<>(Topics.RAW_RECORD_RECEIVED, "pds:" + raw,
                        envelope("uddp.ingestion.raw-record-received", "pds:" + raw, p)));
            }
            producer.flush();
        }
        double sent = (System.nanoTime() - started) / 1e9;
        long deadline = System.currentTimeMillis() + 3_600_000;
        while (count("select count(*) from outbox_event where topic = ?", Topics.BATCH_PROCESSED) == 0) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            Thread.sleep(1000);
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        long created = count("select count(*) from canonical_resident");
        System.out.printf("LOAD canonical %,d rows: sent in %.1f s, mapped and announced in %.1f s (%,.0f rows/s);"
                + " %,d residents%n", ROWS, sent, seconds, ROWS / seconds, created);
        assertThat(created).as(() -> "errors " + jdbc.queryForList("select reason_code, count(*) from"
                + " canonical_processing_error group by reason_code")).isEqualTo(ROWS);
    }

    private String envelope(String type, String key, ObjectNode payload) {
        ObjectNode e = json.createObjectNode();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("eventType", type);
        e.put("schemaVersion", 1);
        e.put("occurredAt", "2026-11-06T00:00:00Z");
        e.put("producer", "ingestion-service");
        e.put("partitionKey", key);
        e.put("stateCode", "ST1");
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
