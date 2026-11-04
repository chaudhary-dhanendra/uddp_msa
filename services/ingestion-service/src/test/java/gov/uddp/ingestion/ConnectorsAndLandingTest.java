package gov.uddp.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Source connectors and landing folders, now owned by ingestion-service: a connector is saved over HTTP with its
 * credential (stored encrypted here, never returned), tested and previewed; an ingest with delivery "auto" stages the
 * connector's delivery or takes the oldest file of a landing folder (which then moves to processed/). Hop is a fixture
 * that lands each line of SOURCE_FILE as generic-file-ingest.hpl does.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConnectorsAndLandingTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_raw");

    @Container
    static final PostgreSQLContainer<?> DEPARTMENT = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("department").withUsername("reader").withPassword("connector-secret-value");

    static Path root;

    @DynamicPropertySource
    static void folders(DynamicPropertyRegistry registry) throws Exception {
        root = Files.createTempDirectory("uddp-landing-root");
        registry.add("uddp.ingestion.landing-folders-root", root::toString);
        registry.add("uddp.ingestion.landing-directory", () -> root.resolve("landing").toString());
        registry.add("uddp.ingestion.secret-key-file", () -> root.resolve("secret.key").toString());
    }

    @TestConfiguration
    static class FixtureHop {
        @Bean
        @Primary
        HopExecutor fixtureHop(JdbcTemplate jdbc) {
            return (template, p) -> {
                try {
                    List<String> lines = Files.readAllLines(Path.of(p.get("SOURCE_FILE")), StandardCharsets.UTF_8);
                    for (int i = 1; i < lines.size(); i++) {
                        jdbc.update("insert into raw_record (batch_id, state_code, source_system, raw_payload,"
                                + " source_metadata, state_pack_version, pipeline_template, source_line)"
                                + " values (?::uuid, ?, ?, ?, '{}'::jsonb, ?, ?, ?)", p.get("BATCH_ID"),
                                p.get("STATE_CODE"), p.get("SOURCE_SYSTEM"), "{\"csvHeader\":\"" + p.get("CSV_HEADER")
                                + "\",\"rawLine\":\"" + lines.get(i) + "\"}", p.get("STATE_PACK_VERSION"), template,
                                i + 1);
                    }
                    return new HopExecutor.Result(0, "landed");
                } catch (Exception e) {
                    return new HopExecutor.Result(1, e.getMessage());
                }
            };
        }
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private Ingestions ingestions;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void aConnectorIsSavedTestedPreviewedAndStagesTheDelivery() throws Exception {
        try (var c = DriverManager.getConnection(DEPARTMENT.getJdbcUrl(), DEPARTMENT.getUsername(),
                DEPARTMENT.getPassword()); var s = c.createStatement()) {
            s.execute("create table pensioners (pension_id text, full_name text)");
            s.execute("insert into pensioners values ('P1', 'Asha Devi'), ('P2', 'Ravi Kumar'), ('P3', 'Meena')");
        }
        var body = Map.of("type", "jdbc",
                "settings", Map.of("dbType", "postgresql", "host", DEPARTMENT.getHost(),
                        "port", String.valueOf(DEPARTMENT.getMappedPort(5432)), "database", "department",
                        "user", "reader", "table", "pensioners"),
                "credentials", Map.of("password", DEPARTMENT.getPassword()));
        var saved = http.exchange("/api/ingestion/connectors/XX/pension", HttpMethod.PUT, new HttpEntity<>(body),
                String.class);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saved.getBody()).doesNotContain(DEPARTMENT.getPassword()).contains("passwordRef");
        assertThat(jdbc.queryForObject("select count(*) from connector_secret where ciphertext like ?", Integer.class,
                "%" + DEPARTMENT.getPassword() + "%")).as("stored encrypted").isZero();

        assertThat(http.postForEntity("/api/ingestion/connectors/XX/pension/test", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        var preview = json.readTree(http.getForObject("/api/ingestion/connectors/XX/pension/preview?rows=2",
                String.class));
        assertThat(preview.path("header").toString()).isEqualTo("[\"pension_id\",\"full_name\"]");
        assertThat(preview.path("rows")).hasSize(2);

        UUID batch = UUID.randomUUID();
        assertThat(ingestions.ingest(auto(batch, "pension", null), null)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForList("select raw_payload from raw_record where batch_id = ? order by source_line",
                String.class, batch)).containsExactly(
                "{\"csvHeader\":\"pension_id,full_name\",\"rawLine\":\"P1,Asha Devi\"}",
                "{\"csvHeader\":\"pension_id,full_name\",\"rawLine\":\"P2,Ravi Kumar\"}",
                "{\"csvHeader\":\"pension_id,full_name\",\"rawLine\":\"P3,Meena\"}");
    }

    @Test
    void aLandingFolderDeliversItsOldestFileOnceAndIsTestedHere() throws Exception {
        Path folder = Files.createDirectories(root.resolve("inbox/households"));
        assertThat(http.postForEntity("/api/ingestion/sources/test", Map.of("stateCode", "XX", "sourceSystem",
                "households", "location", "inbox/households"), Map.class).getStatusCode())
                .as("an empty landing folder fails SOURCE_TESTED").isEqualTo(HttpStatus.BAD_REQUEST);
        Files.writeString(folder.resolve("2026-11-01.csv"), "household,members\nH1,3\nH2,4\n");
        Files.writeString(folder.resolve("2026-11-02.csv"), "household,members\nH3,2\n");
        assertThat(http.getForObject("/api/ingestion/landing/waiting?location=inbox/households", List.class))
                .containsExactly("2026-11-01.csv", "2026-11-02.csv");
        assertThat(http.getForEntity("/api/ingestion/landing/waiting?location=inbox/none", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        UUID batch = UUID.randomUUID();
        assertThat(ingestions.ingest(auto(batch, "households", "inbox/households"), null)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from raw_record where batch_id = ?", Integer.class, batch))
                .isEqualTo(2);
        assertThat(http.getForObject("/api/ingestion/landing/waiting?location=inbox/households", List.class))
                .containsExactly("2026-11-02.csv");
        try (var processed = Files.list(folder.resolve("processed"))) {
            assertThat(processed.map(f -> f.getFileName().toString()).toList()).singleElement().asString()
                    .endsWith("2026-11-01.csv");
        }
    }

    private ObjectNode auto(UUID batch, String source, String location) {
        ObjectNode r = json.createObjectNode();
        r.put("batchId", batch.toString());
        r.put("sourceSystem", source);
        r.put("sourceType", "file");
        r.put("sourceSchemaVersion", "1.0.0");
        r.put("statePackVersion", "1.0.0");
        r.put("pipelineTemplate", "generic-file-ingest.hpl");
        r.put("delivery", "auto");
        r.put("location", location);
        ObjectNode p = r.putObject("parameters");
        p.put("STATE_CODE", "XX");
        p.put("SOURCE_SYSTEM", source);
        p.put("SOURCE_ENTITY", source);
        p.put("SOURCE_SCHEMA_VERSION", "1.0.0");
        p.put("STATE_PACK_VERSION", "1.0.0");
        p.put("BATCH_ID", batch.toString());
        return r;
    }
}
