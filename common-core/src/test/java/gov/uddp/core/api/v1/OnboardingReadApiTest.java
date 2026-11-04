package gov.uddp.core.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M12 read API: onboarding runs across states and their stage timelines. */
class OnboardingReadApiTest extends ReadApiFixture {
    @Test
    void runsAcrossStatesAreListedNewestFirstAndFiltered() throws Exception {
        var runs = read("/api/v1/onboarding-runs");
        assertEquals(3, runs.path("totalItems").asInt());
        var newest = runs.path("items").get(0);
        var oldest = runs.path("items").get(2);
        assertTrue(newest.path("startedAt").asText().compareTo(oldest.path("startedAt").asText()) >= 0);
        runs.path("items").forEach(run -> {
            assertEquals("COMPLETED", run.path("status").asText());
            assertEquals("PUBLISHED", run.path("currentStage").asText());
            assertEquals(8, run.path("completedStages").asInt());
            assertEquals(8, run.path("totalStages").asInt());
            assertEquals(8, run.path("stageExecutions").asInt());
            assertTrue(run.path("durationMs").isNumber());
            assertTrue(run.path("packId").isTextual());
        });
        String packId = firstPackId();
        assertEquals(1, read("/api/v1/onboarding-runs?state={s}", packId).path("totalItems").asInt());
        assertEquals(1, read("/api/v1/onboarding-runs?state={s}", stateCodeOf(packId)).path("totalItems").asInt());
        assertEquals(3, read("/api/v1/onboarding-runs?status=completed").path("totalItems").asInt());
        assertEquals(0, read("/api/v1/onboarding-runs?status=FAILED").path("totalItems").asInt());
        assertEquals(2, read("/api/v1/onboarding-runs?size=1&page=1&sort=stateCode,asc").path("page").asInt() + 1);
        mvc.perform(get("/api/v1/onboarding-runs").param("status", "LOST"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("status must be RUNNING, FAILED or COMPLETED"));
    }

    @Test
    void detailIsAStageTimelineWithCountsTimingsAndActions() throws Exception {
        String id = read("/api/v1/onboarding-runs").path("items").get(0).path("runId").asText();
        var detail = read("/api/v1/onboarding-runs/{id}", id);
        assertEquals(id, detail.path("run").path("runId").asText());
        var timeline = detail.path("timeline");
        assertEquals(8, timeline.size());
        assertEquals("PACK_VALIDATED", timeline.get(0).path("stage").asText());
        assertEquals(1, timeline.get(0).path("order").asInt());
        assertEquals("PUBLISHED", timeline.get(7).path("stage").asText());
        timeline.forEach(entry -> {
            assertEquals("COMPLETED", entry.path("status").asText());
            assertEquals(1, entry.path("attempts").asInt());
            assertTrue(entry.path("latest").path("durationMs").isNumber());
        });
        var ingested = timeline.get(2).path("latest");
        assertTrue(ingested.path("ingestionBatchId").isTextual());
        assertEquals(ingested.path("inputCount"), ingested.path("outputCount"));
        assertTrue(timeline.get(5).path("latest").path("matchingRunId").isTextual());
        assertEquals(8, detail.path("executions").size());
        assertEquals(ingested.path("ingestionBatchId").asText(), detail.path("processingRejections").path("batchId").asText());
        assertEquals(0, detail.path("processingRejections").path("count").asInt());
        assertFalse(detail.path("actions").path("canRunNextStage").asBoolean());
        assertFalse(detail.path("actions").path("canResume").asBoolean());

        UUID missing = UUID.randomUUID();
        mvc.perform(get("/api/v1/onboarding-runs/{id}", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Onboarding run does not exist: " + missing));
    }
}
