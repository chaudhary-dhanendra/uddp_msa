package gov.uddp.core.api;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.onboarding.HopRunner;
import gov.uddp.core.onboarding.PythonMatchingRunner;
import gov.uddp.core.testsupport.HopShapedRawLoader;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Part 3: the onboarding REST contract through the full Spring stack against a disposable database. Only the Hop
 * and Python process boundaries are replaced (by what they write); the service, lifecycle and persistence are real.
 */
@SpringBootTest(properties = "uddp.state-pack-directory=../m10-state-packs")
@AutoConfigureMockMvc
class OnboardingControllerTest {
    private static final String START = "{\"packId\":\"state-4\",\"version\":\"1.0.0\",\"sourceSystem\":\"citizen-register\"%s}";
    /** Generic failure hook for the matching boundary; null means matching succeeds. */
    static volatile String failMatchingWith;

    @TestConfiguration
    static class Boundaries {
        @Bean
        @Primary
        PostgresConnectionFactory connections() {
            return TestDatabase.freshMigrated();
        }

        @Bean
        @Primary
        HopRunner fixtureHop(PostgresConnectionFactory connections) {
            var raw = new HopShapedRawLoader(connections, SampleData.REPOSITORY_ROOT);
            return (source, stateCode, version, batch) ->
                    new HopRunner.Result(0, "landed " + raw.landInto(source, stateCode, version, batch));
        }

        @Bean
        @Primary
        PythonMatchingRunner fixtureMatching(PostgresConnectionFactory connections) {
            return new PythonMatchingRunner(new ObjectMapper()) {
                @Override
                public UUID run() {
                    if (failMatchingWith != null) {
                        throw new IllegalStateException(failMatchingWith);
                    }
                    return MatchingRunFixture.completed(connections, List.of());
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void activePack() throws Exception {
        failMatchingWith = null;
        mvc.perform(post("/api/onboarding/state-packs/state-4/register")).andExpect(status().isCreated());
        // Idempotent setup across tests sharing this context's database: transitions are allowed only once.
        mvc.perform(post("/api/onboarding/state-packs/state-4/1.0.0/transitions/VALIDATED"));
        mvc.perform(post("/api/onboarding/state-packs/state-4/1.0.0/transitions/ACTIVE"));
    }

    @AfterEach
    void clearHook() {
        failMatchingWith = null;
    }

    @Test
    void fullModeStartExecutesEveryStageAndCompletes() throws Exception {
        JsonNode started = body(start(",\"mode\":\"FULL\"").andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("FULL"))
                .andExpect(jsonPath("$.status").value("COMPLETED")));
        String run = started.path("runId").asText();

        mvc.perform(get("/api/onboarding/runs/{id}", run))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(run))
                .andExpect(jsonPath("$.stateCode").value("ST4"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currentStage").value("PUBLISHED"))
                .andExpect(jsonPath("$.stages.length()").value(8))
                .andExpect(jsonPath("$.stages[0].stage").value("PACK_VALIDATED"))
                .andExpect(jsonPath("$.stages[7].stage").value("PUBLISHED"))
                .andExpect(jsonPath("$.stages[2].outputCount").value(2))
                .andExpect(jsonPath("$.stages[*].status").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("COMPLETED"))))
                .andExpect(jsonPath("$.stages[*].attempt").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is(1))));
    }

