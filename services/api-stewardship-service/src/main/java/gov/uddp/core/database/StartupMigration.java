package gov.uddp.core.database;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.security.SecretRedactor;
import gov.uddp.core.setup.PlatformDatabaseSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Migrates the configured database before the application serves requests, so a blank
 * database is fully created by the app. Skipped when no settings are present (the backend starts
 * in setup mode); disabled with {@code uddp.database.migrate-on-startup=false}. When the settings
 * saved from the Setup screen cannot be used (for example the database is down), the backend still
 * starts, in setup mode, and the Setup screen shows why; environment settings still fail fast.
 */
@Component
@ConditionalOnProperty(name = "uddp.database.migrate-on-startup", havingValue = "true", matchIfMissing = true)
public class StartupMigration implements InitializingBean {
    private static final Logger log = LoggerFactory.getLogger(StartupMigration.class);

    private final PostgresConnectionFactory connections;
    private final DatabaseMigrator migrator;
    private final PlatformDatabaseSettings settings;

    public StartupMigration(PostgresConnectionFactory connections, DatabaseMigrator migrator,
                            PlatformDatabaseSettings settings) {
        this.connections = connections;
        this.migrator = migrator;
        this.settings = settings;
    }

    @Override
    public void afterPropertiesSet() {
        if (!connections.isConfigured()) {
            log.warn("No platform database is configured; starting in setup mode (complete the Setup screen)");
            return;
        }
        try {
            var result = migrator.migrate();
            log.info("Database schema at version {} ({} migration(s) applied)", result.targetSchemaVersion,
                    result.migrationsExecuted);
        } catch (RuntimeException e) {
            if (settings.source() != PlatformDatabaseSettings.Source.LOCAL_STORE) {
                throw e;
            }
            String reason = SecretRedactor.redact("The saved platform database could not be migrated: "
                    + e.getMessage(), connections.secrets());
            settings.markUnavailable(reason);
            log.warn("{}; starting in setup mode", reason);
        }
    }
}
