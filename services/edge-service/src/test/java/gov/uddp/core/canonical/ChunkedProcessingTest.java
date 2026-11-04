package gov.uddp.core.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Chunked canonical processing over 20,000 synthetic rows of the state-1 pds source: chunks of 5,000 commit one
 * transaction each, a stop after the second chunk resumes from the third without redoing or duplicating anything,
 * and a source record claimed by two rows in different chunks is rejected for both.
 */
class ChunkedProcessingTest {
    private static final int ROWS = 20_000;
    private static final String HEADER = "beneficiary_nm,dob,sex_cd,dist_cd,ration_no,mobile,card_type,card_status,"
            + "card_issued_on,monthly_grain_kg,last_lifted_on,last_lifted_kg";

    private PostgresConnectionFactory connections;
    private Sql sql;
    private StatePackLoader packs;
    private ProcessingProgress progress;

    @BeforeEach
    void database() {
        connections = TestDatabase.freshMigrated();
        sql = new Sql(connections);
        packs = SampleData.loader(SampleData.STATE_PACKS);
        progress = new ProcessingProgress(connections);
    }

    private CanonicalResidentProcessingService service(int chunkSize) {
        var service = SampleData.processing(connections, packs);
        service.setChunkSize(chunkSize);
        service.setProgress(progress);
        return service;
    }

    @Test
    void twentyThousandRowsAreProcessedInFourCommittedChunks() throws Exception {
        UUID batch = land(ROWS, false);

        var summary = service(5_000).processBatch(batch);

        assertEquals(ROWS, summary.recordsRead());
        assertEquals(ROWS, summary.recordsMapped());
        assertEquals(0, summary.recordsFailed());
        assertEquals(ROWS, sql.count("select count(*) from canonical_resident where batch_id='" + batch + "'"));
        assertEquals(4, sql.count("select count(*) from canonical_processing_chunk where batch_id='" + batch + "'"));
        assertEquals(4, sql.count("select last_committed_chunk from canonical_processing_progress where batch_id='"
                + batch + "' and status='COMPLETED'"));
        assertEquals(ROWS, progress.totals(batch).read());
        assertEquals(1, sql.count("select count(*) from dq_batch_evaluation where batch_id='" + batch + "'"),
                "one DQ evaluation per batch, not per chunk");
        assertEquals(ROWS, sql.count("select records_evaluated from dq_batch_evaluation where batch_id='" + batch + "'"));
        assertTrue(progress.chunks(batch).stream().allMatch(chunk -> chunk.counts().read() == 5_000));
    }

    @Test
    void aStopAfterTheSecondChunkResumesFromTheThirdWithoutDuplicates() throws Exception {
        UUID batch = land(ROWS, false);
        var interrupted = service(5_000);
        interrupted.setAfterChunk(number -> {
            if (number == 2) {
                throw new IllegalStateException("simulated crash after chunk 2");
            }
        });

        assertThrows(IllegalStateException.class, () -> interrupted.processBatch(batch));

        assertEquals(2, sql.count("select last_committed_chunk from canonical_processing_progress where batch_id='"
                + batch + "' and status='RUNNING'"));
        assertEquals(10_000, sql.count("select count(*) from canonical_resident where batch_id='" + batch + "'"));

        var resumed = service(5_000).processBatch(batch);

        assertEquals(ROWS, resumed.recordsRead(), "totals include the chunks committed before the stop");
        assertEquals(ROWS, resumed.recordsMapped());
        assertEquals(ROWS, sql.count("select count(*) from canonical_resident where batch_id='" + batch + "'"));
        assertEquals(ROWS, sql.count("select count(distinct raw_record_id) from canonical_resident where batch_id='"
                + batch + "'"));
        assertEquals(1, sql.count("select count(*) from dq_batch_evaluation where batch_id='" + batch + "'"));
        assertEquals(ROWS, sql.count("select records_evaluated from dq_batch_evaluation where batch_id='" + batch + "'"));
        assertEquals(4, sql.count("select count(*) from canonical_processing_chunk where batch_id='" + batch + "'"));
        assertEquals(1, sql.count("select count(*) from canonical_processing_progress where batch_id='" + batch
                + "' and status='COMPLETED' and last_committed_chunk=4"));
    }

