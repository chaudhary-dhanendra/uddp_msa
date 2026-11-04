package gov.uddp.core.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gov.uddp.core.testsupport.Migrations;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** V12 (stewardship) adds tables only; decisions need a reviewer and a reason, and the latest decision per pair wins. */
class StewardshipMigrationTest {
    @Test
    void aPopulatedV11DatabaseMigratesToV12AndTheLatestDecisionPerPairIsEffective() throws Exception {
        var connections = TestDatabase.freshEmpty();
        var migrator = new DatabaseMigrator(connections);
        migrator.migrateTo("11");
        SampleData.processedSampleWithMatchingRun(connections,
                SampleData.processingWithoutBenefits(connections, SampleData.loader(SampleData.STATE_PACKS)));
        long canonical = DatabaseMigratorTest.count(connections, "select count(*) from canonical_resident");

        // V12, then V13 (benefits, M15), which only adds tables.
        assertEquals(Migrations.after(11), migrator.migrate().migrationsExecuted);
        migrator.validate();
        assertEquals(canonical, DatabaseMigratorTest.count(connections, "select count(*) from canonical_resident"));

        DatabaseMigratorTest.execute(connections, "insert into stewardship_decision(decision_id, action, reviewer, reason)"
                + " values ('00000000-0000-0000-0000-000000000001', 'MERGE', 'r', 'same person'),"
                + " ('00000000-0000-0000-0000-000000000002', 'SPLIT', 'r', 'different people');"
                + " insert into stewardship_link_constraint values"
                + " ('00000000-0000-0000-0000-000000000001', 'a', 'b', 'MUST_LINK'),"
                + " ('00000000-0000-0000-0000-000000000002', 'a', 'b', 'CANNOT_LINK')");
        assertEquals(1, DatabaseMigratorTest.count(connections, "select count(*) from stewardship_link_constraint_effective"
                + " where constraint_type = 'CANNOT_LINK'"));
        assertEquals(0, DatabaseMigratorTest.count(connections, "select count(*) from stewardship_link_constraint_effective"
                + " where constraint_type = 'MUST_LINK'"));
    }

    @Test
    void aDecisionNeedsAReviewerAndAReasonAndAcceptRejectNeedATask() {
        var connections = TestDatabase.freshMigrated();
        for (String values : new String[] {"'MERGE', ' ', 'why'", "'MERGE', 'r', ''", "'ACCEPT_MATCH', 'r', 'why'"}) {
            var error = assertThrows(SQLException.class, () -> DatabaseMigratorTest.execute(connections,
                    "insert into stewardship_decision(decision_id, action, reviewer, reason)"
                            + " values (gen_random_uuid(), " + values + ")"));
            assertEquals("23514", error.getSQLState(), values);
        }
    }
}
