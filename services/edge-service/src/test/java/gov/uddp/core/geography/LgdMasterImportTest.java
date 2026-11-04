package gov.uddp.core.geography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Track F1: the LGD master is bootstrapped from a file snapshot through the adapter; re-importing it changes nothing;
 * renames, reparenting and inactivation/reactivation become versions and change-log rows; a missing row is not a
 * deletion; and a snapshot that fails validation leaves the active master untouched.
 */
class LgdMasterImportTest {
    static final Path FIXTURE = Path.of("../../services/edge-service/src/test/fixtures/lgd/lgd-snapshot-v1.csv");

    private final PostgresConnectionFactory db = TestDatabase.freshMigrated();
    private final LgdImportService imports = new LgdImportService(db, new ObjectMapper());

    @Test
    void bootstrapThenIdempotentReimport() {
        var client = new FileLgdSourceClient(FIXTURE.toString());
        var first = imports.importSnapshot(client.fetch().orElseThrow(), "tester");
        assertEquals("ACTIVE", first.status(), first.errors().toString());
        assertEquals(13, first.changes().get("ADDED"));
        assertEquals(13, count("select count(*) from lgd_entity"));
        assertEquals(12, count("select count(*) from lgd_relationship where valid_to is null"));
        assertEquals("Ward 1, North Town", text("select name from lgd_entity_version where code='70011'"));

        var again = imports.importSnapshot(client.fetch().orElseThrow(), "tester");
        assertEquals("UNCHANGED", again.status());
        assertEquals(0, again.changeCount());
        assertEquals(13, count("select count(*) from lgd_change"));
        assertEquals(13, count("select count(*) from lgd_entity_version"));
        assertEquals(first.snapshotId().toString(), text("select active_snapshot_id::text from lgd_master_state"));
    }

    @Test
    void renameReparentInactivateAndReactivateAreVersioned() {
        var base = FileLgdSourceClient.read(FIXTURE);
        imports.importSnapshot(base, "tester");

        // Only two rows delivered: everything missing stays as it was.
        var changed = imports.importSnapshot(new LgdSnapshotData("delta", LocalDate.parse("2024-06-01"), List.of(
                new LgdRow(1, "VILLAGE", "500002", "Lake View", "ACTIVE", "SUB_DISTRICT", "9021", null),
                new LgdRow(2, "BLOCK", "90201", "Hillside Block", "INACTIVE", "DISTRICT", "902", null))), "tester");
        assertEquals("ACTIVE", changed.status(), changed.errors().toString());
        assertEquals(Map.of("RENAMED", 1, "REPARENTED", 1, "INACTIVATED", 1), changed.changes());
        assertEquals(13, count("select count(*) from lgd_entity_version where effective_to is null"),
                "a missing row is not a deletion");
        assertEquals("Lakeview", text("select name from lgd_entity_version where code='500002'"
                + " and effective_from <= '2024-03-01' and (effective_to is null or effective_to > '2024-03-01')"));
        assertEquals("Lake View", text("select name from lgd_entity_version where code='500002' and effective_to is null"));
        assertEquals("9021", text("select parent_code from lgd_relationship where child_code='500002' and valid_to is null"));
        assertEquals("9011", text("select parent_code from lgd_relationship where child_code='500002'"
                + " and valid_to='2024-06-01'"));

        var reactivated = imports.importSnapshot(new LgdSnapshotData("delta2", LocalDate.parse("2024-09-01"), List.of(
                new LgdRow(1, "BLOCK", "90201", "Hillside Block", null, "DISTRICT", "902", null))), "tester");
        assertEquals(Map.of("REACTIVATED", 1), reactivated.changes());
        assertEquals("ACTIVE", text("select status from lgd_entity_version where code='90201' and effective_to is null"));
        assertEquals(3, count("select count(*) from lgd_entity_version where code='90201'"));
    }

    @Test
    void aFailedSnapshotLeavesTheActiveMasterUntouched() {
        var base = imports.importSnapshot(FileLgdSourceClient.read(FIXTURE), "tester");
        long versions = count("select count(*) from lgd_entity_version");
        List<LgdRow> rows = new ArrayList<>(FileLgdSourceClient.read(FIXTURE).rows());
        rows.set(1, new LgdRow(rows.get(1).lineNo(), "DISTRICT", "901", "North District Renamed", "ACTIVE", "STATE", "90", null));
        rows.add(new LgdRow(99, "VILLAGE", "500009", "Orphan", "ACTIVE", "SUB_DISTRICT", "0000", null));
        rows.add(new LgdRow(100, "DISTRICT", "903", "Upside Down", "ACTIVE", "VILLAGE", "500001", null));

        var failed = imports.importSnapshot(new LgdSnapshotData("bad", LocalDate.parse("2024-07-01"), rows), "tester");
        assertEquals("FAILED", failed.status());
        assertTrue(failed.errors().stream().anyMatch(e -> e.contains("parent SUB_DISTRICT 0000 is unknown")));
        assertTrue(failed.errors().stream().anyMatch(e -> e.contains("VILLAGE cannot be the parent of DISTRICT")));
        assertEquals(versions, count("select count(*) from lgd_entity_version"));
        assertEquals("North District", text("select name from lgd_entity_version where code='901' and effective_to is null"));
        assertEquals(base.snapshotId().toString(), text("select active_snapshot_id::text from lgd_master_state"));
        assertEquals("FAILED", text("select status from lgd_snapshot where snapshot_id='" + failed.snapshotId() + "'"));
        assertFalse(text("select errors::text from lgd_snapshot where snapshot_id='" + failed.snapshotId() + "'")
                .equals("[]"));
        assertEquals(0, count("select count(*) from lgd_staging_row"));
    }

    private long count(String sql) {
        return Long.parseLong(text(sql));
    }

    private String text(String sql) {
        try (var c = db.open(); var r = c.createStatement().executeQuery(sql)) {
            return r.next() ? r.getString(1) : null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
