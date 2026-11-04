package gov.uddp.core.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.security.Permission;
import gov.uddp.core.security.UddpPrincipal;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestSecurity;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * P1 Part 2, R1b: a user assigned only DEPT-1 (holding every permission, so only the scope limits them) sees only the
 * records of DEPT-1's Source Pack (source code ST1) and nothing from the other departments' sources (ST2, ST3) through
 * any read endpoint: lists, counts and reports never contain another department's records or source codes, details
 * of another department's records answer 404 as if unknown, the Ram Singh identity spanning departments is visible
 * with only its in-scope members and with values from other departments withheld, and writes on another department's
 * source are refused.
 */
class DepartmentScopeApiTest extends ReadApiFixture {
    static final UddpPrincipal ST1_ONLY = TestSecurity.principal("dept1.only", Set.of("PLATFORM_ADMIN"),
            EnumSet.allOf(Permission.class), List.of("DEPT-1"));
    static final UddpPrincipal DEPT2_AND_DEPT3 = TestSecurity.principal("dept2.dept3", Set.of("PLATFORM_ADMIN"),
            EnumSet.allOf(Permission.class), List.of("DEPT-2", "dept-3"));
    static final UddpPrincipal NO_SOURCES = TestSecurity.principal("dept.without.sources", Set.of("PLATFORM_ADMIN"),
            EnumSet.allOf(Permission.class), List.of("DEPT-UNKNOWN"));

    @Test
    void listsCountsAndReportsHoldNothingFromOtherStates() throws Exception {
        List<String> foreign = foreignIds();
        assertFalse(foreign.isEmpty());
        UUID run = sql().uuid("select run_id from matching_run order by started_at desc limit 1");
        List<String> paths = List.of("/api/v1/overview", "/api/v1/states", "/api/v1/batches?size=200",
                "/api/v1/dq/rules", "/api/v1/dq/findings?size=200", "/api/v1/canonical-residents?size=200",
                "/api/v1/matching-runs", "/api/v1/matching-runs/" + run, "/api/v1/matching-runs/" + run + "/pairs?size=200",
                "/api/v1/golden-residents?size=200&status=ALL", "/api/v1/families?size=200&status=ALL",
                "/api/v1/onboarding-runs?size=200", "/api/v1/benefits?size=200", "/api/v1/schemes",
                "/api/v1/reports/duplicate-benefits", "/api/v1/reports/eligible-not-enrolled",
                "/api/v1/audit-events?size=200", "/api/v1/audit-events/export",
                "/api/v1/stewardship/reviews?status=ALL&size=200", "/api/v1/stewardship/decisions?size=200",
                "/api/studio/states", "/api/studio/portability");
        List<String> leaks = new ArrayList<>();
        for (String path : paths) {
            String body = get(path, ST1_ONLY, 200);
            for (String id : foreign) {
                if (body.contains(id)) {
                    leaks.add(path + " contains " + id);
                }
            }
            if (body.contains("\"ST2\"") || body.contains("\"ST3\"")) {
                leaks.add(path + " names another state");
            }
        }
        assertEquals(List.of(), leaks);

        JsonNode overview = json.readTree(get("/api/v1/overview", ST1_ONLY, 200));
        assertEquals(1, overview.path("totals").path("states").asInt());
        assertEquals("ST1", overview.path("states").get(0).path("stateCode").asText());
        assertEquals(sql().count("select count(*) from raw_record where state_code = 'ST1'"),
                overview.path("totals").path("rawRecords").asLong());
        JsonNode admin = json.readTree(get("/api/v1/overview", TestSecurity.PLATFORM_ADMIN, 200));
        assertEquals(3, admin.path("totals").path("states").asInt(), "a user with every state sees all three");
    }

    @Test
    void detailsOfAnotherStatesRecordsAreNotFound() throws Exception {
        Sql sql = sql();
        UUID canonical = sql.uuid("select canonical_resident_id from canonical_resident where state_code = 'ST2' limit 1");
        UUID raw = sql.uuid("select raw_record_id from raw_record where state_code = 'ST2' limit 1");
        UUID batch = sql.uuid("select batch_id from ingestion_batch where state_code = 'ST2' limit 1");
        UUID run = sql.uuid("select onboarding_run_id from onboarding_run where state_code = 'ST2' limit 1");
        UUID family = sql.uuid("select golden_family_id from golden_family where state_code = 'ST2' limit 1");
        UUID benefit = sql.uuid("select benefit_id from benefit_state where state_code = 'ST2' limit 1");
        UUID golden = sql.uuid("select g.golden_resident_id from golden_resident g where not exists (select 1"
                + " from golden_resident_membership m join canonical_resident c on c.canonical_resident_id"
                + " = m.canonical_resident_id where m.golden_resident_id = g.golden_resident_id and c.state_code = 'ST1')"
                + " limit 1");
        for (String path : List.of("/api/v1/canonical-residents/" + canonical, "/api/v1/raw-records/" + raw,
                "/api/v1/batches/" + batch, "/api/v1/onboarding-runs/" + run, "/api/v1/families/" + family,
                "/api/v1/families/" + family + "/history", "/api/v1/benefits/" + benefit,
                "/api/v1/golden-residents/" + golden, "/api/v1/golden-residents/" + golden + "/history",
                "/api/v1/golden-residents/" + golden + "/families", "/api/v1/golden-residents/" + golden + "/benefits",
                "/api/v1/states/ST2", "/api/v1/states/state-2", "/api/onboarding/runs/" + run,
                "/api/onboarding/states/ST2/runs")) {
            get(path, ST1_ONLY, 404);
            get(path, TestSecurity.PLATFORM_ADMIN, 200);
        }
        // The views that are not state-scoped need every state; batch-level legacy endpoints check the batch.
        for (String path : List.of("/api/golden-residents", "/api/golden-residents/" + golden, "/api/state-packs",
                "/api/dq/batches/" + batch)) {
            get(path, ST1_ONLY, 403);
        }
    }

