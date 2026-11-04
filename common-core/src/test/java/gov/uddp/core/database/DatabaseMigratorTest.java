package gov.uddp.core.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.Migrations;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.Arrays;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;

class DatabaseMigratorTest {
    static final String[] TABLES = {
        "ingestion_batch", "raw_record", "canonical_resident", "canonical_processing_error",
        "canonical_validation_warning", "matching_run", "resident_match_pair", "resident_match_cluster_member",
        "golden_resident", "golden_resident_member", "golden_resident_field_provenance",
        "reference_dataset", "reference_value", "dq_batch_evaluation", "dq_result",
        "state_pack_lifecycle", "onboarding_run", "onboarding_stage_execution",
        "canonical_resident_version", "canonical_resident_current", "golden_build", "golden_resident_version",
        "golden_resident_version_provenance", "golden_resident_membership", "golden_resident_event",
        "canonical_family_observation", "canonical_family_membership", "canonical_family_processing_error",
        "canonical_family_membership_current", "golden_family", "golden_family_version", "golden_family_membership",
        "golden_family_event", "stewardship_review_task", "stewardship_decision", "stewardship_link_constraint",
        "stewardship_link_constraint_effective", "benefit", "canonical_benefit_observation",
        "canonical_benefit_disbursement", "canonical_benefit_processing_error", "canonical_benefit_current",
        "benefit_state", "benefit_holding_current", "studio_draft", "state_pack_document", "app_user", "app_role",
        "app_permission", "app_user_role", "app_role_permission", "user_state_scope", "app_audit_event",
        "user_department_scope"
    };

    @Test
    void createsTheWholeSchemaOnABlankDatabase() throws Exception {
        var connections = TestDatabase.freshEmpty();
        var migrator = new DatabaseMigrator(connections);

        var result = migrator.migrate();

        assertEquals(Migrations.COUNT, result.migrationsExecuted);
        assertEquals(String.valueOf(Migrations.LATEST), result.targetSchemaVersion);
        for (String table : TABLES) {
            assertTrue(exists(connections, table), table + " should exist");
        }
        assertEquals(0, migrator.migrate().migrationsExecuted);
        migrator.validate();
    }

    @Test
    void adoptsAManuallyMigratedDatabaseWithABaselineThatRunsNoDdl() throws Exception {
        var connections = TestDatabase.freshEmpty();
        var migrator = new DatabaseMigrator(connections);

        // Build exactly a V7 schema, then remove Flyway history to model a database whose V1-V7 DDL was applied
        // manually before Flyway was introduced. This is deliberately version-targeted instead of migrating to the
        // latest schema and trying to reverse every later migration; the latter becomes stale whenever a new
        // migration is added.
        assertEquals("7", migrator.migrateTo("7").targetSchemaVersion);
        execute(connections, "insert into matching_run(run_id,model_version,threshold,status)"
                + " values(gen_random_uuid(),'kept',0.95,'COMPLETED')");
        execute(connections, "drop table flyway_schema_history");

        assertThrows(FlywayException.class, migrator::migrate,
                "a non-empty schema without history must not be migrated or silently baselined");

        assertTrue(migrator.baseline("7", "V2-V7 applied manually before M8").successfullyBaselined);
        assertEquals(Migrations.after(7), migrator.migrate().migrationsExecuted);
        migrator.validate();
        var states = Arrays.stream(migrator.info().all()).map(info -> info.getState()).toList();
        assertTrue(states.contains(MigrationState.BASELINE));
        assertTrue(states.stream().noneMatch(state -> state == MigrationState.PENDING));
        assertEquals(1, count(connections, "select count(*) from matching_run where model_version='kept'"));
    }

    static boolean exists(PostgresConnectionFactory connections, String table) throws Exception {
        try (var c = connections.open(); var s = c.prepareStatement("select to_regclass(?) is not null")) {
            s.setString(1, "public." + table);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getBoolean(1);
            }
        }
    }

    static void execute(PostgresConnectionFactory connections, String sql) throws Exception {
        try (var c = connections.open(); var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static long count(PostgresConnectionFactory connections, String sql) throws Exception {
        try (var c = connections.open(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }
}
