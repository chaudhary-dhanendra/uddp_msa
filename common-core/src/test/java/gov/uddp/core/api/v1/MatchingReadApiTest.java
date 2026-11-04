package gov.uddp.core.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M12 read API: matching runs, clusters and pair evidence by decision. */
class MatchingReadApiTest extends ReadApiFixture {
    @Test
    void runsArePagedNewestFirstWithDecisionCounts() throws Exception {
        var runs = read("/api/v1/matching-runs");
        assertEquals(3, runs.path("totalItems").asInt());
        var latest = runs.path("items").get(0);
        assertEquals("COMPLETED", latest.path("status").asText());
        assertEquals("FULL", latest.path("mode").asText());
        assertEquals(7, latest.path("recordsConsidered").asInt());
        assertEquals(3, latest.path("pairs").path("autoMatch").asInt());
        assertEquals(1, latest.path("pairs").path("possibleMatch").asInt());
        assertEquals(17, latest.path("pairs").path("noMatch").asInt());
        assertEquals(5, latest.path("clusters").asInt());
        assertEquals(1, latest.path("multiMemberClusters").asInt());
        assertEquals(0.95, latest.path("autoMatchThreshold").asDouble(), 1e-9);
        assertEquals(1, read("/api/v1/matching-runs?size=1&page=2").path("items").size());
        assertEquals(0, read("/api/v1/matching-runs?mode=incremental").path("totalItems").asInt());

        var detail = read("/api/v1/matching-runs/{id}", latest.path("runId").asText());
        assertEquals(1, detail.path("clusters").size());
        assertEquals(3, detail.path("clusters").get(0).path("members").size());
        assertEquals(1, detail.path("goldenBuilds").size());
    }

    @Test
    void pairsFilterByDecisionAndCanonicalIdAndOpenSideBySide() throws Exception {
        String run = read("/api/v1/matching-runs").path("items").get(0).path("runId").asText();
        var auto = read("/api/v1/matching-runs/{r}/pairs?decision=AUTO_MATCH", run);
        assertEquals(3, auto.path("totalItems").asInt());
        auto.path("items").forEach(pair -> assertEquals("Ram Singh", pair.path("nameA").asText()));
        readAsProgramUser(gov.uddp.core.security.Permission.MATCH_EVIDENCE_VIEW, "/api/v1/matching-runs/{r}/pairs?decision=AUTO_MATCH", run)
                .path("items").forEach(pair -> assertEquals("Ram S.", pair.path("nameA").asText(),
                        "a PROGRAM_USER sees masked names"));
        var possible = read("/api/v1/matching-runs/{r}/pairs?decision=possible_match", run);
        assertEquals(1, possible.path("totalItems").asInt());
        var none = read("/api/v1/matching-runs/{r}/pairs?decision=NO_MATCH&size=5", run);
        assertEquals(17, none.path("totalItems").asInt());
        assertEquals(4, none.path("totalPages").asInt());
        var probabilities = read("/api/v1/matching-runs/{r}/pairs", run).path("items").findValues("matchProbability");
        assertTrue(probabilities.get(0).asDouble() >= probabilities.get(probabilities.size() - 1).asDouble());

        var pair = possible.path("items").get(0);
        String a = pair.path("canonicalRecordA").asText();
        String b = pair.path("canonicalRecordB").asText();
        assertEquals(6, read("/api/v1/matching-runs/{r}/pairs?canonicalId={c}", run, a).path("totalItems").asInt(),
                "every other record");
        var detail = read("/api/v1/matching-runs/{r}/pairs/{a}/{b}", run, a, b);
        assertEquals(detail, read("/api/v1/matching-runs/{r}/pairs/{a}/{b}", run, b, a), "either order");
        assertEquals("POSSIBLE_MATCH", detail.path("pair").path("decision").asText());
        assertEquals(a, detail.path("recordA").path("canonicalResidentId").asText());
        assertEquals(0.75, detail.path("possibleMatchThreshold").asDouble(), 1e-9);
        var comparisons = detail.path("comparisons");
        assertEquals(5, comparisons.size());
        assertEquals("name", comparisons.get(0).path("field").asText());
        assertEquals("DIFFERENT", comparisons.get(0).path("outcome").asText());
        assertEquals("CANONICAL", comparisons.get(0).path("basis").asText(), "the fixture stores no field evidence");
    }

    @Test
    void unknownRunsPairsAndBadDecisionsAreRejected() throws Exception {
        String run = read("/api/v1/matching-runs").path("items").get(0).path("runId").asText();
        UUID missing = UUID.randomUUID();
        mvc.perform(get("/api/v1/matching-runs/{r}", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Matching run does not exist: " + missing));
        mvc.perform(get("/api/v1/matching-runs/{r}/pairs", missing)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/matching-runs/{r}/pairs/{a}/{b}", run, missing, UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/matching-runs/{r}/pairs", run).param("decision", "MAYBE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("decision must be one of AUTO_MATCH, POSSIBLE_MATCH, NO_MATCH"));
        mvc.perform(get("/api/v1/matching-runs/{r}/pairs", run).param("sort", "evidence"))
                .andExpect(status().isBadRequest());
    }
}
