package gov.uddp.core.setup;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.config.StudioProperties;
import gov.uddp.core.database.DatabaseMigrator;
import gov.uddp.core.security.SecretRedactor;
import gov.uddp.core.security.UserAccountStore;
import gov.uddp.core.statepack.StatePackLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Platform database setup from the UI: test a connection, optionally create the database, save the settings
 * (password encrypted), migrate with Flyway and switch to normal mode without a restart; and reset a demo database.
 */
@Service
public class SetupService {
    private static final Logger log = LoggerFactory.getLogger(SetupService.class);
    private static final String DEMO_SUFFIX = "_demo";
    private static final int LOGIN_TIMEOUT_SECONDS = 5;

    private final PlatformDatabaseSettings settings;
    private final PostgresConnectionFactory connections;
    private final DatabaseMigrator migrator;
    private final StatePackLoader packs;
    private final StudioProperties studio;
    private final List<OnboardingActivity> activities;
    private final UserAccountStore users;
    private final ApplicationEventPublisher events;

    public SetupService(PlatformDatabaseSettings settings, PostgresConnectionFactory connections,
                        DatabaseMigrator migrator, StatePackLoader packs, StudioProperties studio,
                        List<OnboardingActivity> activities, UserAccountStore users,
                        ApplicationEventPublisher events) {
        this.settings = settings;
        this.connections = connections;
        this.migrator = migrator;
        this.packs = packs;
        this.studio = studio;
        this.activities = activities;
        this.users = users;
        this.events = events;
    }

    /**
     * What anyone may see once the platform is configured (the UI needs the mode before sign-in): no host, database,
     * user or file names.
     */
    public SetupModels.Status publicStatus() {
        var full = status();
        if (!"READY".equals(full.mode())) {
            return full;
        }
        return new SetupModels.Status(full.mode(), full.source(), null, null, full.environmentVariablesPresent(), null,
                full.onboardingInProgress(), false, null, full.adminRequired());
    }

    public SetupModels.Status status() {
        boolean ready = connections.isConfigured();
        var stored = settings.storedSettings();
        SetupModels.MaskedSettings shown = null;
        if (settings.source() == PlatformDatabaseSettings.Source.ENVIRONMENT) {
            var variables = connections.processVariables();
            shown = new SetupModels.MaskedSettings(variables.get("UDDP_POSTGRES_HOST"),
                    Integer.parseInt(variables.get("UDDP_POSTGRES_PORT")), variables.get("UDDP_POSTGRES_DB"),
                    variables.get("UDDP_POSTGRES_USER"), true);
        } else if (stored.isPresent()) {
            var s = stored.get();
            shown = new SetupModels.MaskedSettings(s.host(), s.port(), s.database(), s.user(), true);
        }
        String schemaVersion = null;
        if (ready) {
            try {
                var current = migrator.info().current();
                schemaVersion = current == null ? null : current.getVersion().getVersion();
            } catch (RuntimeException e) {
                schemaVersion = null;
            }
        }
        String problem = settings.unavailableReason() != null ? settings.unavailableReason() : settings.storeError();
        return new SetupModels.Status(ready ? "READY" : "SETUP", settings.source().name(), shown,
                settings.store().file().toString(), settings.environmentPartlySet(), schemaVersion,
                ready && onboardingInProgress(), shown != null && shown.database().endsWith(DEMO_SUFFIX),
                redact(problem), ready && noUsers());
    }

    public SetupModels.ConnectionTest testConnection(SetupModels.ConnectionRequest request) {
        DatabaseConnectionSettings candidate;
        try {
            candidate = resolve(request);
        } catch (IllegalArgumentException e) {
            return new SetupModels.ConnectionTest(false, e.getMessage(), null, null);
        }
        try (Connection c = open(candidate, "postgres")) {
            String version = c.getMetaData().getDatabaseProductVersion();
            boolean exists = databaseExists(c, candidate.database());
            String message = exists
                    ? "Connected. Database " + candidate.database() + " exists."
                    : "Connected. Database " + candidate.database() + " does not exist yet; Apply can create it.";
            return new SetupModels.ConnectionTest(true, message, version, exists);
        } catch (SQLException e) {
            // The maintenance database may be closed to this user: try the target database itself.
            try (Connection c = open(candidate, candidate.database())) {
                return new SetupModels.ConnectionTest(true, "Connected. Database " + candidate.database() + " exists.",
                        c.getMetaData().getDatabaseProductVersion(), true);
            } catch (SQLException second) {
                return new SetupModels.ConnectionTest(false, "Cannot connect: " + redact(second.getMessage(), candidate),
                        null, null);
            }
        }
    }

