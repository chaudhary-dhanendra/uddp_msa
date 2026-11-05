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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Sources without a shared disk or shared credentials: a delivered file is uploaded once and ingested by its fileRef
 * (its landed copy replaces any caller path), and a database-table source is named only by its connectionRef: this
 * service reads the header with its own credentials and hands Hop the connection and the generic SELECT, while no
 * command or event carries the password. Hop is a fixture that does what generic-file-ingest.hpl and
 * generic-db-ingest.hpl do.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestionSourcesTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_raw");

    /** A department's database, reached only through ingestion-service's own connection settings. */
    @Container
    static final PostgreSQLContainer<?> SOURCE = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("department").withUsername("reader").withPassword("source-secret-value");

    static Path landing;

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) throws Exception {
        landing = Files.createTempDirectory("uddp-landing-test");
        registry.add("uddp.ingestion.landing-directory", landing::toString);
        registry.add("uddp.ingestion.connections.department.host", SOURCE::getHost);
        registry.add("uddp.ingestion.connections.department.port", () -> SOURCE.getMappedPort(5432));
        registry.add("uddp.ingestion.connections.department.database", SOURCE::getDatabaseName);
        registry.add("uddp.ingestion.connections.department.user", SOURCE::getUsername);
        registry.add("uddp.ingestion.connections.department.password", SOURCE::getPassword);
    }

    /** Lands rows as the generic templates do: file lines, or the raw lines the generic SELECT returns. */
    @TestConfiguration
    static class FixtureHop {
        @Bean
        @Primary
        HopExecutor fixtureHop(JdbcTemplate jdbc) {
            return new HopExecutor() {
                @Override
                public Result run(String template, Map<String, String> p) {
                    return run(template, p, Map.of());
                }

                @Override
                public Result run(String template, Map<String, String> p, Map<String, String> secret) {
                    try {
                        List<String> lines;
                        if (template.equals("generic-db-ingest.hpl")) {
                            String url = "jdbc:postgresql://" + secret.get("UDDP_SOURCE_HOST") + ":"
                                    + secret.get("UDDP_SOURCE_PORT") + "/" + secret.get("UDDP_SOURCE_DB");
                            try (var c = DriverManager.getConnection(url, secret.get("UDDP_SOURCE_USER"),
                                    secret.get("UDDP_SOURCE_PASSWORD")); var s = c.createStatement();
                                    var r = s.executeQuery(secret.get("UDDP_SOURCE_SQL"))) {
                                lines = new java.util.ArrayList<>(List.of("header"));
                                while (r.next()) {
                                    lines.add(r.getString(1));
                                }
                            }
                        } else {
                            lines = Files.readAllLines(Path.of(p.get("SOURCE_FILE")), StandardCharsets.UTF_8);
                        }
                        for (int i = 1; i < lines.size(); i++) {
                            jdbc.update("insert into raw_record (batch_id, state_code, source_system, raw_payload,"
                                    + " source_metadata, state_pack_version, pipeline_template, source_line)"
                                    + " values (?::uuid, ?, ?, ?, '{}'::jsonb, ?, ?, ?)", p.get("BATCH_ID"),
                                    p.get("STATE_CODE"), p.get("SOURCE_SYSTEM"), "{\"csvHeader\":\"" + p.get("CSV_HEADER")
                                    + "\",\"rawLine\":\"" + lines.get(i) + "\"}", p.get("STATE_PACK_VERSION"),
                                    template, i + 1);
                        }
                        return new Result(0, "landed");
                    } catch (Exception e) {
                        return new Result(1, e.getMessage());
                    }
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
    void anUploadedFileIsIngestedByItsFileRef() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        byte[] content = "name,born\nAsha,1990-01-01\nRavi,1985-05-05\n".getBytes(StandardCharsets.UTF_8);
        var response = http.postForObject("/api/ingestion/files?name=people.csv", new HttpEntity<>(content, headers),
                Map.class);
        String fileRef = (String) response.get("fileRef");
        assertThat(fileRef).matches("sha256:[0-9a-f]{64}/people\\.csv");
        assertThat(http.postForObject("/api/ingestion/files?name=people.csv", new HttpEntity<>(content, headers),
                Map.class).get("fileRef")).as("the same content gets the same fileRef").isEqualTo(fileRef);

        UUID batch = UUID.randomUUID();
        ObjectNode request = request(batch, "generic-file-ingest.hpl", "people-register");
        request.put("fileRef", fileRef);
        request.withObject("parameters").put("CSV_HEADER", "name,born");
        request.withObject("parameters").put("SOURCE_FILE", "C:/somewhere/only/the/caller/sees.csv");

        assertThat(ingestions.ingest(request, null)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from raw_record where batch_id = ?", Integer.class, batch))
                .isEqualTo(2);
    }

    @Test
    void aDatabaseSourceRunsWithThisServicesCredentialsOnly() throws Exception {
        try (var c = DriverManager.getConnection(SOURCE.getJdbcUrl(), SOURCE.getUsername(), SOURCE.getPassword());
                var s = c.createStatement()) {
            s.execute("create table citizens (citizen_id text, full_name text, born date)");
            s.execute("insert into citizens values ('C1', 'Kiran Rao', '1971-04-02'), ('C2', 'Rao, Meena', null)");
        }
        UUID batch = UUID.randomUUID();
        ObjectNode request = request(batch, "generic-db-ingest.hpl", "citizen-register");
        request.put("connectionRef", "department");
        request.put("table", "citizens");

        assertThat(ingestions.ingest(request, null)).isEqualTo("COMPLETED");
        List<String> payloads = jdbc.queryForList("select raw_payload from raw_record where batch_id = ?"
                + " order by source_line", String.class, batch);
        assertThat(payloads).containsExactly(
                "{\"csvHeader\":\"citizen_id,full_name,born\",\"rawLine\":\"C1,Kiran Rao,1971-04-02\"}",
                "{\"csvHeader\":\"citizen_id,full_name,born\",\"rawLine\":\"C2,\"Rao, Meena\",\"}");
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where payload::text like ?", Integer.class,
                "%" + SOURCE.getPassword() + "%")).as("no event carries the source password").isZero();
    }

    @Test
    void aSourceWithAChangeColumnLoadsOnlyRowsChangedSinceTheLastBatch() throws Exception {
        try (var c = DriverManager.getConnection(SOURCE.getJdbcUrl(), SOURCE.getUsername(), SOURCE.getPassword());
                var s = c.createStatement()) {
            s.execute("create table ration_cards (card_no text, holder text, updated_at timestamp)");
            s.execute("insert into ration_cards values ('R1', 'Asha', '2026-01-01 10:00'),"
                    + " ('R2', 'Ravi', '2026-01-02 10:00')");
        }
        assertThat(delta(UUID.randomUUID())).containsExactly("R1,Asha,2026-01-01T10:00:00",
                "R2,Ravi,2026-01-02T10:00:00");
        assertThat(delta(UUID.randomUUID())).as("nothing changed").isEmpty();

        try (var c = DriverManager.getConnection(SOURCE.getJdbcUrl(), SOURCE.getUsername(), SOURCE.getPassword());
                var s = c.createStatement()) {
            s.execute("update ration_cards set holder = 'Ravi Kumar', updated_at = '2026-02-01 09:00'"
                    + " where card_no = 'R2'");
            s.execute("insert into ration_cards values ('R3', 'Meera', '2026-02-02 09:00')");
        }
        assertThat(delta(UUID.randomUUID())).containsExactly("R2,Ravi Kumar,2026-02-01T09:00:00",
                "R3,Meera,2026-02-02T09:00:00");

        assertThat(http.exchange("/api/ingestion/sources/XX/ration-cards/watermark",
                org.springframework.http.HttpMethod.DELETE, null, Map.class).getBody()).containsEntry("reset", true);
        assertThat(delta(UUID.randomUUID())).as("after a reset the whole table is read again").hasSize(3);
    }

    /** Ingests the ration_cards delta source as a new batch; returns the raw lines it landed. */
    private List<String> delta(UUID batch) throws Exception {
        ObjectNode request = request(batch, "generic-db-ingest.hpl", "ration-cards");
        request.put("connectionRef", "department");
        request.put("table", "ration_cards");
        request.put("changeColumn", "updated_at");
        assertThat(ingestions.ingest(request, null)).isEqualTo("COMPLETED");
        return jdbc.queryForList("select raw_payload::json->>'rawLine' from raw_record where batch_id = ?"
                + " order by source_line", String.class, batch);
    }

    @Test
    void studioPreviewsReadRowsWithThisServicesCredentialsAndNeverReturnThePassword() throws Exception {
        try (var c = DriverManager.getConnection(SOURCE.getJdbcUrl(), SOURCE.getUsername(), SOURCE.getPassword());
                var s = c.createStatement()) {
            s.execute("create table if not exists preview_people (person_id text, full_name text)");
            s.execute("insert into preview_people values ('P1', 'Asha'), ('P2', 'Ravi'), ('P3', 'Mira')");
        }
        String listed = http.getForObject("/api/ingestion/connections", String.class);
        assertThat(listed).contains("\"department\"").contains("\"passwordSet\":true")
                .doesNotContain(SOURCE.getPassword());
        var rows = json.readTree(http.postForObject("/api/ingestion/sources/rows",
                Map.of("connectionRef", "department", "table", "preview_people", "limit", 2), String.class));
        assertThat(rows.path("header").toString()).isEqualTo("[\"person_id\",\"full_name\"]");
        assertThat(rows.path("rows")).hasSize(2);
        assertThat(rows.path("rows").get(0).path("full_name").asText()).isEqualTo("Asha");
    }

    /** Provider side of the ingestion-service HTTP contract (contracts/http-contracts/ingestion-service.json). */
    @Test
    void everyInteractionOfTheHttpContractIsHonoured() throws Exception {
        try (var c = DriverManager.getConnection(SOURCE.getJdbcUrl(), SOURCE.getUsername(), SOURCE.getPassword());
                var s = c.createStatement()) {
            s.execute("create table if not exists contract_people (person_id text, full_name text)");
            s.execute("insert into contract_people values ('P1', 'Asha'), ('P2', 'Ravi')");
        }
        Map<String, String> state = Map.of("connection", "department", "table", "contract_people");
        for (var interaction : gov.uddp.contracts.HttpContracts.of("ingestion-service")) {
            var headers = new org.springframework.http.HttpHeaders();
            headers.add("Content-Type", interaction.contentType());
            Object body = interaction.rawBody() != null ? interaction.rawBody().getBytes(StandardCharsets.UTF_8)
                    : interaction.body(state);
            var response = http.exchange(interaction.path(state),
                    org.springframework.http.HttpMethod.valueOf(interaction.method()),
                    new org.springframework.http.HttpEntity<>(body, headers), String.class);
            var answer = response.getBody() == null ? null : json.readTree(response.getBody());
            assertThat(gov.uddp.contracts.HttpContracts.problems(interaction, response.getStatusCode().value(), answer))
                    .as(interaction.name() + ": " + response.getBody()).isEmpty();
        }
    }

    private ObjectNode request(UUID batch, String template, String source) {
        ObjectNode r = json.createObjectNode();
        r.put("batchId", batch.toString());
        r.put("sourceSystem", source);
        r.put("sourceType", template.contains("-db-") ? "DATABASE" : "FILE");
        r.put("sourceSchemaVersion", "1.0.0");
        r.put("statePackVersion", "1.0.0");
        r.put("pipelineTemplate", template);
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
