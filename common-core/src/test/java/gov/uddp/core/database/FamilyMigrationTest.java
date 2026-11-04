package gov.uddp.core.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gov.uddp.core.testsupport.Migrations;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** V11 (Family domain) adds tables only: a populated V10 database upgrades without any existing row changing. */
class FamilyMigrationTest {
    @Test
    void aPopulatedV10DatabaseMigratesToV11WithoutRewritingAnyRow() throws Exception {
        var connections = TestDatabase.freshEmpty();
        var migrator = new DatabaseMigrator(connections);
        assertEquals("10", migrator.migrateTo("10").targetSchemaVersion);
        SampleData.processedSampleWithMatchingRun(connections,
                SampleData.processingWithoutFamilies(connections, SampleData.loader(SampleData.STATE_PACKS)));
        String before = snapshot(connections);

        var result = migrator.migrate();

        // V11 (Family), V12 (stewardship, M13) and V13 (benefits, M15), all of which only add tables.
        assertEquals(Migrations.after(10), result.migrationsExecuted);
        assertEquals(String.valueOf(Migrations.LATEST), result.targetSchemaVersion);
        migrator.validate();
        assertEquals(before, snapshot(connections));
        assertEquals(0, DatabaseMigratorTest.count(connections, "select count(*) from canonical_family_membership_current"));
    }

    @Test
    void relationshipTypesAreAClosedPlatformList() {
        var connections = TestDatabase.freshMigrated();
        SampleData.processedSampleWithMatchingRun(connections,
                SampleData.processingWithoutFamilies(connections, SampleData.loader(SampleData.STATE_PACKS)));
        var error = assertThrows(SQLException.class, () -> DatabaseMigratorTest.execute(connections,
                "insert into canonical_family_observation(canonical_family_observation_id, raw_record_id, batch_id,"
                        + " state_code, source_system, source_entity, state_pack_version, mapping_version,"
                        + " resident_record_key, canonical_resident_id)"
                        + " select gen_random_uuid(), raw_record_id, batch_id, state_code, source_system, source_entity,"
                        + " state_pack_version, '1', 'k', canonical_resident_id from canonical_resident limit 1;"
                        + " insert into canonical_family_membership(canonical_family_membership_id,"
                        + " canonical_family_observation_id, family_record_key, source_family_id, relationship_type)"
                        + " select gen_random_uuid(), canonical_family_observation_id, 'f', 'f', 'COUSIN'"
                        + " from canonical_family_observation"));
        assertEquals("23514", error.getSQLState());
    }

    private static String snapshot(gov.uddp.core.canonical.PostgresConnectionFactory connections) throws Exception {
        String sql = "select (select string_agg(raw_record_id::text || raw_payload, ',' order by raw_record_id) from raw_record)"
                + " || (select string_agg(canonical_resident_id::text || canonical_payload::text, ',' order by"
                + " canonical_resident_id) from canonical_resident)"
                + " || (select string_agg(canonical_resident_id::text || source_record_key, ',' order by"
                + " canonical_resident_id) from canonical_resident_version)"
                + " || (select string_agg(run_id::text || decision, ',' order by run_id, canonical_record_a,"
                + " canonical_record_b) from resident_match_pair)";
        try (var c = connections.open(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }
}
