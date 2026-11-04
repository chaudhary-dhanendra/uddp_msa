package gov.uddp.core.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gov.uddp.core.testsupport.Migrations;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** V13 (Scheme and Benefit domains, M15) adds tables only; its constraints keep observations well formed. */
class BenefitMigrationTest {
    @Test
    void aPopulatedV12DatabaseMigratesToV13WithoutRewritingAnyRow() throws Exception {
        var connections = TestDatabase.freshEmpty();
        var migrator = new DatabaseMigrator(connections);
        assertEquals("12", migrator.migrateTo("12").targetSchemaVersion);
        SampleData.processedSampleWithMatchingRun(connections,
                SampleData.processingWithoutBenefits(connections, SampleData.loader(SampleData.STATE_PACKS)));
        String sql = "select string_agg(x, ';' order by x) from (select canonical_resident_id::text"
                + "||canonical_payload::text as x from canonical_resident union all select family_record_key"
                + "||source_family_id from canonical_family_membership) rows";
        String before = text(connections, sql);

        var result = migrator.migrate();

        // V13 (benefits) and every later migration, none of which rewrites existing rows.
        assertEquals(Migrations.after(12), result.migrationsExecuted);
        assertEquals(String.valueOf(Migrations.LATEST), result.targetSchemaVersion);
        migrator.validate();
        assertEquals(before, text(connections, sql));
        assertEquals(0, DatabaseMigratorTest.count(connections, "select count(*) from canonical_benefit_current"));
    }

    @Test
    void observationsNeedAClosedStatusAndAHouseholdForFamilyLevelBenefits() throws Exception {
        var connections = TestDatabase.freshMigrated();
        SampleData.processedSampleWithMatchingRun(connections,
                SampleData.processingWithoutBenefits(connections, SampleData.loader(SampleData.STATE_PACKS)));
        DatabaseMigratorTest.execute(connections, "insert into benefit values ('00000000-0000-0000-0000-00000000000b',"
                + " 'k', 's', 'src', 'src', 'SCHEME', 'B1')");
        String insert = "insert into canonical_benefit_observation(canonical_benefit_observation_id, raw_record_id, batch_id,"
                + " state_code, source_system, source_entity, state_pack_version, mapping_version,"
                + " scheme_catalogue_version, resident_record_key, canonical_resident_id, benefit_id, benefit_record_key,"
                + " scheme_id, source_benefit_id, beneficiary_level, benefit_status)"
                + " select gen_random_uuid(), raw_record_id, batch_id, state_code, source_system, source_system,"
                + " state_pack_version, '1', '1', 'key', canonical_resident_id, '00000000-0000-0000-0000-00000000000b',"
                + " 'k', 'SCHEME', 'B1', %s, %s from canonical_resident limit 1";
        for (String values : new String[] {"'RESIDENT', 'PAID'", "'FAMILY', 'ACTIVE'", "'RESIDENT', null"}) {
            var error = assertThrows(SQLException.class, () -> DatabaseMigratorTest.execute(connections,
                    insert.formatted(values.split(", ")[0], values.split(", ")[1])));
            assertEquals("23514", error.getSQLState(), values);
        }
    }

    private static String text(gov.uddp.core.canonical.PostgresConnectionFactory connections, String sql) throws Exception {
        try (var c = connections.open(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }
}