    @Test
    void aChunkThatFailsRollsBackWholeAndLeavesTheLastCommittedChunk() throws Exception {
        UUID batch = land(10_000, false);
        // A failing family observation in the second chunk aborts that chunk's transaction.
        var failing = service(5_000);
        var calls = new int[] {0};
        failing.setBenefitProcessor(new CanonicalBenefitProcessor(connections,
                new gov.uddp.core.mapping.BenefitMappingEngine(
                        new gov.uddp.core.mapping.RawCsvRecordParser(new com.fasterxml.jackson.databind.ObjectMapper()),
                        new gov.uddp.core.scheme.SchemeCatalogue(SampleData.SCHEME_CATALOGUE)),
                new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override
            public Outcome observe(gov.uddp.core.mapping.RawRecord raw, gov.uddp.core.statepack.LoadedStatePack pack,
                                   String key, UUID canonicalId) {
                if (++calls[0] == 7_500) {
                    throw new IllegalStateException("benefit store down");
                }
                return super.observe(raw, pack, key, canonicalId);
            }
        });

        assertThrows(IllegalStateException.class, () -> failing.processBatch(batch));

        assertEquals(1, sql.count("select last_committed_chunk from canonical_processing_progress where batch_id='"
                + batch + "'"));
        assertEquals(5_000, sql.count("select count(*) from canonical_resident where batch_id='" + batch + "'"),
                "the second chunk's 2,499 rows were rolled back with it");

        var resumed = service(5_000).processBatch(batch);
        assertEquals(10_000, resumed.recordsRead());
        assertEquals(10_000, sql.count("select count(*) from canonical_resident where batch_id='" + batch + "'"));
    }

    @Test
    void aSourceRecordClaimedInTwoChunksIsRejectedForBothRows() throws Exception {
        UUID batch = land(12_000, true);

        var summary = service(5_000).processBatch(batch);

        assertEquals(12_000, summary.recordsRead());
        assertEquals(2, summary.recordsFailed());
        assertEquals(11_998, summary.recordsMapped());
        assertEquals(2, sql.count("select count(*) from canonical_processing_error where batch_id='" + batch + "'"));
        assertEquals(2, progress.rejectedCount(batch));
        var reasons = new java.util.ArrayList<String>();
        progress.forEachRejected(batch, rejected -> reasons.add(rejected.reason()));
        assertTrue(reasons.stream().allMatch(reason -> reason.startsWith("Duplicate source record key in batch")));
    }

    /** Lands {@code rows} unique pds rows; with {@code duplicate}, row 4 and the last row claim the same source record. */
    private UUID land(int rows, boolean duplicate) throws SQLException {
        var pack = packs.find("state-1").orElseThrow();
        var source = pack.sources().stream().filter(s -> s.sourceId().equals("pds")).findFirst().orElseThrow();
        String version = pack.manifest().version();
        UUID batch = UUID.randomUUID();
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try (var s = c.prepareStatement("insert into ingestion_batch(batch_id,state_code,source_system,source_type,"
                    + "source_schema_version,state_pack_version,pipeline_template,status,record_count)"
                    + " values(?,?,?,?,?,?,?,'COMPLETED',?)")) {
                s.setObject(1, batch);
                s.setString(2, pack.manifest().stateCode());
                s.setString(3, source.sourceId());
                s.setString(4, source.sourceType());
                s.setString(5, source.sourceSchemaVersion());
                s.setString(6, version);
                s.setString(7, source.hopTemplate());
                s.setInt(8, rows);
                s.executeUpdate();
            }
            try (var s = c.prepareStatement("insert into raw_record(batch_id,state_code,source_system,raw_payload,"
                    + "source_metadata,state_pack_version,source_schema_version,pipeline_template)"
                    + " values(?,?,?,?,?::jsonb,?,?,?)")) {
                for (int i = 0; i < rows; i++) {
                    int n = duplicate && i == rows - 1 ? 4 : i;
                    String line = "Person " + n + ",12-03-1980,M,101,R" + n + "," + (9_000_000_000L + n)
                            + ",PHH,A,01-04-2015,25,05-10-2026,25";
                    s.setObject(1, batch);
                    s.setString(2, pack.manifest().stateCode());
                    s.setString(3, source.sourceId());
                    s.setString(4, "{\"csvHeader\":\"" + HEADER + "\",\"rawLine\":\"" + line + "\"}");
                    s.setString(5, "{\"sourceEntity\":\"" + source.sourceId() + "\"}");
                    s.setString(6, version);
                    s.setString(7, source.sourceSchemaVersion());
                    s.setString(8, source.hopTemplate());
                    s.addBatch();
                }
                s.executeBatch();
            }
            c.commit();
        }
        return batch;
    }
}
