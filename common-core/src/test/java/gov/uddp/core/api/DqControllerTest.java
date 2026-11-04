package gov.uddp.core.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc
class DqControllerTest {
    @TestConfiguration static class Db { @Bean @Primary PostgresConnectionFactory connections(){return TestDatabase.freshMigrated();} }
    @Autowired MockMvc mvc; @Autowired PostgresConnectionFactory connections; UUID batch;
    @BeforeEach void setup(){batch=SampleData.landAll(connections,SampleData.FIXTURE_PACKS).get("fx-alpha").getFirst();SampleData.processing(connections,SampleData.loader(SampleData.FIXTURE_PACKS)).processBatch(batch);}
    @Test void readsPersistedSummaryAndUsesBoundedSamples() throws Exception {
        var result = mvc.perform(get("/api/dq/batches/{id}",batch)).andExpect(status().isOk()).andExpect(jsonPath("$.batchId").value(batch.toString()))
                .andExpect(jsonPath("$.warningCount").value(1)).andExpect(jsonPath("$.samples.length()").value(2))
                .andReturn();
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertTrue(body.path("samples").size() == 2);
        assertTrue(java.util.stream.StreamSupport.stream(body.path("samples").spliterator(), false)
                .anyMatch(sample -> sample.path("severity").asText().equals("ERROR") && sample.path("canonicalResidentId").isNull()));
        assertTrue(java.util.stream.StreamSupport.stream(body.path("samples").spliterator(), false)
                .anyMatch(sample -> sample.path("severity").asText().equals("WARNING") && !sample.path("canonicalResidentId").isNull()));
    }
    @Test void unknownBatchIsNotFound() throws Exception { mvc.perform(get("/api/dq/batches/{id}",UUID.randomUUID())).andExpect(status().isNotFound()); }
}
