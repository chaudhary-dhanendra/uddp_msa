package gov.uddp.core.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.golden.GoldenResidentService;
import gov.uddp.core.onboarding.HopRunner;
import gov.uddp.core.onboarding.PythonMatchingRunner;
import gov.uddp.core.testsupport.HopShapedRawLoader;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Read API views of state that changes: a retired (merged) Golden ID that redirects to its survivor, and a failed
 * onboarding run that the UI resumes through the existing write endpoints. Own context and database.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReadApiLifecycleTest {
    static volatile String failMatchingWith;

    @TestConfiguration
    static class Boundaries {
        @Bean
        @Primary
        PostgresConnectionFactory lifecycleConnections() {
            return TestDatabase.freshMigrated();
        }

        @Bean
        @Primary
        HopRunner lifecycleHop(PostgresConnectionFactory connections) {
            var raw = new HopShapedRawLoader(connections, SampleData.REPOSITORY_ROOT);
            return (source, stateCode, version, batch) ->
                    new HopRunner.Result(0, "landed " + raw.landInto(source, stateCode, version, batch));
        }

        @Bean
        @Primary
        PythonMatchingRunner lifecycleMatching(PostgresConnectionFactory connections) {
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

    @Autowired
    PostgresConnectionFactory connections;

    @Autowired
    GoldenResidentService golden;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void aRetiredGoldenIdRedirectsToTheIdentityItMergedInto() throws Exception {
        var sql = new Sql(connections);
        golden.build(SampleData.processedSampleWithMatchingRun(connections));
        var ram = new HashSet<>(sql.canonicalsNamed("Ram Singh"));
        UUID sita = sql.canonicalsNamed("Sita Singh").getFirst();
        UUID ramId = sql.goldenOf(ram.iterator().next());
        UUID sitaId = sql.goldenOf(sita);
        var merged = new HashSet<>(ram);
        merged.add(sita);
        golden.build(MatchingRunFixture.completed(connections, List.of(merged)));

        var retired = read("/api/v1/golden-residents/" + sitaId);
        assertEquals("RETIRED", retired.path("status").asText());
        assertEquals(ramId.toString(), retired.path("redirectTo").asText());
        assertEquals(ramId.toString(), retired.path("mergedIntoId").asText());
        assertTrue(retired.path("currentVersion").isNull());
        assertEquals(1, retired.path("members").size(), "a retired ID shows the members of its last version");

        var survivor = read("/api/v1/golden-residents/" + ramId);
        assertEquals(4, survivor.path("members").size());
        assertEquals(2, survivor.path("currentVersion").asInt());
        var history = read("/api/v1/golden-residents/" + ramId + "/history");
        var v2 = history.path("versions").get(1);
        assertTrue(v2.path("changeReasons").toString().contains("MERGED"));
        assertEquals(4, v2.path("members").size());
        assertTrue(history.path("events").findValuesAsText("eventType").contains("MERGED"));
        assertEquals(1, read("/api/v1/golden-residents?status=RETIRED").path("totalItems").asInt());
        var overview = read("/api/v1/overview").path("totals");
        assertEquals(1, overview.path("retiredGoldenResidents").asInt());
    }

    @Test
    void aFailedRunShowsItsFailedStageAndCanBeResumedThroughTheExistingEndpoint() throws Exception {
        String pack = "state-1";
        mvc.perform(post("/api/onboarding/state-packs/{p}/register", pack));
        mvc.perform(post("/api/onboarding/state-packs/{p}/1.0.0/transitions/VALIDATED", pack));
        mvc.perform(post("/api/onboarding/state-packs/{p}/1.0.0/transitions/ACTIVE", pack));
        failMatchingWith = "injected matching failure";
        try {
            var started = mvc.perform(post("/api/onboarding/runs").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"packId\":\"" + pack + "\",\"version\":\"1.0.0\",\"sourceSystem\":\"pds\"}"))
                    .andExpect(status().isCreated()).andReturn();
            String run = json.readTree(started.getResponse().getContentAsString()).path("runId").asText();
            for (int i = 0; i < 6; i++) {
                mvc.perform(post("/api/onboarding/runs/{id}/next", run));
            }
            var failed = read("/api/v1/onboarding-runs/" + run);
            assertEquals("FAILED", failed.path("run").path("status").asText());
            assertEquals("injected matching failure", failed.path("run").path("failureMessage").asText());
            var matched = failed.path("timeline").get(5);
            assertEquals("MATCHED", matched.path("stage").asText());
            assertEquals("FAILED", matched.path("status").asText());
            assertEquals("injected matching failure", matched.path("latest").path("errorMessage").asText());
            assertEquals("PENDING", failed.path("timeline").get(6).path("status").asText());
            assertTrue(failed.path("timeline").get(6).path("latest").isNull());
            assertTrue(failed.path("actions").path("canResume").asBoolean());
            assertFalse(failed.path("actions").path("canRunNextStage").asBoolean());

            failMatchingWith = null;
            mvc.perform(post("/api/onboarding/runs/{id}/resume", run)).andExpect(status().isNoContent());
            var resumed = read("/api/v1/onboarding-runs/" + run);
            assertEquals("RUNNING", resumed.path("run").path("status").asText());
            assertEquals(2, resumed.path("timeline").get(5).path("attempts").asInt());
            assertEquals("COMPLETED", resumed.path("timeline").get(5).path("status").asText());
            assertTrue(resumed.path("actions").path("canRunNextStage").asBoolean());
            assertEquals(1, read("/api/v1/onboarding-runs?status=RUNNING").path("totalItems").asInt());
        } finally {
            failMatchingWith = null;
        }
    }

    private JsonNode read(String path) throws Exception {
        var result = mvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
