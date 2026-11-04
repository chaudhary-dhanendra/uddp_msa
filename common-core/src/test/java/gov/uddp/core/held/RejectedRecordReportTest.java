package gov.uddp.core.held;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.security.DepartmentScope;
import gov.uddp.core.security.Permission;
import gov.uddp.core.security.StateScope;
import gov.uddp.core.security.UddpAuthentication;
import gov.uddp.core.security.UddpPrincipal;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import gov.uddp.core.validation.ValidationIssue.Severity;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The final-rejected report (filters, masking, reopened flag, audit), the department feedback file of a batch, and
 * the permission and state scope of both.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RejectedRecordReportTest {
    @TestConfiguration
    static class Database {
        @Bean
        @Primary
        PostgresConnectionFactory rejectedReportConnections() {
            return TestDatabase.freshMigrated();
        }
    }

    private static final String NAME = "Ramesh Kumar Singh";
    private static final String RATION = "RC-77123456";

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgresConnectionFactory connections;

    @Autowired
    HeldRecordRepository repository;

    private final ObjectMapper json = new ObjectMapper();
    private UUID batch1;
    private UUID batch2;
    private UUID rejected1;
    private UUID reopened1;
    private UUID rejected2;

    private static UddpPrincipal user(String name, Set<Permission> permissions, StateScope scope) {
        return new UddpPrincipal(null, name, name, Set.of("TEST"),
                permissions.isEmpty() ? Set.of() : EnumSet.copyOf(permissions),
                scope.all() ? DepartmentScope.of(List.of(DepartmentScope.ALL)) : DepartmentScope.of(List.of("DEPT-1")),
                scope, false, "TEST");
    }

    private static RequestPostProcessor as(UddpPrincipal principal) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .authentication(new UddpAuthentication(principal));
    }

    private final UddpPrincipal nobody = user("nobody", EnumSet.noneOf(Permission.class), StateScope.everyState());
    private final UddpPrincipal steward = user("steward", EnumSet.of(Permission.STEWARD_HELD_VIEW,
            Permission.STEWARD_HELD_DECIDE), StateScope.everyState());
    private final UddpPrincipal admin = user("root", EnumSet.allOf(Permission.class), StateScope.everyState());
    private final UddpPrincipal auditor = user("auditor", EnumSet.of(Permission.AUDIT_VIEW, Permission.REPORT_EXPORT),
            StateScope.everyState());
    private final UddpPrincipal viewOnly = user("viewer", EnumSet.of(Permission.AUDIT_VIEW), StateScope.everyState());
    private final UddpPrincipal fullViewer = user("full", EnumSet.of(Permission.AUDIT_VIEW, Permission.REPORT_EXPORT,
            Permission.RESIDENT_VIEW_FULL), StateScope.everyState());
    private final UddpPrincipal st1Auditor = user("st1.auditor", EnumSet.of(Permission.AUDIT_VIEW,
            Permission.REPORT_EXPORT, Permission.BATCH_VIEW), StateScope.of(List.of("ST1")));
    private final UddpPrincipal department = user("dept", EnumSet.of(Permission.BATCH_VIEW), StateScope.everyState());

    @BeforeEach
    void seed() throws Exception {
        if (batch1 != null) {
            return;
        }
        batch1 = batch("ST1");
        batch2 = batch("ST2");
        rejected1 = hold(batch1, "ST1", "R1001", "dateOfBirth");
        reopened1 = hold(batch1, "ST1", "R1002", "rationNumber");
        hold(batch1, "ST1", "R1003", "dateOfBirth");
        UUID rectified = hold(batch1, "ST1", "R1004", "dateOfBirth");
        rejected2 = hold(batch2, "ST2", "R2001", "dateOfBirth");

        reject(rejected1, "SOURCE_ERROR", "date typed wrongly");
        reject(reopened1, "OUT_OF_SCOPE", "not ours");
        reject(rejected2, "UNREADABLE", "scan unreadable");
        assertEquals(200, status(post("/api/v1/held-records/" + reopened1 + "/reopen")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"source fixed\"}").with(as(admin))));
        new Sql(connections).execute("update held_record set status = 'RECTIFIED' where held_id = ?", rectified);
    }

    // ---- the audit report ------------------------------------------------------------------------------------------

    @Test
    void listsRejectedRecordsWithMaskedReferenceAndFiltersByEveryDimension() throws Exception {
        var all = body("/api/v1/audit-reports/final-rejected?size=100", auditor);
        assertEquals(Set.of(rejected1.toString(), reopened1.toString(), rejected2.toString()),
                new HashSet<>(ids(all)), "records never rejected are not listed");
        JsonNode first = byId(all, rejected1);
        assertEquals("•••1001", first.path("reference").asText(), "the record reference is masked");
        assertEquals("SOURCE_ERROR", first.path("reasonCode").asText());
        assertEquals("date typed wrongly", first.path("note").asText());
        assertEquals("steward", first.path("decidedBy").asText());
        assertFalse(first.path("reopened").asBoolean());
        assertEquals(1, first.path("originalReasons").size());
        assertEquals("RULE_FAILED:dob-not-future", first.path("originalReasons").get(0).path("code").asText());
        assertEquals("dateOfBirth", first.path("originalReasons").get(0).path("field").asText());
        assertEquals("pds", first.path("sourceSystem").asText());
        assertFalse(all.toString().contains("R1001"), "no unmasked reference in the view");

        assertEquals(2, filtered("batch=" + batch1).path("totalItems").asInt());
        assertEquals(1, filtered("batch=" + batch2).path("totalItems").asInt());
        assertEquals(3, filtered("source=pds").path("totalItems").asInt());
        assertEquals(0, filtered("source=other").path("totalItems").asInt());
        assertEquals(2, filtered("state=ST1").path("totalItems").asInt());
        assertEquals(0, filtered("department=Nowhere").path("totalItems").asInt());
        assertEquals(1, filtered("reason=UNREADABLE").path("totalItems").asInt());
        assertEquals(3, filtered("decidedBy=STEW").path("totalItems").asInt());
        assertEquals(0, filtered("decidedBy=someone").path("totalItems").asInt());
        assertEquals(3, filtered("from=2000-01-01&to=2999-12-31").path("totalItems").asInt());
        assertEquals(0, filtered("to=2000-01-01").path("totalItems").asInt());
        assertEquals(0, filtered("from=2999-01-01").path("totalItems").asInt());
        assertEquals(400, status(get("/api/v1/audit-reports/final-rejected?from=yesterday").with(as(auditor))));
    }

    @Test
    void aReopenedRecordStaysInTheReportAndShowsAsReopened() throws Exception {
        JsonNode row = byId(body("/api/v1/audit-reports/final-rejected?size=100", auditor), reopened1);

        assertTrue(row.path("reopened").asBoolean());
        assertEquals("OPEN", row.path("currentStatus").asText());
        assertEquals("OUT_OF_SCOPE", row.path("reasonCode").asText(), "the rejection survives the reopening");
        assertEquals("steward", row.path("decidedBy").asText());
        assertFalse(row.path("decidedAt").isNull());
    }

    @Test
    void exportIsMaskedByDefaultAndUnmaskedOnlyForACallerWhoMayViewPersonalData() throws Exception {
        String csv = text("/api/v1/audit-reports/final-rejected/export", auditor);

        assertTrue(csv.startsWith("reference,source,batch,state,department,reasonCode,note,originalFailureReasons,"
                + "decidedBy,decidedAt,reopened,currentStatus,payloadPurged"));
        assertTrue(csv.contains("•••1001,pds"));
        assertTrue(csv.contains("RULE_FAILED:dob-not-future (dateOfBirth)"));
        assertTrue(csv.contains(",yes,OPEN,"), "the reopened record");
        for (String pii : List.of("R1001", "R1002", "R2001", NAME, "9876543210", RATION)) {
            assertFalse(csv.contains(pii), "unmasked " + pii);
        }
        assertEquals(403, status(get("/api/v1/audit-reports/final-rejected/export?unmasked=true").with(as(auditor))));
        assertEquals(403, status(get("/api/v1/audit-reports/final-rejected?unmasked=true").with(as(auditor))));
        String full = text("/api/v1/audit-reports/final-rejected/export?unmasked=true", fullViewer);
        assertTrue(full.contains("R1001"));
        assertFalse(full.contains(NAME), "the export never carries the payload, only the reference");
    }

    @Test
    void everyViewAndExportIsAudited() throws Exception {
        var sql = new Sql(connections);
        long views = sql.count("select count(*) from app_audit_event where action = 'FINAL_REJECTED_REPORT_VIEW'");
        long exports = sql.count("select count(*) from app_audit_event where action = 'FINAL_REJECTED_REPORT_EXPORT'");

        text("/api/v1/audit-reports/final-rejected?state=ST1", auditor);
        text("/api/v1/audit-reports/final-rejected/summary", auditor);
        text("/api/v1/audit-reports/final-rejected/export?reason=UNREADABLE", auditor);
        text("/api/v1/batches/" + batch1 + "/rejection-report", department);

        assertEquals(views + 2, sql.count("select count(*) from app_audit_event where action ="
                + " 'FINAL_REJECTED_REPORT_VIEW'"));
        assertEquals(exports + 1, sql.count("select count(*) from app_audit_event where action ="
                + " 'FINAL_REJECTED_REPORT_EXPORT'"));
        assertEquals(1, sql.count("select count(*) from app_audit_event where action = 'BATCH_REJECTION_REPORT_EXPORT'"
                + " and resource_id = ? and actor_username = 'dept'", batch1.toString()));
        assertEquals(1, sql.count("select count(*) from app_audit_event where action = 'FINAL_REJECTED_REPORT_EXPORT'"
                + " and actor_username = 'auditor' and metadata->'filter'->>'reason' = 'UNREADABLE'"));
    }

    @Test
    void summaryCountsAndTrendPerSourcePerWeek() throws Exception {
        JsonNode summary = body("/api/v1/audit-reports/final-rejected/summary", auditor);

        assertEquals(3, summary.path("total").asInt());
        assertEquals(1, summary.path("reopened").asInt());
        int rejectedRecently = 0;
        for (JsonNode week : summary.path("perSourcePerWeek")) {
            assertEquals("pds", week.path("sourceSystem").asText());
            rejectedRecently += week.path("count").asInt();
        }
        assertEquals(3, rejectedRecently);
        assertEquals(2, body("/api/v1/audit-reports/final-rejected/summary", st1Auditor).path("total").asInt());
    }

    // ---- scope and permissions -------------------------------------------------------------------------------------

    @Test
    void reportsNeedTheirPermissionsAndStayInTheCallersStates() throws Exception {
        assertEquals(403, status(get("/api/v1/audit-reports/final-rejected").with(as(nobody))));
        assertEquals(403, status(get("/api/v1/audit-reports/final-rejected").with(as(steward))));
        assertEquals(403, status(get("/api/v1/audit-reports/final-rejected/summary").with(as(nobody))));
        assertEquals(403, status(get("/api/v1/audit-reports/final-rejected/export").with(as(viewOnly))),
                "export also needs REPORT_EXPORT");
        assertEquals(200, status(get("/api/v1/audit-reports/final-rejected").with(as(viewOnly))));

        var scoped = body("/api/v1/audit-reports/final-rejected?size=100", st1Auditor);
        assertEquals(2, scoped.path("totalItems").asInt());
        assertFalse(ids(scoped).contains(rejected2.toString()));
        assertFalse(text("/api/v1/audit-reports/final-rejected/export", st1Auditor).contains("•••2001"));
        assertEquals(0, body("/api/v1/audit-reports/final-rejected?state=ST2", st1Auditor).path("totalItems").asInt());
    }

    // ---- the department feedback file ------------------------------------------------------------------------------

    @Test
    void theBatchRejectionReportListsEveryHeldRowWithItsStatusAndMaskedValues() throws Exception {
        String csv = text("/api/v1/batches/" + batch1 + "/rejection-report", department);
        List<String> lines = csv.lines().toList();

        assertEquals("rowNumber,reasonCode,reasonText,field,maskedValue,status", lines.getFirst());
        assertEquals(5, lines.size(), "four held rows with one reason each: " + csv);
        assertEquals("1,RULE_FAILED:dob-not-future,date is after reference date,dateOfBirth,2099,REJECTED_FINAL",
                lines.get(1));
        assertEquals("2,RULE_FAILED:dob-not-future,date is after reference date,rationNumber,•••3456,HELD",
                lines.get(2), "reopened, so held again; an identifier keeps only its last four characters");
        assertTrue(lines.get(3).startsWith("3,") && lines.get(3).endsWith(",HELD"), lines.get(3));
        assertTrue(lines.get(4).startsWith("4,") && lines.get(4).endsWith(",RECTIFIED"), lines.get(4));
        for (String pii : List.of(NAME, "Ramesh Kumar", "9876543210", RATION, "R1001")) {
            assertFalse(csv.contains(pii), "unmasked " + pii);
        }
    }

    @Test
    void theBatchRejectionReportUsesTheStoredLineOfTheDeliveredFile() throws Exception {
        // Hop stores each row's physical line (V111); blank and title lines make it differ from the landing order.
        var sql = new gov.uddp.core.testsupport.Sql(connections);
        sql.execute("update raw_record r set source_line = 10 * x.n + 3 from (select raw_record_id,"
                + " row_number() over (order by ingested_at, ctid) n from raw_record where batch_id = ?) x"
                + " where r.raw_record_id = x.raw_record_id", batch1);
        try {
            List<String> lines = text("/api/v1/batches/" + batch1 + "/rejection-report", department).lines().toList();
            assertTrue(lines.get(1).startsWith("13,"), lines.get(1));
            assertTrue(lines.get(4).startsWith("43,"), lines.get(4));
        } finally {
            sql.execute("update raw_record set source_line = null where batch_id = ?", batch1);
        }
    }

    @Test
    void theBatchRejectionReportNeedsBatchViewAndTheBatchesState() throws Exception {
        String path = "/api/v1/batches/" + batch2 + "/rejection-report";

        assertEquals(403, status(get(path).with(as(nobody))));
        assertEquals(403, status(get(path).with(as(steward))));
        assertEquals(200, status(get(path).with(as(department))));
        assertEquals(404, status(get(path).with(as(st1Auditor))), "another state's batch is unknown to them");
        assertEquals(404, status(get("/api/v1/batches/" + UUID.randomUUID() + "/rejection-report")
                .with(as(department))));
        assertEquals(200, status(get("/api/v1/batches/" + batch1 + "/rejection-report").with(as(st1Auditor))));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private void reject(UUID id, String code, String note) throws Exception {
        assertEquals(200, status(post("/api/v1/held-records/" + id + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"" + code + "\",\"note\":\"" + note + "\"}").with(as(steward))));
    }

    private JsonNode filtered(String query) throws Exception {
        return body("/api/v1/audit-reports/final-rejected?size=100&" + query, auditor);
    }

    private static JsonNode byId(JsonNode page, UUID id) {
        for (JsonNode item : page.path("items")) {
            if (item.path("heldId").asText().equals(id.toString())) {
                return item;
            }
        }
        throw new AssertionError("Not in the report: " + id);
    }

    private static List<String> ids(JsonNode page) {
        var ids = new java.util.ArrayList<String>();
        page.path("items").forEach(item -> ids.add(item.path("heldId").asText()));
        return ids;
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private String text(String path, UddpPrincipal caller) throws Exception {
        var response = mvc.perform(get(path).with(as(caller))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), path);
        return response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private JsonNode body(String path, UddpPrincipal caller) throws Exception {
        return json.readTree(text(path, caller));
    }

    private UUID batch(String state) {
        UUID batch = UUID.randomUUID();
        new Sql(connections).execute("insert into ingestion_batch(batch_id,state_code,source_system,source_type,"
                + "source_schema_version,state_pack_version,pipeline_template,status,record_count)"
                + " values(?,?,'pds','file','1.0','1.0.0','generic-file-ingest.hpl','COMPLETED',10)", batch, state);
        return batch;
    }

    private UUID hold(UUID batch, String state, String reference, String failingField) throws Exception {
        UUID raw = UUID.randomUUID();
        new Sql(connections).execute("insert into raw_record(raw_record_id,batch_id,state_code,source_system,"
                + "source_record_id,raw_payload,source_metadata,state_pack_version,source_schema_version,"
                + "pipeline_template) values(?,?,?,'pds',?,?,'{}','1.0.0','1.0','generic-file-ingest.hpl')",
                raw, batch, state, reference, "{\"rawLine\":\"" + NAME + ",12-03-2099,9876543210," + RATION + "\"}");
        var payload = json.createObjectNode().put("name", NAME).put("mobile", "9876543210")
                .put("dateOfBirth", "2099-03-12").put("rationNumber", RATION);
        repository.insert(new HeldRecord(null, batch, raw, "pds", state, null, "1.0.0",
                List.of(new HeldReason("RULE_FAILED:dob-not-future", "date is after reference date", failingField,
                        Severity.ERROR)),
                "OPEN", null, null, null, null, null, null, payload, payload, 0), null);
        return new Sql(connections).uuid("select held_id from held_record where raw_record_id=?", raw);
    }
}
