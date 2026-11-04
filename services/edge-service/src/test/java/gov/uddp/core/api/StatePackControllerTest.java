package gov.uddp.core.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Pack metadata needs a configured platform database: in setup mode every /api/** except /api/setup answers 503. */
@SpringBootTest
@AutoConfigureMockMvc
class StatePackControllerTest {
    @org.springframework.boot.test.context.TestConfiguration
    static class ConfiguredDatabase {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        gov.uddp.core.canonical.PostgresConnectionFactory configuredConnections() {
            return gov.uddp.core.testsupport.TestDatabase.freshMigrated();
        }
    }

    @Autowired MockMvc mockMvc;
    @Test void listsAllConfiguredStatePacks() throws Exception {
        mockMvc.perform(get("/api/state-packs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].packId").exists());
    }
    @Test void exposesSafePackMetadata() throws Exception {
        mockMvc.perform(get("/api/state-packs/state-2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.stateCode").value("ST2"))
                .andExpect(jsonPath("$.sources[0].hopTemplate").value("generic-file-ingest.hpl"))
                .andExpect(jsonPath("$.sources[0].connectionRef").doesNotExist());
    }
    @Test void exposesStateOneMetadata() throws Exception {
        mockMvc.perform(get("/api/state-packs/state-1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.stateCode").value("ST1"))
                .andExpect(jsonPath("$.sources[0].hopTemplate").value("generic-file-ingest.hpl"));
    }
}
