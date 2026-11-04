package gov.uddp.core.held;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.privacy.PiiMaskingAdvice;
import gov.uddp.core.security.DepartmentScope;
import gov.uddp.core.security.Permission;
import gov.uddp.core.security.StateScope;
import gov.uddp.core.security.UddpAuthentication;
import gov.uddp.core.security.UddpPrincipal;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import gov.uddp.core.testsupport.TestSecurity;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * The held-record API: permissions are checked before the body is read (403 before 400), a user sees and decides only
 * the states in their scope, and personal data in the payloads is masked unless the caller may view it in full.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HeldRecordApiTest {
    @TestConfiguration
    static class Database {
        @Bean
        @Primary
        PostgresConnectionFactory heldApiConnections() {
            return TestDatabase.freshMigrated();
        }
    }

    private static final String HEADER = "beneficiary_nm,dob,sex_cd,dist_cd,ration_no,mobile";
    private static final String NAME = "Ramesh Kumar Singh";
    private static final UUID NONE = UUID.fromString("00000000-0000-0000-0000-000000000000");

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgresConnectionFactory connections;

    @Autowired
    HeldRecordRepository repository;

    private final ObjectMapper json = new ObjectMapper();
    private UUID st1;
    private UUID st1Batch;
    private UUID st2;
    private UUID st2Batch;

    @BeforeEach
    void seed() throws Exception {
        if (st1 != null) {
            return;
        }
        st1Batch = batch("ST1");
        st2Batch = batch("ST2");
        st1 = hold(st1Batch, "ST1", "R1");
        st2 = hold(st2Batch, "ST2", "R2");
    }

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
    private final UddpPrincipal reader = user("reader", EnumSet.of(Permission.STEWARD_HELD_VIEW), StateScope.everyState());
    private final UddpPrincipal steward = user("steward", EnumSet.of(Permission.STEWARD_HELD_VIEW,
            Permission.STEWARD_HELD_DECIDE), StateScope.everyState());
    private final UddpPrincipal st1Steward = user("st1.steward", EnumSet.of(Permission.STEWARD_HELD_VIEW,
            Permission.STEWARD_HELD_DECIDE), StateScope.of(List.of("ST1")));
    private final UddpPrincipal admin = user("root", EnumSet.allOf(Permission.class), StateScope.everyState());

    // ---- 403 before 400 -------------------------------------------------------------------------------------------

    @Test
    void everyEndpointRefusesTheCallerBeforeItReadsTheBodyOrTheParameters() throws Exception {
        String bad = "{not json";
        for (var call : decisionCalls(bad)) {
            assertEquals(403, status(call.apply(as(reader))), call + " as a viewer");
            assertEquals(403, status(call.apply(as(nobody))), call + " as nobody");
        }
        assertEquals(403, status(get("/api/v1/held-records?size=abc").with(as(nobody))));
        assertEquals(403, status(get("/api/v1/held-records/groups").with(as(nobody))));
        assertEquals(403, status(get("/api/v1/held-records/bulk-actions?status=NOPE").with(as(nobody))),
                "the permission is checked before the status filter");
        assertEquals(403, status(get("/api/v1/held-records/not-a-uuid").with(as(nobody))));
        assertEquals(400, status(get("/api/v1/held-records?size=abc").with(as(reader))), "allowed, then invalid");
        assertEquals(400, status(post("/api/v1/held-records/" + st1 + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content(bad).with(as(steward))), "allowed, then an unreadable body");
        assertEquals(400, status(post("/api/v1/held-records/" + st1 + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"NOPE\"}").with(as(steward))), "allowed, then an unknown reason code");
    }

    @Test
    void reopeningNeedsAPlatformAdministratorBeforeAnyBodyIsRead() throws Exception {
        var reopen = post("/api/v1/held-records/" + NONE + "/reopen").contentType(MediaType.APPLICATION_JSON)
                .content("{not json");

        assertEquals(403, status(reopen.with(as(steward))), "a steward may decide but not reopen");
        assertEquals(400, status(post("/api/v1/held-records/" + NONE + "/reopen").contentType(MediaType.APPLICATION_JSON)
                .content("{not json").with(as(admin))));
    }

    // ---- scope -----------------------------------------------------------------------------------------------------

    @Test
    void aUserSeesAndDecidesOnlyTheirOwnStatesRecords() throws Exception {
        var all = body(get("/api/v1/held-records?size=100").with(as(steward)));
        var scoped = body(get("/api/v1/held-records?size=100").with(as(st1Steward)));

        assertTrue(ids(all).containsAll(List.of(st1.toString(), st2.toString())));
        assertEquals(List.of(st1.toString()), ids(scoped).stream().filter(id -> id.equals(st1.toString())
                || id.equals(st2.toString())).toList());
        assertEquals(200, status(get("/api/v1/held-records/" + st1).with(as(st1Steward))));
        assertEquals(404, status(get("/api/v1/held-records/" + st2).with(as(st1Steward))), "as if unknown");
        assertEquals(403, status(post("/api/v1/held-records/" + st2 + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"SOURCE_ERROR\"}").with(as(st1Steward))));
        assertEquals(403, status(post("/api/v1/held-records/groups/" + st2Batch + "/RULE_FAILED:r/reject")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"SOURCE_ERROR\"}")
                .with(as(st1Steward))));
        assertFalse(body(get("/api/v1/held-records/groups").with(as(st1Steward))).toString().contains(st2Batch.toString()));
        assertEquals("OPEN", repository.find(st2).orElseThrow().status(), "nothing was decided");
    }

    // ---- masking and audit -----------------------------------------------------------------------------------------

    @Test
    void personalDataIsMaskedUnlessTheCallerMayViewItInFullAndNamesAPurpose() throws Exception {
        String purpose = "Data correction request";

        String viewer = read("/api/v1/held-records/" + st1, reader, purpose);
        String full = read("/api/v1/held-records/" + st1, admin, purpose);
        String withoutPurpose = read("/api/v1/held-records/" + st1, admin, null);

        assertFalse(viewer.contains(NAME), "a viewer without RESIDENT_VIEW_FULL gets masked data, whatever header they send");
        assertFalse(viewer.contains("9876543210"));
        assertTrue(full.contains(NAME), "full view with a configured purpose");
        assertFalse(withoutPurpose.contains(NAME), "no purpose, no full view");
        assertFalse(read("/api/v1/held-records?size=100", reader, null).contains(NAME));
        assertFalse(read("/api/v1/held-records/groups", reader, null).contains(NAME));
    }

    @Test
    void rejectingAndReopeningIsAuditedWithTheActor() throws Exception {
        UUID id = hold(st1Batch, "ST1", "R9");

        assertEquals(200, status(post("/api/v1/held-records/" + id + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"OUT_OF_SCOPE\",\"note\":\"not ours\"}").with(as(steward))));
        assertEquals(200, status(post("/api/v1/held-records/" + id + "/reopen").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"source fixed\"}").with(as(admin))));

        var sql = new Sql(connections);
        assertEquals(1, sql.count("select count(*) from app_audit_event where action='HELD_REJECT' and resource_id=?"
                + " and actor_username='steward'", id.toString()));
        assertEquals(1, sql.count("select count(*) from app_audit_event where action='HELD_REOPEN' and resource_id=?"
                + " and actor_username='root'", id.toString()));
        assertEquals("OPEN", repository.find(id).orElseThrow().status());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** Every POST endpoint with the given body, as a function from the caller to the request. */
    private List<java.util.function.Function<RequestPostProcessor, MockHttpServletRequestBuilder>> decisionCalls(
            String body) {
        List<String> paths = List.of("/api/v1/held-records/" + NONE + "/rectify",
                "/api/v1/held-records/" + NONE + "/release-override", "/api/v1/held-records/" + NONE + "/reject",
                "/api/v1/held-records/groups/" + NONE + "/RULE_FAILED:r/reject",
                "/api/v1/held-records/groups/" + NONE + "/RULE_FAILED:r/reprocess",
                "/api/v1/held-records/bulk-actions/" + NONE + "/approve",
                "/api/v1/held-records/bulk-actions/" + NONE + "/decline");
        return paths.stream().<java.util.function.Function<RequestPostProcessor, MockHttpServletRequestBuilder>>map(
                path -> caller -> post(path).contentType(MediaType.APPLICATION_JSON).content(body).with(caller)).toList();
    }

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void theDetailSuggestsAFixPerReasonWithoutRepeatingValues() throws Exception {
        UUID batch = batch("ST1");
        UUID raw = UUID.randomUUID();
        try (var c = connections.open();
                var s = c.prepareStatement("insert into raw_record(raw_record_id,batch_id,state_code,source_system,"
                        + "raw_payload,source_metadata,state_pack_version,source_schema_version,pipeline_template)"
                        + " values(?,?,'ST1','pds','{}'::jsonb,'{}'::jsonb,'1.0.0','1.0','generic-file-ingest.hpl')")) {
            s.setObject(1, raw);
            s.setObject(2, batch);
            s.executeUpdate();
        }
        var payload = json.createObjectNode().put("name", NAME).put("dateOfBirth", "1980-03-12");
        repository.insert(new HeldRecord(null, batch, raw, "pds", "ST1", null, "1.0.0", List.of(
                new HeldReason("UNREADABLE_DATE", "unreadable date", "dateOfBirth",
                        gov.uddp.core.validation.ValidationIssue.Severity.SEND_TO_STEWARD),
                new HeldReason("RULE_FAILED:dob-not-future", "date is after reference date", "dateOfBirth",
                        gov.uddp.core.validation.ValidationIssue.Severity.ERROR)),
                "OPEN", null, null, null, null, null, null, payload, payload, 0), null);
        UUID id = new Sql(connections).uuid("select held_id from held_record where raw_record_id=?", raw);

        JsonNode detail = json.readTree(read("/api/v1/held-records/" + id, steward, null));
        String suggestion = detail.path("suggestions").path("UNREADABLE_DATE").asText();
        assertTrue(suggestion.contains("YYYY-MM-DD"), detail.toString());
        assertFalse(suggestion.contains("1980"), "the suggestion never repeats the value");
        assertFalse(detail.path("suggestions").has("RULE_FAILED:dob-not-future"), "rule failures have none");
        assertEquals("RULE", detail.path("record").path("reasons").get(1).path("fix").path("kind").asText());
    }

    private String read(String path, UddpPrincipal caller, String purpose) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).with(as(caller));
        if (purpose != null) {
            request.header(PiiMaskingAdvice.PURPOSE_HEADER, purpose);
        }
        return mvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    }

    private static List<String> ids(JsonNode page) {
        var ids = new java.util.ArrayList<String>();
        page.path("items").forEach(item -> ids.add(item.path("heldId").asText()));
        return ids;
    }

    private UUID batch(String state) throws SQLException {
        UUID batch = UUID.randomUUID();
        try (var c = connections.open();
                var s = c.prepareStatement("insert into ingestion_batch(batch_id,state_code,source_system,source_type,"
                        + "source_schema_version,state_pack_version,pipeline_template,status,record_count)"
                        + " values(?,?,'pds','file','1.0','1.0.0','generic-file-ingest.hpl','COMPLETED',10)")) {
            s.setObject(1, batch);
            s.setString(2, state);
            s.executeUpdate();
        }
        return batch;
    }

    private UUID hold(UUID batch, String state, String ration) throws Exception {
        UUID raw = UUID.randomUUID();
        try (var c = connections.open();
                var s = c.prepareStatement("insert into raw_record(raw_record_id,batch_id,state_code,source_system,"
                        + "raw_payload,source_metadata,state_pack_version,source_schema_version,pipeline_template)"
                        + " values(?,?,?,'pds',?::jsonb,'{}'::jsonb,'1.0.0','1.0','generic-file-ingest.hpl')")) {
            s.setObject(1, raw);
            s.setObject(2, batch);
            s.setString(3, state);
            s.setString(4, "{\"csvHeader\":\"" + HEADER + "\",\"rawLine\":\"" + NAME + ",12-03-2099,M,101," + ration
                    + ",9876543210\"}");
            s.executeUpdate();
        }
        var payload = json.createObjectNode().put("name", NAME).put("mobile", "9876543210")
                .put("dateOfBirth", "2099-03-12");
        repository.insert(new HeldRecord(null, batch, raw, "pds", state, null, "1.0.0",
                List.of(new HeldReason("RULE_FAILED:dob-not-future", "date is after reference date", "dateOfBirth",
                        gov.uddp.core.validation.ValidationIssue.Severity.ERROR)),
                "OPEN", null, null, null, null, null, null, payload, payload, 0), null);
        return new Sql(connections).uuid("select held_id from held_record where raw_record_id=?", raw);
    }
}
