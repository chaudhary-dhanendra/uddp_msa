package gov.uddp.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
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

/**
 * Real Apache Hop through ingestion-service (not a fixture): the generic templates write raw_record in this service's
 * own schema (RAW_SCHEMA=ingestion) through the repository's metadata, for a delivered CSV file and for a
 * database-table source read with this service's connection. Runs only with {@code -Dmsa.hop=true} and HOP_HOME set,
 * because real Hop runs stay outside the regular test suite.
 */
@Testcontainers
@SpringBootTest
class RealHopIngestionTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_raw");

    @Container
    static final PostgreSQLContainer<?> SOURCE = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("department").withUsername("reader").withPassword("source-secret-value");

    static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();

    @BeforeAll
    static void requiresHop() {
        assumeTrue(Boolean.getBoolean("msa.hop"), "real Hop runs only with -Dmsa.hop=true");
        assumeTrue(System.getenv("HOP_HOME") != null, "HOP_HOME is not set");
    }

    @DynamicPropertySource
    static void hop(DynamicPropertyRegistry registry) {
        registry.add("uddp.ingestion.hop-home", () -> System.getenv().getOrDefault("HOP_HOME", ""));
        registry.add("uddp.ingestion.repository-root", ROOT::toString);
        registry.add("uddp.ingestion.connections.department.host", SOURCE::getHost);
        registry.add("uddp.ingestion.connections.department.port", () -> SOURCE.getMappedPort(5432));
        registry.add("uddp.ingestion.connections.department.database", SOURCE::getDatabaseName);
        registry.add("uddp.ingestion.connections.department.user", SOURCE::getUsername);
        registry.add("uddp.ingestion.connections.department.password", SOURCE::getPassword);
    }

    @Autowired
    private Ingestions ingestions;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theGenericFileTemplateLandsTheSampleFileInTheIngestionSchema() throws Exception {
        Path csv = ROOT.resolve("services/ingestion-service/seed/sample-data/state-1/pds.csv");
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        UUID batch = UUID.randomUUID();
        ObjectNode request = request(batch, "generic-file-ingest.hpl", "pds");
        request.withObject("parameters").put("SOURCE_FILE", csv.toString());
        request.withObject("parameters").put("CSV_HEADER", lines.getFirst());

        String status = ingestions.ingest(request, null);

        assertThat(status).as(String.valueOf(jdbc.queryForObject("select error_message from ingestion_batch"
                + " where batch_id = ?", String.class, batch))).isEqualTo("COMPLETED");
        long data = lines.stream().skip(1).filter(line -> !line.isBlank()).count();
        assertThat(jdbc.queryForObject("select count(*) from ingestion.raw_record where batch_id = ?", Long.class,
                batch)).isEqualTo(data);
        assertThat(jdbc.queryForObject("select raw_payload from ingestion.raw_record where batch_id = ?"
                + " order by source_line limit 1", String.class, batch)).contains(lines.get(1));
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where topic ="
                + " 'uddp.ingestion.raw-record-received.v1'", Long.class)).isGreaterThanOrEqualTo(data);
    }

    @Test
    void theGenericDatabaseTemplateReadsTheSourceWithThisServicesConnection() throws Exception {
        try (var c = DriverManager.getConnection(SOURCE.getJdbcUrl(), SOURCE.getUsername(), SOURCE.getPassword());
                var s = c.createStatement()) {
            s.execute("create table if not exists citizens (citizen_id text, full_name text)");
            s.execute("insert into citizens values ('C1', 'Kiran Rao'), ('C2', 'Rao, Meena')");
        }
        UUID batch = UUID.randomUUID();
        ObjectNode request = request(batch, "generic-db-ingest.hpl", "citizen-register");
        request.put("connectionRef", "department");
        request.put("table", "citizens");

        String status = ingestions.ingest(request, null);

        assertThat(status).as(String.valueOf(jdbc.queryForObject("select error_message from ingestion_batch"
                + " where batch_id = ?", String.class, batch))).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from ingestion.raw_record where batch_id = ?", Long.class,
                batch)).isEqualTo(2);
        String failure = jdbc.queryForObject("select coalesce(string_agg(error_message, ''), '') from"
                + " ingestion_batch", String.class);
        assertThat(failure).doesNotContain(SOURCE.getPassword());
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
