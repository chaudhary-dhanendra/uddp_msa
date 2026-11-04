package gov.uddp.core.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.onboarding.StatePackLifecycleService;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * DQ report endpoints over the bad-data fixture pack fx-alpha (one error-severity allowedValues finding, one
 * warning-severity mobile finding; see BadDataFixtureTest), served through its own context and database.
 */
@SpringBootTest(properties = "uddp.state-pack-directory=../../tests/fixtures/state-packs")
@AutoConfigureMockMvc
class DqReportApiTest {
    @TestConfiguration
    static class FixtureDatabase {
        @Bean
        @Primary
        PostgresConnectionFactory dqConnections() {
            return TestDatabase.freshMigrated();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgresConnectionFactory connections;

    @Autowired
    StatePackLifecycleService lifecycle;

    private static UUID alpha;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void processFixtureOnce() {
        synchronized (DqReportApiTest.class) {
            if (alpha != null) {
                return;
            }
            var loader = SampleData.loader(SampleData.FIXTURE_PACKS);
            // A pack is an operational state (resolvable by pack ID) only once its lifecycle is ACTIVE.
            for (var pack : loader.all()) {
                String id = pack.manifest().packId();
                lifecycle.register(id);
                lifecycle.transition(id, pack.manifest().version(), "VALIDATED");
                lifecycle.transition(id, pack.manifest().version(), "ACTIVE");
            }
            var service = SampleData.processing(connections, loader);
            var batches = SampleData.landAll(connections, SampleData.FIXTURE_PACKS);
            batches.values().forEach(ids -> ids.forEach(service::processBatch));
            alpha = batches.get("fx-alpha").getFirst();
        }
    }

    @Test
    void ruleBreakdownFindingsAndBatchDetailShowFailingRecords() throws Exception {
        var rules = read("/api/v1/dq/rules?batchId=" + alpha);
        assertEquals(2, rules.size(), rules.toString());
        var error = rules.get(0);
        assertEquals("ERROR", error.path("severity").asText());
        assertEquals(1, error.path("findings").asInt());
        assertEquals(1, error.path("batches").asInt());
        var warning = rules.get(1);
        assertEquals("WARNING", warning.path("severity").asText());
        assertEquals("mobile", warning.path("fieldPath").asText());
        assertEquals(read("/api/v1/dq/rules?state=fx-alpha"), rules, "pack ID filter equals batch filter here");

        var warnings = read("/api/v1/dq/findings?batchId=" + alpha + "&severity=warning");
        assertEquals(1, warnings.path("totalItems").asInt());
        var finding = warnings.path("items").get(0);
        assertTrue(finding.path("canonicalResidentId").isTextual(), "a kept record links to its canonical Resident");
        assertTrue(finding.path("rawRecordId").isTextual());
        var errors = read("/api/v1/dq/findings?batchId=" + alpha + "&severity=ERROR");
        assertTrue(errors.path("items").get(0).path("canonicalResidentId").isNull(), "a rejected record has none");
        assertEquals(1, read("/api/v1/dq/findings?ruleId=" + error.path("ruleId").asText() + "&batchId=" + alpha)
                .path("totalItems").asInt());
        var paged = read("/api/v1/dq/findings?size=1&sort=severity,asc");
        assertEquals(1, paged.path("items").size());
        assertTrue(paged.path("totalItems").asInt() >= 2);

        var batch = read("/api/v1/batches/" + alpha);
        assertEquals(1, batch.path("dq").path("errorCount").asInt());
        assertEquals(1, batch.path("dq").path("warningCount").asInt());
        assertEquals(2, batch.path("sampleFindings").size());
        assertEquals(4, batch.path("processingErrors").size(), "mapping and validation rejections");
        assertEquals(4, batch.path("batch").path("processingErrors").asInt());
        assertEquals(2, batch.path("batch").path("canonicalResidents").asInt());
    }

    private JsonNode read(String path) throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.get(path))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
