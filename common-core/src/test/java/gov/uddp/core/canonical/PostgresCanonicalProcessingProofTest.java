package gov.uddp.core.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Raw -> generic mapper -> canonical JSONB persistence and replay idempotency, on a disposable PostgreSQL 16. */
class PostgresCanonicalProcessingProofTest {
    @Test
    void processesSampleBatchesWithLineageExtensionsAndIdempotency() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var batches = SampleData.landAll(connections, SampleData.STATE_PACKS);
        var service = SampleData.processing(connections, SampleData.loader(SampleData.STATE_PACKS));
        UUID state1 = batches.get("state-1").getFirst();
        UUID state2 = batches.get("state-2").getFirst();
        UUID state3 = batches.get("state-3").getFirst();

        assertEquals(3, service.processBatch(state1).recordsRead());
        assertEquals(2, service.processBatch(state2).recordsRead());
        assertEquals(2, service.processBatch(state3).recordsRead());
        assertEquals(7, count(connections, "select count(*) from canonical_resident"));
        assertEquals(1, count(connections, "select count(*) from canonical_resident"
                + " where canonical_payload->>'name' = 'Sita Singh'"
                + " and canonical_payload->>'dateOfBirth' = '1984-07-14'"
                + " and canonical_payload->>'gender' = 'FEMALE'"
                + " and canonical_payload#>>'{address,districtLGD}' = '100001'"
                + " and canonical_payload#>>'{extensions,familyReference}' = 'R1001'"));
        assertEquals(1, count(connections, "select count(*) from canonical_resident"
                + " where canonical_payload->>'name' = 'Ram Singh'"
                + " and canonical_payload->>'dateOfBirth' = '1980-03-12'"
                + " and canonical_payload->>'gender' = 'MALE'"
                + " and canonical_payload#>>'{extensions,familyReference}' = 'F5001'"));
        assertEquals(1, count(connections, "select count(*) from canonical_resident where batch_id = ?"
                + " and canonical_payload#>>'{extensions,blood_group}' = 'B+'"
                + " and canonical_payload#>>'{extensions,disability_pct}' = '20'", state3));

        assertEquals(3, service.processBatch(state1).recordsSkipped());
        assertEquals(2, service.processBatch(state2).recordsSkipped());
        assertEquals(2, service.processBatch(state3).recordsSkipped());
        assertEquals(7, count(connections, "select count(*) from canonical_resident"));
        assertEquals(7, count(connections, "select count(*) from canonical_resident c"
                + " join raw_record r on r.raw_record_id = c.raw_record_id where c.batch_id = r.batch_id"));
        assertEquals(0, count(connections, "select count(*) from canonical_processing_error"));
    }

    static long count(PostgresConnectionFactory connections, String sql, UUID... ids) throws Exception {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < ids.length; i++) {
                s.setObject(i + 1, ids[i]);
            }
            try (var r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }
}
