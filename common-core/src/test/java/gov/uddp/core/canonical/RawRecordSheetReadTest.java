package gov.uddp.core.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One sheet of a delivery is read in SQL: only that source's rows of that file and sheet leave the database. */
class RawRecordSheetReadTest {
    private final PostgresConnectionFactory connections = TestDatabase.freshMigrated();
    private final PostgresRawRecordRepository raws = new PostgresRawRecordRepository(connections, new ObjectMapper());

    @Test
    void readsOnlyTheRequestedSheetOfTheSource() throws Exception {
        UUID batch = UUID.randomUUID();
        try (var c = connections.open();
                var s = c.prepareStatement("insert into ingestion_batch(batch_id,state_code,source_system,source_type,"
                        + "source_schema_version,state_pack_version,pipeline_template,status,record_count)"
                        + " values(?,'WB','census','file','1.0','1.0.0','generic-excel-ingest.hpl','COMPLETED',5)")) {
            s.setObject(1, batch);
            s.executeUpdate();
        }
        insert(batch, "census", "{\"format\":\"xlsx\",\"file\":\"book.xlsx\",\"sheet\":\"Ngairang\",\"row\":1,\"cells\":[]}");
        insert(batch, "census", "{\"format\":\"xlsx\",\"file\":\"book.xlsx\",\"sheet\":\"Ngairang\",\"row\":2,\"cells\":[]}");
        insert(batch, "census", "{\"format\":\"xlsx\",\"file\":\"book.xlsx\",\"sheet\":\"Senkhai\",\"row\":1,\"cells\":[]}");
        insert(batch, "other", "{\"format\":\"xlsx\",\"file\":\"book.xlsx\",\"sheet\":\"Ngairang\",\"row\":1,\"cells\":[]}");
        insert(batch, "census", "{\"csvHeader\":\"a\",\"rawLine\":\"1\"}");

        assertEquals(2, raws.findSheet(batch, "census", "book.xlsx", "Ngairang").size());
        assertEquals(1, raws.findSheet(batch, "census", "book.xlsx", "Senkhai").size());
        assertEquals(1, raws.findSheet(batch, "census", null, null).size(), "a CSV payload names no file or sheet");
    }

    private void insert(UUID batch, String source, String payload) throws Exception {
        try (var c = connections.open();
                var s = c.prepareStatement("insert into raw_record(raw_record_id,batch_id,state_code,source_system,"
                        + "raw_payload,source_metadata,state_pack_version,source_schema_version,pipeline_template)"
                        + " values(?,?,'WB',?,?,'{}'::jsonb,'1.0.0','1.0','generic-excel-ingest.hpl')")) {
            s.setObject(1, UUID.randomUUID());
            s.setObject(2, batch);
            s.setString(3, source);
            s.setString(4, payload);
            s.executeUpdate();
        }
    }
}
