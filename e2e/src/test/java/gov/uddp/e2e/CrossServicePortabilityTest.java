package gov.uddp.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.contracts.Topics;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackLoader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Phase 4: portability across services. States 1 and 3 are onboarded first; then State 2 and State 4 (the M10
 * portability fixture) are onboarded by publishing their State Packs to statepack-service and landing their sample
 * files, with the same running service code. The services only exchange events: ingestion lands Raw, canonical maps it
 * with packs verified by digest, identity matches with the real Splink adapter, registry builds Golden records. The
 * results are the monolith's M10 numbers (9 raw, 9 canonical, 6 AUTO_MATCH, 6 Golden Residents, 8 Golden Families),
 * every baseline Golden ID is carried forward, and no service, contract or messaging source names a state.
 */
@Testcontainers
class CrossServicePortabilityTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    @TempDir
    static Path work;

    static Platform platform;
    static KafkaProducer<String, String> producer;
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @BeforeAll
    static void start() throws Exception {
        platform = new Platform(POSTGRES, KAFKA);
        platform.start(work);
        producer = new KafkaProducer<>(Map.<String, Object>of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "acks", "all"), new StringSerializer(), new StringSerializer());
    }

    @AfterAll
    static void stop() {
        if (producer != null) {
            producer.close();
        }
        if (platform != null) {
            platform.close();
        }
    }

    @Test
    void stateTwoAndStateFourOnboardByStatePackDataAloneAcrossServices() throws Exception {
        Path packs = Platform.ROOT.resolve("services/statepack-service/seed/state-packs");
        Path m10 = Platform.ROOT.resolve("services/statepack-service/seed/m10-state-packs");

        onboard(List.of(packs.resolve("state-1"), packs.resolve("state-3")));
        UUID baselineRun = matchAndBuild();
        List<String> baseline = registry().queryForList("select golden_resident_id::text from golden_resident"
                + " where status = 'ACTIVE' order by 1", String.class);
        assertThat(baseline).isNotEmpty();

        onboard(List.of(packs.resolve("state-2"), m10.resolve("state-4")));
        UUID run = matchAndBuild();
        assertThat(run).isNotEqualTo(baselineRun);

        assertThat(count(ingestion(), "select count(*) from raw_record")).as("raw").isEqualTo(9);
        assertThat(count(canonical(), "select count(*) from canonical_resident")).as("canonical").isEqualTo(9);
        assertThat(count(canonical(), "select count(*) from canonical_processing_error")).as("rejected").isZero();
        assertThat(count(identity(), "select count(*) from resident_match_pair where run_id = ? and decision ="
                + " 'AUTO_MATCH'", run)).as("AUTO_MATCH").isEqualTo(6);
        assertThat(count(registry(), "select count(*) from golden_resident where status = 'ACTIVE'"))
                .as("golden").isEqualTo(6);
        assertThat(count(registry(), "select count(*) from golden_family where status = 'ACTIVE'"))
                .as("families").isEqualTo(8);
        assertThat(registry().queryForList("select golden_resident_id::text from golden_resident where status ="
                + " 'ACTIVE'", String.class)).as("every baseline Golden ID is carried forward")
                .containsAll(baseline);
        assertThat(count(statepack(), "select count(*) from state_pack_version where status = 'ACTIVE'")).isEqualTo(4);
    }

    /** An LGD snapshot the edge published reaches canonical-service's and statepack-service's masters over Kafka. */
    @Test
    void geographySnapshotsReachEveryCopyOfTheMaster() throws Exception {
        var rows = new ArrayList<gov.uddp.core.geography.LgdRow>();
        rows.add(new gov.uddp.core.geography.LgdRow(1, "STATE", "77", "Replicated State", "ACTIVE", null, null, null));
        for (int i = 0; i < 2500; i++) {
            rows.add(new gov.uddp.core.geography.LgdRow(i + 2, "DISTRICT", String.valueOf(770000 + i),
                    "Replicated District " + i, "ACTIVE", "STATE", "77", null));
        }
        var snapshot = new gov.uddp.core.geography.LgdSnapshotData("edge-sync", java.time.LocalDate.of(2025, 2, 1),
                rows);
        for (var envelope : gov.uddp.core.geography.GeographyReplication.envelopes(UUID.randomUUID(), snapshot,
                "edge-service", gov.uddp.core.geography.GeographyReplication.CHUNK_ROWS)) {
            producer.send(new ProducerRecord<>(Topics.GEOGRAPHY_SNAPSHOT_PUBLISHED, envelope.partitionKey(),
                    gov.uddp.messaging.Outbox.json().writeValueAsString(envelope))).get();
        }
        await("canonical-service imported the snapshot", () -> count(canonical(),
                "select count(*) from lgd_entity where code like '77%'") == 2501);
        await("statepack-service imported the snapshot", () -> count(statepack(),
                "select count(*) from lgd_entity where code like '77%'") == 2501);
        assertThat(count(canonical(), "select count(*) from lgd_snapshot_chunk")).isZero();
    }

    @Test
    void noServiceContractOrMessagingSourceNamesAState() throws Exception {
        Pattern state = Pattern.compile("(?i)\\bst[1-9]\\b|state[-_ ]?[1-9]\\b|DEPT-[1-9]|\"ST\\d\"");
        List<String> offenders = new ArrayList<>();
        for (Path root : List.of(Platform.ROOT.resolve("services"), Platform.ROOT.resolve("contracts/src/main"),
                Platform.ROOT.resolve("messaging/src/main"))) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(f -> f.toString().contains("src" + java.io.File.separator + "main"))
                        // The edge's historical Flyway migrations are immutable upgrade records of the monolith; some
                        // name legacy sample states to migrate old installations (as the portability guard allows).
                        .filter(f -> !f.toString().contains("db" + java.io.File.separator + "migration"))
                        .filter(f -> f.toString().matches(".*\\.(java|sql|yml|yaml|json|py)$")).toList()) {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    if (state.matcher(text).find() || state.matcher(file.getFileName().toString()).find()) {
                        offenders.add(Platform.ROOT.relativize(file).toString());
                    }
                }
            }
        }
        assertThat(offenders).as("production code of the services must stay state-agnostic").isEmpty();
    }

    /** Publishes, approves and activates each pack, then lands every source of it through ingestion-service. */
    private void onboard(List<Path> packDirectories) throws Exception {
        var properties = new StatePackProperties();
        int before = count(canonical(), "select count(*) from ingestion_batch where processed_at is not null");
        int batches = 0;
        for (Path dir : packDirectories) {
            Map<String, String> files = files(dir);
            ObjectNode published = post("/api/statepacks", Map.of("maker", "pack-author", "files", files));
            String packId = published.path("packId").asText();
            String version = published.path("version").asText();
            post("/api/statepacks/" + packId + "/" + version + "/approve", Map.of("checker", "pack-reviewer"));
            post("/api/statepacks/" + packId + "/" + version + "/activate", Map.of("actor", "pack-reviewer"));

            properties.setStatePackDirectory(dir.getParent().toString());
            LoadedStatePack pack = new StatePackLoader(properties).load(dir);
            for (var source : pack.sources()) {
                Path csv = Platform.ROOT.resolve(source.location());
                String header = Files.readAllLines(csv, StandardCharsets.UTF_8).getFirst();
                UUID batch = UUID.randomUUID();
                ObjectNode p = JSON.createObjectNode();
                p.put("batchId", batch.toString());
                p.put("sourceSystem", source.sourceId());
                p.put("sourceType", source.sourceType());
                p.put("sourceSchemaVersion", source.sourceSchemaVersion());
                p.put("statePackVersion", version);
                p.put("pipelineTemplate", source.hopTemplate());
                ObjectNode params = p.putObject("parameters");
                params.put("SOURCE_FILE", csv.toString());
                params.put("CSV_HEADER", header);
                params.put("STATE_CODE", pack.manifest().stateCode());
                params.put("SOURCE_SYSTEM", source.sourceId());
                params.put("SOURCE_ENTITY", source.sourceId());
                params.put("SOURCE_SCHEMA_VERSION", source.sourceSchemaVersion());
                params.put("STATE_PACK_VERSION", version);
                params.put("BATCH_ID", batch.toString());
                command(Topics.CMD_INGEST_REQUESTED, "uddp.cmd.ingestion.ingest-requested", batch.toString(), p);
                batches++;
            }
        }
        int expected = before + batches;
        await("canonical-service processed " + expected + " batches", () -> count(canonical(),
                "select count(*) from ingestion_batch where processed_at is not null") >= expected);
        int canonicalCount = count(canonical(), "select count(*) from canonical_resident");
        await("identity-service has every canonical Resident", () -> count(identity(),
                "select count(*) from candidate_resident") >= canonicalCount);
        await("registry-service has every canonical Resident and family", () -> count(registry(),
                "select count(*) from canonical_resident") >= canonicalCount
                && count(registry(), "select count(*) from canonical_family_observation") >= count(canonical(),
                "select count(*) from canonical_family_observation"));
    }

    /** A FULL matching run in identity-service, then a Golden build in registry-service; returns the run. */
    private UUID matchAndBuild() throws Exception {
        UUID correlation = UUID.randomUUID();
        command(Topics.CMD_MATCHING_REQUESTED, "uddp.cmd.identity.matching-requested", correlation.toString(),
                JSON.createObjectNode().put("mode", "FULL"), correlation);
        await("identity-service completed the run", () -> count(identity(),
                "select count(*) from matching_run where correlation_id = ? and status = 'COMPLETED'", correlation) == 1);
        UUID run = identity().queryForObject("select run_id from matching_run where correlation_id = ?", UUID.class,
                correlation);
        int builds = count(registry(), "select count(*) from golden_build");
        command(Topics.CMD_GOLDEN_BUILD_REQUESTED, "uddp.cmd.registry.golden-build-requested", run.toString(),
                JSON.createObjectNode().put("matchingRunId", run.toString()));
        await("registry-service built Golden records for the run", () -> count(registry(),
                "select count(*) from golden_build where matching_run_id = ? and completed_at is not null", run) == 1
                && count(registry(), "select count(*) from golden_build") > builds);
        return run;
    }

    private void command(String topic, String type, String key, ObjectNode payload) throws Exception {
        command(topic, type, key, payload, null);
    }

    private void command(String topic, String type, String key, ObjectNode payload, UUID correlation) throws Exception {
        ObjectNode e = JSON.createObjectNode();
        e.put("eventId", UUID.randomUUID().toString());
        e.put("eventType", type);
        e.put("schemaVersion", 1);
        e.put("occurredAt", "2026-11-04T00:00:00Z");
        e.put("producer", "e2e-onboarding");
        e.put("partitionKey", key);
        if (correlation != null) {
            e.put("correlationId", correlation.toString());
        }
        e.set("payload", payload);
        producer.send(new ProducerRecord<>(topic, key, e.toString())).get();
    }

    private ObjectNode post(String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + Platform.port(platform.statepack) + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + " " + response.body()).isBetween(200, 201);
        return (ObjectNode) JSON.readTree(response.body());
    }

    private static Map<String, String> files(Path root) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(root.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"));
            }
        }
        return files;
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 300_000;
        while (!condition.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as("timed out waiting: " + what).isLessThan(deadline);
            Thread.sleep(500);
        }
    }

    private static int count(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static JdbcTemplate statepack() {
        return Platform.jdbc(platform.statepack);
    }

    private static JdbcTemplate ingestion() {
        return Platform.jdbc(platform.ingestion);
    }

    private static JdbcTemplate canonical() {
        return Platform.jdbc(platform.canonical);
    }

    private static JdbcTemplate identity() {
        return Platform.jdbc(platform.identity);
    }

    private static JdbcTemplate registry() {
        return Platform.jdbc(platform.registry);
    }
}
