package gov.uddp.statepack;

import static org.assertj.core.api.Assertions.assertThat;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.geography.GeographyReplication;
import gov.uddp.core.geography.LgdImportService;
import gov.uddp.core.geography.LgdRow;
import gov.uddp.core.geography.LgdSnapshotData;
import gov.uddp.messaging.Outbox;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The geography master here follows the edge's LGD snapshots: a snapshot that arrives in several chunks, in any order,
 * is imported once all chunks are here, with the same import the edge ran (a renamed district gets a new version, a
 * new district is added), and the kept chunks are cleared.
 */
@Testcontainers
@SpringBootTest
class GeographyReplicationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_platform");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PostgresConnectionFactory connections;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aChunkedSnapshotIsImportedOnceComplete() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from statepack.lgd_entity", Integer.class))
                .as("seeded from the platform snapshot").isEqualTo(3);
        LocalDate asOf = LocalDate.of(2025, 1, 1);
        var snapshot = new LgdSnapshotData("edge-sync", asOf, List.of(
                new LgdRow(1, "STATE", "10", "Demo State", "ACTIVE", null, null, null),
                new LgdRow(2, "DISTRICT", "100001", "Patna Sadar", "ACTIVE", "STATE", "10", null),
                new LgdRow(3, "DISTRICT", "100002", "Gaya", "ACTIVE", "STATE", "10", null),
                new LgdRow(4, "DISTRICT", "100003", "Nalanda", "ACTIVE", "STATE", "10", null)));
        var envelopes = new java.util.ArrayList<>(GeographyReplication.envelopes(UUID.randomUUID(), snapshot, "edge",
                2));
        assertThat(envelopes).hasSize(2);
        Collections.reverse(envelopes);

        var imports = new LgdImportService(connections, Outbox.json());
        try (var c = dataSource.getConnection()) {
            assertThat(GeographyReplication.apply(c, Outbox.json().valueToTree(envelopes.get(0)), imports))
                    .as("waits for the other chunk").isNull();
            assertThat(GeographyReplication.apply(c, Outbox.json().valueToTree(envelopes.get(1)), imports))
                    .isEqualTo("ACTIVE");
        }
        assertThat(jdbc.queryForObject("select count(*) from statepack.lgd_entity", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select name from statepack.lgd_entity_version where code = '100001'"
                + " and effective_to is null", String.class)).isEqualTo("Patna Sadar");
        assertThat(jdbc.queryForObject("select count(*) from statepack.lgd_snapshot_chunk", Integer.class)).isZero();
    }
}