    public synchronized SetupModels.Status apply(SetupModels.ConnectionRequest request) {
        if (settings.source() == PlatformDatabaseSettings.Source.ENVIRONMENT) {
            throw new SetupConflictException("UDDP_POSTGRES_* environment variables are set and take priority;"
                    + " change them instead of the Setup screen settings");
        }
        requireNoOnboarding();
        DatabaseConnectionSettings candidate = resolve(request);
        boolean create = Boolean.TRUE.equals(request.createDatabaseIfMissing());
        try {
            if (create) {
                createDatabaseIfMissing(candidate);
            }
            try (Connection ignored = open(candidate, candidate.database())) {
                // Reachable with these settings.
            }
            var migrated = new DatabaseMigrator(PostgresConnectionFactory.forSettings(candidate.jdbcUrl(),
                    candidate.user(), candidate.password())).migrate();
            settings.save(candidate);
            packs.reload();
            events.publishEvent(new PlatformDatabaseReadyEvent("setup applied"));
            log.info("Platform database settings applied from the Setup screen: {} (schema {}, {} migration(s))",
                    candidate, migrated.targetSchemaVersion, migrated.migrationsExecuted);
            return status();
        } catch (SQLException e) {
            throw new IllegalArgumentException("Cannot connect: " + redact(e.getMessage(), candidate), e);
        } catch (RuntimeException e) {
            if (e instanceof SetupConflictException || e instanceof IllegalArgumentException) {
                throw e;
            }
            throw new IllegalArgumentException("Cannot apply the settings: " + redact(e.getMessage(), candidate), e);
        }
    }

    /**
     * Drops and recreates every UDDP object in the configured database, migrates it again and removes everything the
     * Studio generated (packs, drafts, uploads, named connections). Allowed only for a database whose name ends with
     * {@code _demo}, when the caller typed that exact name.
     */
    public synchronized SetupModels.Status resetDemo(String confirmDatabaseName) {
        if (!connections.isConfigured()) {
            throw new SetupConflictException("Configure the platform database first");
        }
        String database = connections.processVariables().get("UDDP_POSTGRES_DB");
        if (!database.toLowerCase(Locale.ROOT).endsWith(DEMO_SUFFIX)) {
            throw new SetupConflictException("Reset is allowed only for a database whose name ends with " + DEMO_SUFFIX
                    + "; the configured database is " + database);
        }
        if (!database.equals(confirmDatabaseName)) {
            throw new IllegalArgumentException("Type the database name " + database + " exactly to confirm the reset");
        }
        requireNoOnboarding();
        try (Connection c = connections.open(); var statement = c.createStatement()) {
            statement.execute("drop schema if exists public cascade");
            statement.execute("create schema public");
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot reset the demo database: " + redact(e.getMessage()), e);
        }
        migrator.migrate();
        deleteChildren(studio.packs());
        deleteChildren(studio.workspace());
        settings.store().clearConnections();
        packs.reload();
        log.info("Demo database {} reset and Studio-generated packs, drafts and uploads removed", database);
        events.publishEvent(new PlatformDatabaseReadyEvent("demo reset"));
        return status();
    }

    private boolean noUsers() {
        try {
            return users.count() == 0;
        } catch (RuntimeException e) {
            // No schema yet: there are no users either.
            return true;
        }
    }

    public boolean onboardingInProgress() {
        if (activities.stream().anyMatch(OnboardingActivity::inProgress)) {
            return true;
        }
        if (!connections.isConfigured()) {
            return false;
        }
        try (Connection c = connections.open();
                var s = c.prepareStatement("select count(*) from onboarding_stage_execution where status='RUNNING'");
                var r = s.executeQuery()) {
            return r.next() && r.getInt(1) > 0;
        } catch (SQLException e) {
            // No schema yet (or unreachable): nothing can be running.
            return false;
        }
    }

    private void requireNoOnboarding() {
        if (onboardingInProgress()) {
            throw new SetupConflictException("An onboarding run is in progress; wait for it to finish first");
        }
    }

    /** The request's settings; a blank password keeps the saved one when the same server and user are kept. */
    private DatabaseConnectionSettings resolve(SetupModels.ConnectionRequest request) {
        if (request.port() == null) {
            throw new IllegalArgumentException("Port is required");
        }
        String password = request.password();
        if (password == null || password.isEmpty()) {
            password = settings.storedSettings()
                    .filter(s -> s.host().equals(request.host()) && s.port() == request.port()
                            && s.user().equals(request.user()))
                    .map(DatabaseConnectionSettings::password)
                    .orElse(null);
        }
        return new DatabaseConnectionSettings(trim(request.host()), request.port(), trim(request.database()),
                trim(request.user()), password).validated();
    }

    private void createDatabaseIfMissing(DatabaseConnectionSettings candidate) throws SQLException {
        try (Connection c = open(candidate, "postgres")) {
            if (!databaseExists(c, candidate.database())) {
                try (var statement = c.createStatement()) {
                    // The name is a validated identifier (DatabaseConnectionSettings.validated).
                    statement.execute("create database \"" + candidate.database() + "\"");
                }
                log.info("Created database {}", candidate.database());
            }
        }
    }

    private static boolean databaseExists(Connection c, String database) throws SQLException {
        try (var s = c.prepareStatement("select 1 from pg_database where datname=?")) {
            s.setString(1, database);
            try (var r = s.executeQuery()) {
                return r.next();
            }
        }
    }

    private static Connection open(DatabaseConnectionSettings settings, String database) throws SQLException {
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        return DriverManager.getConnection(settings.jdbcUrl(database), settings.user(), settings.password());
    }

    private static void deleteChildren(Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.toList()) {
                try (Stream<Path> tree = Files.walk(child)) {
                    for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot clear " + directory, e);
        }
    }

    private String redact(String text) {
        return SecretRedactor.redact(text, connections.secrets());
    }

    private String redact(String text, DatabaseConnectionSettings candidate) {
        return SecretRedactor.redact(redact(text), List.of(candidate.password()));
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