    @Test
    void theCrossStateIdentityShowsOnlyItsMemberInScopeAndWithholdsOtherStatesValues() throws Exception {
        UUID ram = sql().uuid("select distinct m.golden_resident_id from golden_resident_membership m"
                + " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id"
                + " where m.effective_to is null and c.canonical_payload->>'name' = 'Ram Singh' limit 1");
        JsonNode full = json.readTree(get("/api/v1/golden-residents/" + ram, TestSecurity.PLATFORM_ADMIN, 200));
        assertEquals(3, full.path("members").size());
        assertEquals(0, full.path("hiddenMemberCount").asInt());

        JsonNode scoped = json.readTree(get("/api/v1/golden-residents/" + ram, ST1_ONLY, 200));
        assertEquals(1, scoped.path("members").size());
        assertEquals("ST1", scoped.path("members").get(0).path("stateCode").asText());
        assertEquals(2, scoped.path("hiddenMemberCount").asInt());
        assertEquals("[\"ST1\"]", scoped.path("stateCodes").toString());
        for (JsonNode field : scoped.path("fields")) {
            if (field.path("withheld").asBoolean()) {
                assertTrue(field.path("value").isNull(), field.toString());
                assertTrue(scoped.path("withheldFields").toString().contains(field.path("fieldPath").asText()));
            } else {
                assertEquals("ST1", field.path("selectedStateCode").asText(), field.toString());
            }
            for (JsonNode contributor : field.path("contributors")) {
                assertEquals("ST1", contributor.path("stateCode").asText());
            }
        }
        JsonNode two = json.readTree(get("/api/v1/golden-residents/" + ram, DEPT2_AND_DEPT3, 200));
        assertEquals(2, two.path("members").size());
        assertEquals(1, two.path("hiddenMemberCount").asInt());
        assertEquals("[\"ST2\",\"ST3\"]", two.path("stateCodes").toString());
        for (JsonNode member : two.path("members")) {
            assertFalse(member.path("stateCode").asText().equals("ST1"), member.toString());
        }

        String history = get("/api/v1/golden-residents/" + ram + "/history", ST1_ONLY, 200);
        assertFalse(history.contains("\"ST2\"") || history.contains("\"ST3\""), history);
        JsonNode benefits = json.readTree(get("/api/v1/golden-residents/" + ram + "/benefits", ST1_ONLY, 200));
        assertEquals("[\"ST1\"]", benefits.path("states").toString());
    }

    @Test
    void aDepartmentOwningNoSourceSeesNoRecords() throws Exception {
        UUID ram = sql().uuid("select distinct m.golden_resident_id from golden_resident_membership m"
                + " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id"
                + " where m.effective_to is null and c.canonical_payload->>'name' = 'Ram Singh' limit 1");
        for (String path : List.of("/api/v1/canonical-residents?size=200", "/api/v1/golden-residents?size=200&status=ALL",
                "/api/v1/families?size=200&status=ALL", "/api/v1/benefits?size=200")) {
            assertEquals(0, json.readTree(get(path, NO_SOURCES, 200)).path("items").size(), path);
        }
        get("/api/v1/golden-residents/" + ram, NO_SOURCES, 404);
    }

    @Test
    void writesOnAnotherStateAreRefused() throws Exception {
        UUID batch = sql().uuid("select batch_id from ingestion_batch where state_code = 'ST2' limit 1");
        mvc.perform(MockMvcRequestBuilders.post("/api/canonical/residents/process/batches/" + batch)
                        .with(TestSecurity.as(ST1_ONLY)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post("/api/studio/drafts").with(TestSecurity.as(ST1_ONLY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packId\":\"other-pack\",\"stateCode\":\"ST2\",\"displayName\":\"Other\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post("/api/onboarding/runs").with(TestSecurity.as(ST1_ONLY))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packId\":\"state-2\",\"version\":\"1.0.0\",\"sourceSystem\":\"pension\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.post("/api/golden-residents/build").with(TestSecurity.as(ST1_ONLY))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    /** IDs of records that belong only to ST2 or ST3. */
    private List<String> foreignIds() {
        Sql sql = sql();
        List<String> ids = new ArrayList<>();
        for (String query : List.of(
                "select canonical_resident_id from canonical_resident where state_code <> 'ST1'",
                "select raw_record_id from raw_record where state_code <> 'ST1'",
                "select batch_id from ingestion_batch where state_code <> 'ST1'",
                "select onboarding_run_id from onboarding_run where state_code <> 'ST1'",
                "select golden_family_id from golden_family where state_code <> 'ST1'",
                "select benefit_id from benefit_state where state_code <> 'ST1'",
                "select g.golden_resident_id from golden_resident g where not exists (select 1"
                        + " from golden_resident_membership m join canonical_resident c"
                        + " on c.canonical_resident_id = m.canonical_resident_id"
                        + " where m.golden_resident_id = g.golden_resident_id and c.state_code = 'ST1')")) {
            sql.column(query, UUID.class).forEach(id -> ids.add(id.toString()));
        }
        return ids;
    }

    private String get(String path, UddpPrincipal principal, int status) throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.get(path).with(TestSecurity.as(principal))).andReturn();
        assertEquals(status, result.getResponse().getStatus(), path + " as " + principal.username() + ": "
                + result.getResponse().getContentAsString());
        return result.getResponse().getContentAsString();
    }
}
