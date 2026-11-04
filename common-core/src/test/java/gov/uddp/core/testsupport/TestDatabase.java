package gov.uddp.core.testsupport;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.database.DatabaseMigrator;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One disposable PostgreSQL 16 container per test JVM; every call to {@link #freshMigrated()} creates a
 * new, empty database in it and applies the platform's Flyway migrations. Tests never see the
 * developer's UDDP_POSTGRES_* settings (surefire excludes them).
 */
public final class TestDatabase {
    private static final PostgreSQLContainer<?> CONTAINER =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    // A distinctive password: secret redaction must not mask ordinary words such as "test".
                    .withPassword("pw-" + UUID.randomUUID().toString().replace("-", ""));

    private TestDatabase() {
    }

    /** A new database with the full V1+ schema and no rows. */
    public static PostgresConnectionFactory freshMigrated() {
        var connections = freshEmpty();
        new DatabaseMigrator(connections).migrate();
        return connections;
    }

    /**
     * A new migrated database reached through a new login role whose password is {@code password}, for tests that
     * prove a distinctive secret never reaches stored or returned text.
     */
    public static PostgresConnectionFactory freshMigratedWithPassword(String password) {
        var owner = freshMigrated();
        String role = "uddp_role_" + UUID.randomUUID().toString().replace("-", "");
        try (var c = owner.open(); var statement = c.createStatement()) {
            statement.execute("create role " + role + " login superuser password '" + password.replace("'", "''") + "'");
            String url = c.getMetaData().getURL();
            return PostgresConnectionFactory.forSettings(url, role, password);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create test role", e);
        }
    }

    /** Host, mapped port, user and password of the shared container, for tests that drive the Setup API. */
    public static String[] serverSettings() {
        start();
        return new String[] {CONTAINER.getHost(), Integer.toString(CONTAINER.getMappedPort(5432)),
                CONTAINER.getUsername(), CONTAINER.getPassword()};
    }

    /** A new database with no schema at all. */
    public static PostgresConnectionFactory freshEmpty() {
        start();
        String name = "uddp_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = DriverManager.getConnection(CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
                var statement = admin.createStatement()) {
            statement.execute("create database " + name);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create test database", e);
        }
        String url = "jdbc:postgresql://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(5432) + "/" + name;
        return PostgresConnectionFactory.forSettings(url, CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    private static synchronized void start() {
        if (!CONTAINER.isRunning()) {
            CONTAINER.start();
        }
    }
}
