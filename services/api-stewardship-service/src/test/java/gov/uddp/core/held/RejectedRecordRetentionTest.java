package gov.uddp.core.held;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.privacy.RetentionService;
import gov.uddp.core.rules.ApprovalConflictException;
import gov.uddp.core.read.Db;
import gov.uddp.core.read.RejectedRecordReportService;
import gov.uddp.core.security.AuditService;
import gov.uddp.core.config.StudioProperties;
import gov.uddp.core.studio.StudioWorkspace;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import gov.uddp.core.validation.ValidationIssue.Severity;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The retention purge drops a final-rejected record's payloads and keeps its audit row and the report line. */
class RejectedRecordRetentionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void purgeRemovesPayloadsOfRejectedRecordsButKeepsReferenceReasonActorAndTime() throws Exception {
        PostgresConnectionFactory connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        var repository = new HeldRecordRepository(connections, JSON);
        UUID batch = UUID.randomUUID();
        sql.execute("insert into ingestion_batch(batch_id,state_code,source_system,source_type,source_schema_version,"
                + "state_pack_version,pipeline_template,status,record_count) values(?,'ST1','pds','file','1.0',"
                + "'1.0.0','generic-file-ingest.hpl','COMPLETED',2)", batch);
        UUID rejected = hold(connections, repository, batch, "R1001");
        UUID waiting = hold(connections, repository, batch, "R1002");
        repository.transition(rejected, List.of("OPEN"), "REJECTED_FINAL", "steward", "SOURCE_ERROR", "bad date",
                "REJECT", null, java.util.Map.of("reasonCode", "SOURCE_ERROR"));

        var properties = new StudioProperties();
        properties.setWorkspaceDirectory(Files.createTempDirectory("uddp-retention").toString());
        var retention = new RetentionService(connections, new AuditService(connections, JSON),
                new StudioWorkspace(properties, JSON, connections), JSON, 0, null, null, null);

        var dry = retention.purge(true, "platform.admin");
        assertEquals(1L, dry.counts().get(RetentionService.REJECTED_PAYLOADS));
        assertEquals(0, sql.count("select count(*) from held_record where payload_purged_at is not null"));

        var real = retention.purge(false, "platform.admin");
        assertEquals(1L, real.counts().get(RetentionService.REJECTED_PAYLOADS));

        // payloads gone, also from the correction attempts
        assertEquals("{\"uddp.purged\": \"retention\"}".replace(" ", ""),
                sql.text("select replace(current_payload::text, ' ', '') from held_record where held_id = ?", rejected));
        assertNull(sql.column("select original_payload::text from held_record where held_id = ?", String.class,
                rejected).getFirst());
        // the audit row is kept
        assertEquals(1, sql.count("select count(*) from held_record where held_id = ? and status = 'REJECTED_FINAL'"
                + " and decided_by = 'steward' and decided_at is not null and decision_reason_code = 'SOURCE_ERROR'",
                rejected));
        assertEquals(1, sql.count("select count(*) from held_record_event where held_id = ? and action = 'REJECT'"
                + " and actor = 'steward'", rejected));
        assertEquals(0, sql.count("select count(*) from held_record where held_id = ? and payload_purged_at is not null",
                waiting), "a record still waiting for a steward is kept whole until its raw record is purged");

        // and it is still in the audit report, flagged as purged, reference masked
        var reports = new RejectedRecordReportService(new Db(connections, JSON), JSON);
        var page = reports.rejected(batch, null, null, null, null, null, null, null, false, 0, 10);
        assertEquals(1, page.totalItems());
        var row = page.items().getFirst();
        assertEquals("•••1001", row.reference());
        assertEquals("SOURCE_ERROR", row.reasonCode());
        assertEquals("steward", row.decidedBy());
        assertTrue(row.payloadPurged());
        assertEquals("RULE_FAILED:dob-not-future", row.originalReasons().getFirst().code());

        // the department file keeps the line but has no value to show
        var feedback = reports.batchRejectionReport(batch);
        var line = feedback.stream().filter(f -> f.status().equals("REJECTED_FINAL")).findFirst().orElseThrow();
        assertNull(line.maskedValue());
        assertFalse(feedback.isEmpty());

        // a purged record cannot be reopened
        var service = new HeldRecordService(repository, null, null, connections, 50);
        assertThrows(ApprovalConflictException.class, () -> service.reopen(rejected, "retry", "root"));
    }

    private UUID hold(PostgresConnectionFactory connections, HeldRecordRepository repository, UUID batch,
                      String reference) {
        var sql = new Sql(connections);
        UUID raw = UUID.randomUUID();
        sql.execute("insert into raw_record(raw_record_id,batch_id,state_code,source_system,source_record_id,"
                + "raw_payload,source_metadata,state_pack_version,source_schema_version,pipeline_template)"
                + " values(?,?,'ST1','pds',?,'{\"rawLine\":\"Ramesh Kumar Singh,12-03-2099\"}','{}','1.0.0','1.0',"
                + "'generic-file-ingest.hpl')", raw, batch, reference);
        var payload = JSON.createObjectNode().put("name", "Ramesh Kumar Singh").put("dateOfBirth", "2099-03-12");
        repository.insert(new HeldRecord(null, batch, raw, "pds", "ST1", null, "1.0.0",
                List.of(new HeldReason("RULE_FAILED:dob-not-future", "date is after reference date", "dateOfBirth",
                        Severity.ERROR)), "OPEN", null, null, null, null, null, null, payload, payload, 0), null);
        return sql.uuid("select held_id from held_record where raw_record_id = ?", raw);
    }
}
