package gov.uddp.core.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.golden.GoldenResidentService;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class GoldenResidentControllerTest {
    @TestConfiguration
    static class DisposableDatabase {
        @Bean
        @Primary
        PostgresConnectionFactory testConnections() {
            return TestDatabase.freshMigrated();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    GoldenResidentService service;

    @Autowired
    PostgresConnectionFactory connections;

    UUID run;

    @BeforeEach
    void build() {
        run = SampleData.processedSampleWithMatchingRun(connections);
        service.build(run);
    }

    @Test
    void listsAndDetailsGoldenResidentsThroughHttpRoutes() throws Exception {
        var body = mvc.perform(get("/api/golden-residents")
                        .param("matchingRunId", run.toString())
                        .param("survivorshipPolicyVersion", "resident-survivorship-v3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = new ObjectMapper().readTree(body).get(0).get("goldenResidentId").asText();

        mvc.perform(get("/api/golden-residents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.fieldProvenance.length()").value(Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.fieldProvenance[0].survivorship_policy_version").value("resident-survivorship-v3"));

        mvc.perform(get("/api/golden-residents/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
