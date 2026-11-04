package gov.uddp.core.database;

import gov.uddp.core.canonical.PostgresConnectionFactory;

/**
 * Command-line Flyway entry point using the UDDP_POSTGRES_* settings:
 * {@code migrate}, {@code info}, {@code validate}, {@code baseline <version> <description>}, or
 * {@code check-manual-v7} (exit 0 only when a database has the V7 schema applied by hand and no Flyway history).
 * Prints schema versions only, never connection settings.
 */
public final class DatabaseMigrationCommand {
    private DatabaseMigrationCommand() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
        }
        var migrator = new DatabaseMigrator(new PostgresConnectionFactory());
        switch (args[0]) {
            case "migrate" -> {
                var result = migrator.migrate();
                System.out.println("Applied " + result.migrationsExecuted + " migration(s); schema version "
                        + result.targetSchemaVersion);
            }
            case "validate" -> {
                migrator.validate();
                System.out.println("Flyway validate: OK");
            }
            case "info" -> printInfo(migrator);
            case "check-manual-v7" -> checkManualV7();
            case "baseline" -> {
                if (args.length < 3) {
                    usage();
                }
                var result = migrator.baseline(args[1], args[2]);
                System.out.println("Baselined at version " + args[1] + ": " + result.successfullyBaselined);
            }
            default -> usage();
        }
    }

    private static void checkManualV7() {
        String sql = "select to_regclass('public.flyway_schema_history') is null,"
                + " to_regclass('public.canonical_validation_warning') is not null,"
                + " exists(select 1 from information_schema.columns"
                + " where table_schema='public' and table_name='matching_run' and column_name='possible_match_threshold')";
        try (var connection = new PostgresConnectionFactory().open();
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            if (!result.getBoolean(1)) {
                fail("flyway_schema_history already exists");
            }
            if (!result.getBoolean(2) || !result.getBoolean(3)) {
                fail("the V7 schema (canonical_validation_warning, matching_run.possible_match_threshold) is not present");
            }
            System.out.println("V7 schema present and no Flyway history: safe to baseline at 7");
        } catch (java.sql.SQLException e) {
            fail("cannot inspect the database: " + e.getMessage());
        }
    }

    private static void fail(String message) {
        System.out.println(message);
        System.exit(1);
    }

    private static void printInfo(DatabaseMigrator migrator) {
        for (var migration : migrator.info().all()) {
            System.out.println(migration.getVersion() + " | " + migration.getDescription() + " | " + migration.getState());
        }
    }

    private static void usage() {
        System.err.println("Usage: DatabaseMigrationCommand migrate | info | validate | check-manual-v7 | baseline <version> <description>");
        System.exit(2);
    }
}
