package gov.uddp.core.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.config.StudioProperties;
import gov.uddp.core.security.AuditService;
import gov.uddp.core.studio.StudioWorkspace;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * P1 Part 5b on two fixture packs (tests/fixtures/national-id-packs, synthetic numbers): an Aadhaar column mapped to
 * {@code nationalIds.AADHAAR} keeps only a keyed hash and the last four digits, the same number gives the same hash
 * in both states (what matching compares), an Aadhaar-like value in an unmapped column is rejected, and the
 * retention purge counts in a dry run and redacts payloads while keeping lineage in a real run.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NationalIdAndRetentionTest {
    private static final Path PACKS = SampleData.REPOSITORY_ROOT.resolve("tests/fixtures/national-id-packs");
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private static PostgresConnectionFactory connections;
    private static Sql sql;
    private static Map<String, java.util.List<UUID>> batches;

    @TempDir
    static Path workspace;

    @BeforeAll
    static void processBothStates() {
        connections = TestDatabase.freshMigrated();
        sql = new Sql(connections);
        var loader = SampleData.loader(PACKS);
        var service = SampleData.processing(connections, loader);
        var protector = new NationalIdProtector(KEY, null);
        service.setNationalIdProtection(protector, new RawNationalIdRedactor(connections, protector, JSON, true));
        batches = SampleData.landAll(connections, PACKS);
        batches.values().forEach(ids -> ids.forEach(service::processBatch));
    }

    @Test
    void aMappedAadhaarKeepsOnlyKeyedHashAndLastFourDigits() {
        String payload = sql.text("select canonical_payload::text from canonical_resident where state_code = 'NIE'"
                + " and canonical_payload->>'name' = 'Ram Singh'");
        assertFalse(payload.contains("234567890124") || payload.contains("2345 6789 0124"), payload);
        String hash = sql.text("select canonical_payload->'extensions'->'uddp.identifiers'->'AADHAAR'->>'value'"
                + " from canonical_resident where state_code = 'NIE'");
        assertEquals(64, hash.length());
        assertEquals("0124", sql.text("select canonical_payload->'extensions'->'uddp.identifiers'->'AADHAAR'->>'last4'"
                + " from canonical_resident where state_code = 'NIE'"));

        // The raw staging row is redacted once processing succeeded: only the protected token remains.
        String raw = sql.text("select raw_payload from raw_record where state_code = 'NIE' and raw_payload like '%Ram Singh%'");
        assertFalse(raw.contains("2345 6789 0124"), raw);
        assertTrue(raw.contains("uddp-id:AADHAAR::0124:" + hash), raw);
        assertEquals(0, sql.count("select count(*) from app_audit_event where metadata::text like '%234567890124%'"
                + " or metadata::text like '%2345 6789 0124%'"));
    }

    @Test
    void theSameNationalIdInTwoStatesHasTheSameHashForMatching() {
        String east = sql.text("select canonical_payload->'extensions'->'uddp.identifiers'->'AADHAAR'->>'value'"
                + " from canonical_resident where state_code = 'NIE'");
        String west = sql.text("select canonical_payload->'extensions'->'uddp.identifiers'->'AADHAAR'->>'value'"
                + " from canonical_resident where state_code = 'NIW'");
        assertNotNull(east);
        assertEquals(east, west, "matching compares this hash exactly (UDDP_MATCH_NATIONAL_ID)");
    }

    @Test
    void anAadhaarLikeValueInAnotherFieldIsRejected() {
        assertEquals(0, sql.count("select count(*) from canonical_resident where canonical_payload->>'name' = 'Sita Devi'"));
        String error = sql.text("select e.error_message from canonical_processing_error e join raw_record r"
                + " on r.raw_record_id = e.raw_record_id where r.raw_payload like '%Sita Devi%'");
        assertTrue(error.contains("sensitive identifier") && error.contains("extensions.remarks"), error);
        assertFalse(error.contains("3456"), "the error names the field, never the value");
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void purgeDryRunCountsAndChangesNothingThenRealPurgeRedactsKeepsLineageAndAudits() throws Exception {
        var audit = new AuditService(connections, JSON);
        audit.record("TEST_EVENT", "TEST", "1", null, AuditService.SUCCESS);
        UUID draft = UUID.randomUUID();
        sql.execute("insert into studio_draft(draft_id, pack_id, state_code, draft)"
                + " values (?, 'nid-published', 'NIP', cast(? as jsonb))", draft,
                "{\"published\": {\"version\": \"1.0.0\"}}");
        Path upload = Files.createDirectories(workspace.resolve("uploads").resolve(draft.toString())).resolve("a.csv");
        Files.writeString(upload, "x\n1\n");
        Files.setLastModifiedTime(upload, FileTime.from(Instant.now().minus(10, ChronoUnit.DAYS)));
        var properties = new StudioProperties();
        properties.setWorkspaceDirectory(workspace.toString());
        var retention = new RetentionService(connections, audit, new StudioWorkspace(properties, JSON, connections),
                JSON, 0, 0, 0, 1);
        long rawRows = sql.count("select count(*) from raw_record");
        long canonicalRows = sql.count("select count(*) from canonical_resident");
        String before = sql.text("select string_agg(raw_payload, '|' order by raw_record_id) from raw_record");

        var dry = retention.purge(true, "platform.admin");

        assertEquals(rawRows, dry.counts().get(RetentionService.RAW_PAYLOADS));
        assertTrue(dry.counts().get(RetentionService.AUDIT_EVENTS) >= 1);
        assertEquals(1, dry.counts().get(RetentionService.UPLOADS));
        assertEquals(before, sql.text("select string_agg(raw_payload, '|' order by raw_record_id) from raw_record"));
        assertEquals(0, sql.count("select count(*) from raw_record where payload_purged_at is not null"));
        assertEquals(0, sql.count("select count(*) from retention_tombstone"));
        assertTrue(Files.exists(upload));

        var real = retention.purge(false, "platform.admin");

        assertEquals(rawRows, real.counts().get(RetentionService.RAW_PAYLOADS));
        assertEquals(rawRows, sql.count("select count(*) from raw_record where payload_purged_at is not null"
                + " and raw_payload = '" + RetentionService.MARKER + "'"));
        // Lineage stays: every raw and canonical row, with its IDs and metadata, still resolves.
        assertEquals(rawRows, sql.count("select count(*) from raw_record where source_metadata is not null"));
        assertEquals(canonicalRows, sql.count("select count(*) from canonical_resident c join raw_record r"
                + " on r.raw_record_id = c.raw_record_id"));
        assertEquals(real.counts().get(RetentionService.AUDIT_EVENTS),
                sql.count("select count(*) from retention_tombstone where category = 'AUDIT_EVENTS'"));
        assertFalse(Files.exists(upload));
        String event = "from app_audit_event where action = 'RETENTION_PURGE' and resource_id = ?";
        assertEquals("false", sql.text("select metadata->>'dryRun' " + event, real.runId().toString()));
        assertEquals(String.valueOf(rawRows), sql.text("select metadata->'counts'->>'RAW_PAYLOADS' " + event,
                real.runId().toString()));
        assertEquals("1", sql.text("select metadata->'counts'->>'UPLOADS' " + event, real.runId().toString()));
        assertEquals(2, sql.count("select count(*) from retention_run"));
    }
}
