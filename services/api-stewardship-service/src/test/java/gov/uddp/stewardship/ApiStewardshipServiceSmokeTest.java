package gov.uddp.stewardship;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The service starts on a disposable database, migrates only its own schema(s) and reports healthy. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiStewardshipServiceSmokeTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_platform");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestRestTemplate http;

    @Test
    void migratesOnlyItsOwnSchemas() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from stewardship.flyway_schema_history where success and type = 'SQL'", Integer.class);
        assertThat(applied).isEqualTo(1);

        var schemas = jdbc.queryForList(
                "select schema_name from information_schema.schemata"
                        + " where schema_name not in ('public', 'information_schema') and schema_name not like 'pg_%'",
                String.class);
        assertThat(schemas).containsExactlyInAnyOrder("stewardship");
    }

    @Test
    void reportsHealthy() {
        assertThat(http.getForObject("/actuator/health", String.class)).contains("\"UP\"");
    }
}
