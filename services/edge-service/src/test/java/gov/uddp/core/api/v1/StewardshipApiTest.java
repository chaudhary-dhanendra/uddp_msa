package gov.uddp.core.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * M13 API: the review queue, accepting a task, splitting and merging through REST, the decision log, and the error
 * contract. Writes, so it runs in its own context and database (the property only separates the context).
 */
@TestPropertySource(properties = "uddp.test.context=stewardship-api")
// Direct decisions (maker-checker off); the proposal workflow is StewardshipProposalTest.
@org.springframework.test.context.TestPropertySource(properties = "uddp.stewardship.maker-checker=false")
class StewardshipApiTest extends ReadApiFixture {
    @Test
    void reviewDecideSplitAndMergeThroughTheApi() throws Exception {
        var queue = read("/api/v1/stewardship/reviews");
        assertEquals(1, queue.path("totalItems").asInt());
        var task = queue.path("items").get(0);
        assertEquals("OPEN", task.path("status").asText());
        assertEquals(List.of("Sita Singh", "Sunita Devi"), List.of(task.path("nameA").asText(), task.path("nameB").asText())
                .stream().sorted().toList());
        var masked = readAsProgramUser(gov.uddp.core.security.Permission.STEWARDSHIP_VIEW, "/api/v1/stewardship/reviews").path("items").get(0);
        assertEquals(List.of("Sita S.", "Sunita D."), List.of(masked.path("nameA").asText(),
                masked.path("nameB").asText()).stream().sorted().toList(), "a PROGRAM_USER sees masked names");
        assertFalse(task.path("linkedNow").asBoolean());
        String taskId = task.path("reviewTaskId").asText();
        var detail = read("/api/v1/stewardship/reviews/{id}", taskId);
        assertEquals("POSSIBLE_MATCH", detail.path("matchDecision").asText());
        assertTrue(detail.path("evidence").isObject());
        assertEquals(task.path("nameA").asText(), detail.path("recordA").path("name").asText());

        var accepted = write("/api/stewardship/reviews/" + taskId + "/decision",
                Map.of("decision", "ACCEPT", "reviewer", "Asha", "reason", "Same person"));
        assertEquals("ACCEPT_MATCH", accepted.path("action").asText());
        assertEquals(1, accepted.path("goldenBuild").path("merged").asInt());
        assertEquals(4, read("/api/v1/golden-residents").path("totalItems").asInt());
        assertEquals(0, read("/api/v1/stewardship/reviews").path("totalItems").asInt());
        var decided = read("/api/v1/stewardship/reviews?status=DECIDED").path("items").get(0);
        assertEquals("ACCEPT_MATCH", decided.path("decisionAction").asText());
        // Since P1 the reviewer is the signed-in user, not a name in the request body.
        assertEquals("test-admin", decided.path("decidedBy").asText());
        assertTrue(decided.path("linkedNow").asBoolean());
        mvc.perform(post("/api/stewardship/reviews/{id}/decision", taskId).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("decision", "REJECT", "reviewer", "Asha", "reason", "x"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("CONFLICT"));

        UUID merged = UUID.fromString(decided.path("goldenResidentIdA").asText());
        String sunitaKey = decided.path("nameB").asText().equals("Sunita Devi")
                ? decided.path("recordKeyB").asText() : decided.path("recordKeyA").asText();
        var split = write("/api/stewardship/split", Map.of("goldenResidentId", merged.toString(),
                "sourceRecordKeys", List.of(sunitaKey), "reviewer", "Ravi", "reason", "Reviewed again"));
        assertEquals("SPLIT", split.path("action").asText());
        assertEquals(5, read("/api/v1/golden-residents").path("totalItems").asInt());

        var log = read("/api/v1/stewardship/decisions");
        assertEquals(2, log.path("totalItems").asInt());
        assertEquals("SPLIT", log.path("items").get(0).path("action").asText(), "newest first");
        assertEquals("test-admin", log.path("items").get(0).path("reviewer").asText(),
                "the browser-supplied reviewer must not be authoritative");
        var acceptConstraint = log.path("items").get(1).path("constraints").get(0);
        assertEquals("MUST_LINK", acceptConstraint.path("constraintType").asText());
        assertFalse(acceptConstraint.path("effective").asBoolean(), "the split's CANNOT_LINK superseded it");
        assertEquals(2, read("/api/v1/stewardship/decisions?goldenResidentId={id}", merged).path("totalItems").asInt());
        assertEquals(1, read("/api/v1/stewardship/decisions?action=split").path("totalItems").asInt());

        UUID detachedId = sql().goldenOf(sql().canonicalsNamed("Sunita Devi").getFirst());
        var remerged = write("/api/stewardship/merge", Map.of("goldenResidentIds", List.of(merged, detachedId),
                "reviewer", "Asha", "reason", "Confirmed by field visit"));
        assertEquals("MERGE", remerged.path("action").asText());
        assertEquals("test-admin", read("/api/v1/stewardship/decisions").path("items").get(0)
                .path("reviewer").asText(), "merge also records the authenticated reviewer");
        assertEquals(4, read("/api/v1/golden-residents").path("totalItems").asInt());
        var retired = read("/api/v1/golden-residents/{id}", detachedId);
        assertEquals("RETIRED", retired.path("status").asText());
        assertEquals(merged.toString(), retired.path("redirectTo").asText());
    }

    @Test
    void errorContract() throws Exception {
        UUID unknown = UUID.randomUUID();
        mvc.perform(get("/api/v1/stewardship/reviews/{id}", unknown)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/stewardship/reviews").param("status", "LATER")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/stewardship/decisions").param("action", "UNDO")).andExpect(status().isBadRequest());
        expect(post("/api/stewardship/reviews/" + unknown + "/decision"),
                Map.of("decision", "ACCEPT", "reviewer", "Asha", "reason", "x"), 404);
        expect(post("/api/stewardship/merge"), Map.of("goldenResidentIds", List.of(unknown, UUID.randomUUID()),
                "reviewer", "Asha", "reason", "x"), 404);
        expect(post("/api/stewardship/merge"), Map.of("goldenResidentIds", List.of(unknown), "reviewer", "Asha",
                "reason", "x"), 400);
        expect(post("/api/stewardship/split"), Map.of("goldenResidentId", unknown.toString(), "sourceRecordKeys",
                List.of(), "reviewer", "Asha", "reason", "x"), 400);
        // The reviewer is the signed-in user, so a blank body reviewer is ignored and the unknown ID is what fails.
        expect(post("/api/stewardship/split"), Map.of("goldenResidentId", unknown.toString(), "sourceRecordKeys",
                List.of("k"), "reviewer", "", "reason", "x"), 404);
        expect(post("/api/stewardship/split"), Map.of("goldenResidentId", unknown.toString(), "sourceRecordKeys",
                List.of("k"), "reason", ""), 400);
        mvc.perform(post("/api/stewardship/merge").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    private JsonNode write(String path, Object body) throws Exception {
        var result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void expect(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, Object body,
                        int status) throws Exception {
        mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(status));
    }
}