    @Test
    void runDetailReportsChunkProgressAndTheRejectedRowsDownloadIsACsv() throws Exception {
        String run = body(start(",\"mode\":\"FULL\"").andExpect(status().isCreated())).path("runId").asText();

        mvc.perform(get("/api/onboarding/runs/{id}", run))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processing.totalRows").value(2))
                .andExpect(jsonPath("$.processing.rowsProcessed").value(2))
                .andExpect(jsonPath("$.processing.rejectedRows").value(0))
                .andExpect(jsonPath("$.processing.chunks.length()").value(1))
                .andExpect(jsonPath("$.processing.chunks[0].chunk").value(1))
                .andExpect(jsonPath("$.processing.chunks[0].rows").value(2))
                .andExpect(jsonPath("$.processing.rowsPerSecond").isNumber());
        var csv = mvc.perform(get("/api/onboarding/runs/{id}/rejected-rows.csv", run))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(csv))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", containsString("rejected-rows-" + run)))
                .andExpect(content().string("rawRecordId,reason,rejectedAt,originalRow,sourceLine\r\n"));
    }

    @Test
    void aSourceWithoutALandingFolderHasNoLandingEndpoints() throws Exception {
        mvc.perform(get("/api/onboarding/state-packs/state-4/sources/citizen-register/landing"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/onboarding/state-packs/state-4/sources/citizen-register/landing/start"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stepModeStartThenNamedStageAndNextExecuteOneStageEach() throws Exception {
        String run = body(start("").andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("STEP"))
                .andExpect(jsonPath("$.status").value("RUNNING"))).path("runId").asText();
        mvc.perform(get("/api/onboarding/runs/{id}", run)).andExpect(jsonPath("$.stages.length()").value(0));

        mvc.perform(post("/api/onboarding/runs/{id}/stages/PACK_VALIDATED", run)).andExpect(status().isNoContent());
        mvc.perform(post("/api/onboarding/runs/{id}/next", run)).andExpect(status().isNoContent());

        mvc.perform(get("/api/onboarding/runs/{id}", run))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.currentStage").value("INGESTED"))
                .andExpect(jsonPath("$.stages.length()").value(2))
                .andExpect(jsonPath("$.stages[0].stage").value("PACK_VALIDATED"))
                .andExpect(jsonPath("$.stages[1].stage").value("SOURCE_TESTED"));
    }

    @Test
    void runningAStageOutOfOrderIsRejectedWithoutExecutingAnything() throws Exception {
        String run = body(start("").andExpect(status().isCreated())).path("runId").asText();
        for (String stage : List.of("PACK_VALIDATED", "SOURCE_TESTED", "INGESTED")) {
            mvc.perform(post("/api/onboarding/runs/{id}/stages/{stage}", run, stage)).andExpect(status().isNoContent());
        }

        mvc.perform(post("/api/onboarding/runs/{id}/stages/MATCHED", run))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value("Invalid stage order: MATCHED requested but the next stage is MAPPED"));

        mvc.perform(get("/api/onboarding/runs/{id}", run))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.currentStage").value("MAPPED"))
                .andExpect(jsonPath("$.stages.length()").value(3));
    }

    @Test
    void historyListsTheStatesRunsNewestFirstByPackIdOrStateCode() throws Exception {
        String older = body(start("").andExpect(status().isCreated())).path("runId").asText();
        String newer = body(start("").andExpect(status().isCreated())).path("runId").asText();

        for (String state : List.of("state-4", "ST4")) {
            JsonNode runs = body(mvc.perform(get("/api/onboarding/states/{state}/runs", state)).andExpect(status().isOk()));
            List<String> ids = new java.util.ArrayList<>();
            runs.forEach(run -> {
                assertEquals("ST4", run.path("stateCode").asText());
                ids.add(run.path("runId").asText());
            });
            assertTrue(ids.contains(older) && ids.contains(newer), ids.toString());
            assertTrue(ids.indexOf(newer) < ids.indexOf(older), "newest first: " + ids);
        }
        mvc.perform(get("/api/onboarding/states/{state}/runs", "state-99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Unknown State Pack: state-99"));
    }

    @Test
    void failedStageReturnsItsRunAndResumeRetriesIt() throws Exception {
        failMatchingWith = "injected matching failure for test";
        String run = body(start(",\"mode\":\"FULL\"")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("STAGE_FAILED"))
                .andExpect(jsonPath("$.stage").value("MATCHED"))
                .andExpect(jsonPath("$.message").value("injected matching failure for test"))).path("runId").asText();
        mvc.perform(get("/api/onboarding/runs/{id}", run))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureMessage").value("injected matching failure for test"))
                .andExpect(jsonPath("$.stages[5].status").value("FAILED"));

        failMatchingWith = null;
        mvc.perform(post("/api/onboarding/runs/{id}/resume", run)).andExpect(status().isNoContent());
        mvc.perform(post("/api/onboarding/runs/{id}/stages/GOLDEN_BUILT", run)).andExpect(status().isNoContent());
        mvc.perform(post("/api/onboarding/runs/{id}/stages/PUBLISHED", run)).andExpect(status().isNoContent());

        mvc.perform(get("/api/onboarding/runs/{id}", run))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.stages.length()").value(9))
                .andExpect(jsonPath("$.stages[6].stage").value("MATCHED"))
                .andExpect(jsonPath("$.stages[6].attempt").value(2))
                .andExpect(jsonPath("$.stages[6].status").value("COMPLETED"));
        mvc.perform(post("/api/onboarding/runs/{id}/resume", run))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only a failed onboarding run can be resumed"));
    }

    @Test
    void unknownPackSourceAndMalformedStartRequestsAreRejected() throws Exception {
        mvc.perform(post("/api/onboarding/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packId\":\"state-99\",\"version\":\"1.0.0\",\"sourceSystem\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Unknown State Pack: state-99"));
        mvc.perform(post("/api/onboarding/state-packs/state-99/register"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Unknown State Pack: state-99"));
        mvc.perform(post("/api/onboarding/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packId\":\"state-4\",\"version\":\"1.0.0\",\"sourceSystem\":\"no-such-source\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown State Pack source: no-such-source"));
        mvc.perform(post("/api/onboarding/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packId\":\"state-4\",\"version\":\"9.9.9\",\"sourceSystem\":\"citizen-register\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("must be ACTIVE")));
        mvc.perform(post("/api/onboarding/runs").contentType(MediaType.APPLICATION_JSON).content("{\"packId\":\"state-4\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("packId, version and sourceSystem are required"));
        mvc.perform(post("/api/onboarding/runs").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void unknownOrMalformedRunReferencesAreRejected() throws Exception {
        UUID missing = UUID.randomUUID();
        mvc.perform(post("/api/onboarding/runs/{id}/resume", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Unknown onboarding run: " + missing));
        mvc.perform(get("/api/onboarding/runs/{id}", missing)).andExpect(status().isNotFound());
        mvc.perform(post("/api/onboarding/runs/{id}/next", missing)).andExpect(status().isNotFound());
        mvc.perform(post("/api/onboarding/runs/{id}/stages/MAPPED", missing)).andExpect(status().isNotFound());
        mvc.perform(get("/api/onboarding/runs/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for runId: not-a-uuid"));

        String run = body(start("").andExpect(status().isCreated())).path("runId").asText();
        mvc.perform(post("/api/onboarding/runs/{id}/stages/NOT_A_STAGE", run))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for stage: NOT_A_STAGE"));
        mvc.perform(post("/api/onboarding/runs/{id}/resume", run))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only a failed onboarding run can be resumed"));
    }

    private ResultActions start(String extra) throws Exception {
        return mvc.perform(post("/api/onboarding/runs").contentType(MediaType.APPLICATION_JSON)
                .content(START.formatted(extra)));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
