package gov.uddp.core.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** M12 read API: overview counts and the State Pack viewer endpoints. */
class OverviewAndStatePackApiTest extends ReadApiFixture {
    @Test
    void overviewTotalsMatchTheSeededPipeline() throws Exception {
        var overview = read("/api/v1/overview");
        var totals = overview.path("totals");
        assertEquals(3, totals.path("states").asInt());
        assertEquals(3, totals.path("batches").asInt());
        assertEquals(7, totals.path("rawRecords").asInt());
        assertEquals(7, totals.path("canonicalResidents").asInt());
        assertEquals(7, totals.path("currentCanonicalResidents").asInt());
        assertEquals(5, totals.path("goldenResidents").asInt());
        assertEquals(0, totals.path("retiredGoldenResidents").asInt());
        assertEquals(1, totals.path("crossStateGoldenResidents").asInt(), "Ram Singh spans three states");
        assertEquals(2, totals.path("reduction").asInt());
        assertEquals(28.6, totals.path("reductionPercent").asDouble(), 0.001);
        assertEquals(3, totals.path("matchDecisions").path("autoMatch").asInt());
        assertEquals(1, totals.path("matchDecisions").path("possibleMatch").asInt());
        assertEquals(21 - 4, totals.path("matchDecisions").path("noMatch").asInt());
        assertEquals(3, totals.path("onboardingRuns").asInt());
        assertEquals(3, totals.path("matchingRuns").asInt());

        long goldenAcrossStates = 0;
        for (var state : overview.path("states")) {
            String code = state.path("stateCode").asText();
            assertEquals(sql().count("select count(*) from raw_record where state_code = ?", code),
                    state.path("rawRecords").asLong(), code);
            assertTrue(state.path("packId").isTextual(), code + " has its pack");
            // Each state holds one of the three matched records, so it touches two of the three AUTO_MATCH pairs.
            assertEquals(2, state.path("matchDecisions").path("autoMatch").asInt(), code);
            goldenAcrossStates += state.path("goldenResidents").asLong();
        }
        assertEquals(3, overview.path("states").size());
        assertEquals(7, goldenAcrossStates, "a cross-state identity counts in every state it has members in");
    }

    @Test
    void statePackListAndDetailServeTheLoadedPacksWithLifecycle() throws Exception {
        var list = read("/api/v1/states");
        assertEquals(3, list.size());
        List<String> ids = new ArrayList<>();
        list.forEach(pack -> {
            ids.add(pack.path("packId").asText());
            assertEquals("ACTIVE", pack.path("lifecycleStatus").asText());
            assertEquals(1, pack.path("sourceCount").asInt());
        });
        assertEquals(ids.stream().sorted().toList(), ids, "ordered by pack ID");

        String packId = firstPackId();
        String code = stateCodeOf(packId);
        var detail = read("/api/v1/states/{state}", packId);
        assertEquals(code, detail.path("stateCode").asText());
        assertEquals(read("/api/v1/states/{state}", code.toLowerCase()), detail, "state code works too");
        var mapping = detail.path("mappings").get(0);
        assertEquals("Resident", mapping.path("entity").asText());
        assertTrue(mapping.path("rules").size() >= 5);
        var nameRule = rule(mapping, "name");
        assertTrue(nameRule.path("required").asBoolean());
        assertTrue(rule(mapping, "gender").path("crosswalk").isTextual());
        var gender = crosswalk(detail, rule(mapping, "gender").path("crosswalk").asText());
        assertTrue(gender.path("entries").size() >= 2);
        assertTrue(gender.path("entries").get(0).path("sourceValue").isTextual());
        assertTrue(detail.path("validationRules").size() >= 2);
        assertEquals(0.95, detail.path("matching").get(0).path("autoMatch").asDouble(), 1e-9);
        assertEquals(0.75, detail.path("matching").get(0).path("possibleMatch").asDouble(), 1e-9);
        assertTrue(detail.path("survivorship").get(0).path("fieldPriority").size() >= 1);
        var version = detail.path("versions").get(0);
        assertEquals("ACTIVE", version.path("lifecycleStatus").asText());
        assertTrue(version.path("loaded").asBoolean());
        assertTrue(version.path("activatedAt").isTextual());

        mvc.perform(get("/api/v1/states/{state}", "no-such-pack"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("State Pack not found: no-such-pack"))
                .andExpect(jsonPath("$.path").value("/api/v1/states/no-such-pack"));
    }

    private static com.fasterxml.jackson.databind.JsonNode rule(com.fasterxml.jackson.databind.JsonNode mapping, String target) {
        for (var rule : mapping.path("rules")) {
            if (rule.path("target").asText().equals(target)) {
                return rule;
            }
        }
        throw new AssertionError("No mapping rule for " + target);
    }

    private static com.fasterxml.jackson.databind.JsonNode crosswalk(com.fasterxml.jackson.databind.JsonNode pack, String id) {
        for (var crosswalk : pack.path("crosswalks")) {
            if (crosswalk.path("id").asText().equals(id)) {
                return crosswalk;
            }
        }
        throw new AssertionError("No crosswalk " + id);
    }
}
