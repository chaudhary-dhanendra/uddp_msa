package gov.uddp.core.database;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.output.BaselineResult;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.stereotype.Component;

/**
 * Applies the platform schema (classpath:db/migration, V1 onwards) with Flyway.
 *
 * <p>{@code baselineOnMigrate} stays off: a database that already holds tables but has no
 * {@code flyway_schema_history} fails loudly instead of being silently baselined. Such a
 * database is adopted once with {@link #baseline(String, String)}.
 */
@Component
public class DatabaseMigrator {
    public static final String LOCATION = "classpath:db/migration";

    private final PostgresConnectionFactory connections;

    public DatabaseMigrator(PostgresConnectionFactory connections) {
        this.connections = connections;
    }

    public MigrateResult migrate() {
        return flyway().migrate();
    }

    /** Applies migrations up to and including {@code targetVersion} only (used to prove upgrades from older schemas). */
    public MigrateResult migrateTo(String targetVersion) {
        return Flyway.configure()
                .dataSource(connections.dataSource())
                .locations(LOCATION)
                .baselineOnMigrate(false)
                .target(targetVersion)
                .load()
                .migrate();
    }

    public void validate() {
        flyway().validate();
    }

    public MigrationInfoService info() {
        return flyway().info();
    }

    /** Records that migrations up to {@code version} were applied outside Flyway; runs no DDL. */
    public BaselineResult baseline(String version, String description) {
        return Flyway.configure()
                .dataSource(connections.dataSource())
                .locations(LOCATION)
                .baselineVersion(version)
                .baselineDescription(description)
                .load()
                .baseline();
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(connections.dataSource())
                .locations(LOCATION)
                .baselineOnMigrate(false)
                .load();
    }
}
