package gov.uddp.core.canonical;

import static gov.uddp.core.testsupport.AssetFixture.HEADER;
import static gov.uddp.core.testsupport.AssetFixture.RULES;
import static gov.uddp.core.testsupport.AssetFixture.pack;
import static gov.uddp.core.testsupport.AssetFixture.raw;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.CanonicalAssetProcessor.Outcome;
import gov.uddp.core.mapping.AssetMappingEngine;
import gov.uddp.core.mapping.RawCsvRecordParser;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CanonicalAssetProcessingTest {
    private final PostgresConnectionFactory connections = TestDatabase.freshMigrated();
    private final Sql sql = new Sql(connections);
    private final ObjectMapper json = new ObjectMapper();
    private final CanonicalAssetProcessor processor = new CanonicalAssetProcessor(connections,
            new AssetMappingEngine(new RawCsvRecordParser(json)), json);
    private final UUID batch = UUID.randomUUID();

    @Test
    void observationsShareOneStableIdentityAndFailuresAreRecorded() {
        sql.execute("insert into ingestion_batch(batch_id,state_code,source_system,source_type,state_pack_version,"
                + "pipeline_template,status) values (?,'FX','src','file','1.0.0','generic-file-ingest.hpl','COMPLETED')", batch);
        var pack = pack("1.0.0", RULES);
        var first = delivery("S-1,PV,Panel,3,KW,100,B-1,,,");
        assertEquals(Outcome.OBSERVED, processor.observe(first, pack, "K|1", null));
        assertEquals(Outcome.ALREADY_OBSERVED, processor.observe(first, pack, "K|1", null));
        assertEquals(Outcome.OBSERVED, processor.observe(delivery("S-1,PV,Panel,4,KW,100,B-1,,,"), pack, "K|1", null));
        assertEquals(1, sql.count("select count(*) from asset"));
        assertEquals(2, sql.count("select count(*) from asset_observation"));
        assertEquals("K|1|4", sql.text("select holder_source_record_key || '|' || quantity::int from asset_state"));

        assertEquals(Outcome.FAILED, processor.observe(delivery("S-2,XYZ,,,,,,,,"), pack, "K|1", null));
        assertEquals(1, sql.count("select count(*) from asset_processing_error"));
        assertEquals(Outcome.NO_ASSET, processor.observe(delivery(",PV,,,,,,,,"), pack, "K|1", null));
        assertEquals(Outcome.NO_ASSET, processor.observe(delivery("S-3,PV,,,,,,,,"), pack("1.0.0", null), "K|1", null));
        assertEquals(1, sql.count("select count(*) from asset"));
    }

    private RawRecord delivery(String line) {
        UUID id = UUID.randomUUID();
        sql.execute("insert into raw_record(raw_record_id,batch_id,state_code,source_system,raw_payload,"
                + "source_metadata,state_pack_version,pipeline_template) values (?,?,'FX','src','{}','{}','1.0.0',"
                + "'generic-file-ingest.hpl')", id, batch);
        return raw(id, batch, "FX", HEADER, line);
    }
}
