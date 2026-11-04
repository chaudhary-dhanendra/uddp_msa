package gov.uddp.core.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.testsupport.Redelivery;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M11 canonical versions per source record (ADR-0003) on the sample packs: an unchanged re-delivery adds nothing, a
 * corrected one becomes version 2 and supersedes version 1, a State Pack version change re-maps into new versions
 * rather than duplicates, and a delivery that repeats a source record key is rejected. Canonical rows are never
 * updated.
 */
class CanonicalVersioningTest {
    @TempDir
    Path work;

    private PostgresConnectionFactory connections;
    private Sql sql;
    private CanonicalResidentProcessingService processing;
    private gov.uddp.core.statepack.StatePackLoader packs;

    @BeforeEach
    void sample() {
        connections = TestDatabase.freshMigrated();
        sql = new Sql(connections);
        packs = SampleData.loader(SampleData.STATE_PACKS);
        processing = SampleData.processing(connections, packs);
        SampleData.landAll(connections, SampleData.STATE_PACKS).values().forEach(ids -> ids.forEach(processing::processBatch));
        assertEquals(7, sql.count("select count(*) from canonical_resident"));
        assertEquals(7, sql.count("select count(*) from canonical_resident_current"));
        assertEquals(7, sql.count("select count(*) from canonical_resident_version where version_number=1"));
    }

    @Test
    void everySourceRecordHasItsOwnKeyEvenWhenTheRecordIdIsAFamilyNumber() {
        // state-1's ration number R1001 is shared by Ram and Sita; recordKeyFields makes them two source records.
        assertEquals(List.of("ST1|pds|pds|R1001|Ram Singh", "ST1|pds|pds|R1001|Sita Singh", "ST1|pds|pds|R1002|Ramesh Kumar"),
                sql.column("select source_record_key from canonical_resident_version where source_record_key like 'ST1|%'"
                        + " order by source_record_key", String.class));
        assertEquals("ST2|pension|pension|F5001", sql.text("select source_record_key from canonical_resident_current"
                + " where state_code='ST2' and canonical_payload->>'name'='Ram Singh'"));
    }

    @Test
    void anUnchangedRedeliveryCreatesNoCanonicalVersion() {
        String before = snapshot();
        UUID batch = Redelivery.land(connections, packs.find("state-1").orElseThrow(), "pds", work, lines -> lines);

        var summary = processing.processBatch(batch);

        assertEquals(new CanonicalProcessingSummary(batch, 3, 0, 3, 0), summary);
        assertEquals(10, sql.count("select count(*) from raw_record"), "Raw keeps the re-delivery (append-only)");
        assertEquals(before, snapshot());
        assertEquals(7, sql.count("select count(*) from canonical_resident_version"));
    }

    @Test
    void aCorrectedRedeliveryBecomesTheNextVersionAndOnlyItParticipates() {
        String before = snapshot();
        UUID old = sql.uuid("select canonical_resident_id from canonical_resident_current"
                + " where state_code='ST2' and canonical_payload->>'name'='Ram Singh'");
        UUID batch = Redelivery.land(connections, packs.find("state-2").orElseThrow(), "pension", work,
                Redelivery.replaceIn("F5001", "9876543210", "9999999999"));

        var summary = processing.processBatch(batch);

        assertEquals(new CanonicalProcessingSummary(batch, 2, 1, 1, 0), summary, "Ram corrected, Anita unchanged");
        assertRowsUnchanged(before);
        assertEquals(8, sql.count("select count(*) from canonical_resident"));
        assertEquals(7, sql.count("select count(*) from canonical_resident_current"));
        UUID current = sql.uuid("select canonical_resident_id from canonical_resident_current"
                + " where state_code='ST2' and canonical_payload->>'name'='Ram Singh'");
        assertEquals("9999999999", sql.text("select canonical_payload->>'mobile' from canonical_resident"
                + " where canonical_resident_id=?", current));
        assertEquals(2, sql.count("select version_number from canonical_resident_version where canonical_resident_id=?",
                current));
        assertEquals(old, sql.uuid("select supersedes_canonical_id from canonical_resident_version"
                + " where canonical_resident_id=?", current));
        assertEquals(0, sql.count("select count(*) from canonical_resident_current where canonical_resident_id=?", old));
    }

    @Test
    void aStatePackVersionChangeRemapsIntoNewVersionsNotDuplicates() throws Exception {
        String before = snapshot();
        Path packsCopy = Redelivery.copyPacks(Files.createDirectories(work.resolve("packs")), SampleData.STATE_PACKS);
        Path manifest = packsCopy.resolve("state-3/state.yaml");
        Files.writeString(manifest, Files.readString(manifest).replace("version: 1.0.0", "version: 1.1.0"));
        var v11 = SampleData.loader(packsCopy);
        var remapping = SampleData.processing(connections, v11);
        UUID state3Batch = sql.uuid("select batch_id from ingestion_batch where state_code='ST3'");

        var summary = remapping.processBatch(state3Batch);

        assertEquals(new CanonicalProcessingSummary(state3Batch, 2, 2, 0, 0), summary);
        assertRowsUnchanged(before);
        assertEquals(9, sql.count("select count(*) from canonical_resident"));
        assertEquals(7, sql.count("select count(*) from canonical_resident_current"), "re-mapping never duplicates");
        assertEquals(List.of("1.1.0", "1.1.0"), sql.column("select state_pack_version from canonical_resident_current"
                + " where state_code='ST3'", String.class));
        assertEquals(List.of(2L, 2L), sql.column("select cast(version_number as bigint) from canonical_resident_current"
                + " where state_code='ST3'", Long.class));
        assertEquals(new CanonicalProcessingSummary(state3Batch, 2, 0, 2, 0), remapping.processBatch(state3Batch),
                "re-processing the same batch under the same version is idempotent");
    }

    @Test
    void aDeliveryThatRepeatsASourceRecordKeyRejectsEveryRowWithThatKey() {
        String before = snapshot();
        UUID batch = Redelivery.land(connections, packs.find("state-2").orElseThrow(), "pension", work, lines -> {
            var out = new ArrayList<>(lines);
            // The pension source carries benefit columns since M15; this row names no benefit.
            out.add("Mohan Lal,19700101,1,Patna,F5001,9800000000,,,,,,,");
            return out;
        });

        var summary = processing.processBatch(batch);

        assertEquals(new CanonicalProcessingSummary(batch, 3, 0, 1, 2), summary, "Anita unchanged; both F5001 rows rejected");
        assertEquals(2, sql.count("select count(*) from canonical_processing_error where batch_id=?"
                + " and error_message like 'Duplicate source record key in batch: ST2|pension|pension|F5001'", batch));
        assertEquals(0, sql.count("select count(*) from canonical_resident where canonical_payload->>'name'='Mohan Lal'"));
        assertEquals(before, snapshot());
        assertEquals(7, sql.count("select count(*) from canonical_resident_version"));
    }

    /** Every canonical row as it is stored; canonical rows must never be updated. */
    private String snapshot() {
        return String.join("\n", sql.column("select canonical_resident_id::text || canonical_payload::text"
                + " || state_pack_version || mapped_at::text from canonical_resident order by canonical_resident_id", String.class));
    }

    private void assertRowsUnchanged(String before) {
        for (String row : before.split("\n")) {
            assertTrue(snapshot().contains(row), "canonical row changed or disappeared: " + row);
        }
    }
}
