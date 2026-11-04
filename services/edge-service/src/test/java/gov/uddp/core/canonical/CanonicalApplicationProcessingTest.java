package gov.uddp.core.canonical;

import static gov.uddp.core.testsupport.ApplicationFixture.HEADER;
import static gov.uddp.core.testsupport.ApplicationFixture.RULES;
import static gov.uddp.core.testsupport.ApplicationFixture.pack;
import static gov.uddp.core.testsupport.ApplicationFixture.raw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.CanonicalApplicationProcessor.Outcome;
import gov.uddp.core.mapping.ApplicationMappingEngine;
import gov.uddp.core.mapping.RawCsvRecordParser;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.scheme.SchemeCatalogue;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Canonical Application observations over three synthetic deliveries of one application: new (first stage event),
 * stage change (second event) and unchanged (observation only). Events are append-only.
 */
class CanonicalApplicationProcessingTest {
    private final PostgresConnectionFactory connections = TestDatabase.freshMigrated();
    private final Sql sql = new Sql(connections);
    private final ObjectMapper json = new ObjectMapper();
    private final CanonicalApplicationProcessor processor = new CanonicalApplicationProcessor(connections,
            new ApplicationMappingEngine(new RawCsvRecordParser(json), new SchemeCatalogue(SampleData.SCHEME_CATALOGUE)),
            json);
    private final UUID batch = UUID.randomUUID();

    @Test
    void stageEventsAreDerivedFromNewChangedAndUnchangedDeliveries() {
        sql.execute("insert into ingestion_batch(batch_id,state_code,source_system,source_type,state_pack_version,"
                + "pipeline_template,status) values (?,'FX','src','file','1.0.0','generic-file-ingest.hpl','COMPLETED')", batch);
        var pack = pack("1.0.0", RULES);

        var first = delivery("A-1,OAP,K|1,VERIFICATION,PENDING,APPROVAL,20260105,20260105,online,portal");
        assertEquals(Outcome.OBSERVED, processor.observe(first, pack, "K|1", null));
        assertEquals(Outcome.ALREADY_OBSERVED, processor.observe(first, pack, "K|1", null), "idempotent per raw record");
        assertEquals(1, sql.count("select count(*) from application_stage_event"));

        var changed = delivery("A-1,OAP,K|1,APPROVAL,PENDING,PAYMENT,20260105,20260112,online,portal");
        assertEquals(Outcome.OBSERVED, processor.observe(changed, pack, "K|1", null));
        assertEquals(2, sql.count("select count(*) from application_stage_event"));

        var unchanged = delivery("A-1,OAP,K|1,APPROVAL,PENDING,PAYMENT,20260105,20260119,online,portal");
        assertEquals(Outcome.OBSERVED, processor.observe(unchanged, pack, "K|1", null));
        assertEquals(2, sql.count("select count(*) from application_stage_event"), "no event when nothing changed");

        assertEquals(1, sql.count("select count(*) from application"), "one stable identity");
        assertEquals(3, sql.count("select count(*) from application_observation"));
        assertEquals("VERIFICATION/PENDING>APPROVAL/PENDING", sql.text("select string_agg(coalesce(previous_stage, '')"
                + " || '/' || coalesce(previous_status, '') || '>' || stage || '/' || status, ','"
                + " order by recorded_at) from (select * from application_stage_event) e where previous_stage is not null"));
        assertEquals(1, sql.count("select count(*) from application_stage_event where previous_stage is null"
                + " and stage = 'VERIFICATION'"));
        assertEquals("2026-01-12", sql.text("select occurred_on::text from application_stage_event"
                + " where stage = 'APPROVAL'"));
        assertEquals("APPROVAL|2026-01-19", sql.text("select current_stage || '|' || last_action_date::text"
                + " from application_state"));
        assertEquals("{\"mode\": \"online\"}", sql.text("select attributes::text from application_state"));

        assertThrows(RuntimeException.class, () -> sql.execute("update application_stage_event set stage = 'X'"));
        assertThrows(RuntimeException.class, () -> sql.execute("delete from application_stage_event"));
        assertEquals(2, sql.count("select count(*) from application_stage_event"));
    }

    @Test
    void failuresAreRecordedAndSourcesWithoutRulesAreSkipped() {
        sql.execute("insert into ingestion_batch(batch_id,state_code,source_system,source_type,state_pack_version,"
                + "pipeline_template,status) values (?,'FX','src','file','1.0.0','generic-file-ingest.hpl','COMPLETED')", batch);
        var pack = pack("1.0.0", RULES);

        assertEquals(Outcome.FAILED, processor.observe(delivery("A-9,GHOST,,S,ST,,,,,"), pack, "K|9", null));
        assertEquals(1, sql.count("select count(*) from application_processing_error where error_message"
                + " like '%No crosswalk value%' or error_message like '%not in scheme catalogue%'"));
        assertEquals(Outcome.NO_APPLICATION, processor.observe(delivery(",PDS,,S,ST,,,,,"), pack, "K|9", null));
        assertEquals(Outcome.NO_APPLICATION, processor.observe(delivery(",PDS,,S,ST,,,,,"), pack("1.0.0", null), "K|9", null));
        assertEquals(0, sql.count("select count(*) from application"));
    }

    private RawRecord delivery(String line) {
        UUID id = UUID.randomUUID();
        sql.execute("insert into raw_record(raw_record_id,batch_id,state_code,source_system,raw_payload,"
                + "source_metadata,state_pack_version,pipeline_template) values (?,?,'FX','src','{}','{}','1.0.0',"
                + "'generic-file-ingest.hpl')", id, batch);
        return raw(id, batch, "FX", HEADER, line);
    }
}
