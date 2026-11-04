package gov.uddp.core.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * tests/fixtures/data/fx-alpha/registry.csv under the fx-alpha State Pack: four bad rows are recorded as
 * processing errors and skipped; a row breaking only the warning-severity mobile rule is kept with the
 * warning stored. Everything is decided by fx-alpha's mapping, crosswalk and validation YAML.
 */
class BadDataFixtureTest {
    @Test
    void errorsAreRecordedAndSkippedWhileWarningsAreKeptAndStored() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var loader = SampleData.loader(SampleData.FIXTURE_PACKS);
        var service = SampleData.processing(connections, loader);
        UUID alpha = SampleData.landAll(connections, SampleData.FIXTURE_PACKS).get("fx-alpha").getFirst();

        var summary = service.processBatch(alpha);

        assertEquals(6, summary.recordsRead());
        assertEquals(2, summary.recordsMapped());
        assertEquals(4, summary.recordsFailed());
        assertEquals(List.of("Leela Nair", "Ram Singh"), strings(connections,
                "select canonical_payload->>'name' from canonical_resident where batch_id=? order by 1", alpha));

        var errors = strings(connections, "select e.error_message from canonical_processing_error e"
                + " join raw_record r on r.raw_record_id=e.raw_record_id where e.batch_id=? order by r.raw_payload", alpha);
        assertEquals(4, errors.size());
        // Blank name: the mapping engine rejects it before validation runs.
        assertContainsOne(errors, "Resident name is required");
        // Unknown gender code: no crosswalk value.
        assertContainsOne(errors, "No crosswalk value for gender: Q");
        // Invalid date for the configured DD-MM-YYYY conversion.
        assertContainsOne(errors, "Invalid date for configured format DD-MM-YYYY: 45-13-1985");
        // X crosswalks to OTHER, which fx-alpha's error-severity allowedValues rule rejects.
        assertContainsOne(errors, "gender allowedValues (error)");
        assertEquals(4, count(connections, "select count(*) from canonical_processing_error e"
                + " where e.batch_id=? and not exists (select 1 from canonical_resident c where c.raw_record_id=e.raw_record_id)",
                alpha));

        var warnings = strings(connections, "select c.canonical_payload->>'name' || '|' || w.field_path || '|' || w.rule"
                + " from canonical_validation_warning w join canonical_resident c on c.canonical_resident_id=w.canonical_resident_id"
                + " where w.batch_id=?", alpha);
        assertEquals(List.of("Leela Nair|mobile|required"), warnings);

        var replay = service.processBatch(alpha);
        assertEquals(2, replay.recordsSkipped());
        assertEquals(1, count(connections, "select count(*) from canonical_validation_warning where batch_id=?", alpha),
                "replaying the batch must not duplicate warnings");
    }

    static void assertContainsOne(List<String> messages, String fragment) {
        assertEquals(1, messages.stream().filter(message -> message.contains(fragment)).count(),
                () -> "expected exactly one error containing '" + fragment + "' in " + messages);
    }

    static List<String> strings(PostgresConnectionFactory connections, String sql, UUID id) throws Exception {
        var values = new ArrayList<String>();
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    values.add(r.getString(1));
                }
            }
        }
        return values;
    }

    static long count(PostgresConnectionFactory connections, String sql, UUID id) throws Exception {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            try (var r = s.executeQuery()) {
                r.next();
                assertTrue(r.getLong(1) >= 0);
                return r.getLong(1);
            }
        }
    }
}
