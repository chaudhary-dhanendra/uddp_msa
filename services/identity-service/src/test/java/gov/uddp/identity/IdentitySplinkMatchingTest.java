package gov.uddp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The unchanged Python Splink adapter runs against identity-service's own schema: fed only by the seven sample
 * canonical.resident-created events (as the monolith publishes them), it finds the three "Ram Singh" records as the
 * one AUTO_MATCH cluster, exactly as in the monolith, and the run's events go to the outbox. Skipped when Python with
 * Splink is not installed.
 */
@Testcontainers
@SpringBootTest
class IdentitySplinkMatchingTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_identity");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MatchingRuns runs;

    @BeforeAll
    static void requiresSplink() throws Exception {
        Process probe = new ProcessBuilder("python", "-c", "import splink, psycopg, duckdb").redirectErrorStream(true)
                .start();
        probe.getInputStream().readAllBytes();
        assumeTrue(probe.waitFor() == 0, "Python with Splink is not installed");
    }

    @Test
    void sampleResidentsMatchAsInTheMonolithAndTheRunIsPublished() throws Exception {
        for (JsonNode event : sampleEvents()) {
            try (var c = dataSource.getConnection()) {
                Projections.residentCreated(c, event);
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from canonical_resident_current", Integer.class)).isEqualTo(7);

        UUID correlation = UUID.randomUUID();
        UUID run = runs.run(false, correlation);

        assertThat(jdbc.queryForObject("select count(*) from resident_match_pair where run_id = ? and decision ="
                + " 'AUTO_MATCH'", Integer.class, run)).isEqualTo(3);
        List<String> ram = jdbc.queryForList("select c.canonical_payload->>'name' from resident_match_cluster_member m"
                + " join candidate_resident c on c.canonical_resident_id = m.canonical_record_id where m.run_id = ?"
                + " and m.cluster_id in (select cluster_id from resident_match_cluster_member where run_id = ?"
                + " group by cluster_id having count(*) > 1)", String.class, run, run);
        assertThat(ram).containsExactly("Ram Singh", "Ram Singh", "Ram Singh");

        long pairs = jdbc.queryForObject("select count(*) from resident_match_pair where run_id = ?", Long.class, run);
        assertThat(count("uddp.identity.match-decided.v1")).isEqualTo(pairs);
        assertThat(count("uddp.identity.match-run-completed.v1")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payload->>'correlationId' from outbox_event where topic ="
                + " 'uddp.identity.match-run-completed.v1'", String.class)).isEqualTo(correlation.toString());

        // Publishing the same run again adds nothing (name-based event ids).
        try (var c = dataSource.getConnection()) {
            MatchingRuns.publish(c, run, correlation);
        }
        assertThat(count("uddp.identity.match-decided.v1")).isEqualTo(pairs);
    }

    private long count(String topic) {
        return jdbc.queryForObject("select count(*) from outbox_event where topic = ?", Long.class, topic);
    }

    static List<JsonNode> sampleEvents() throws Exception {
        var json = new ObjectMapper();
        Path file = Path.of("src/test/resources/fixtures/resident-created-sample.jsonl");
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    try {
                        return json.readTree(line);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    }
}
