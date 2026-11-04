package gov.uddp.core.api.v1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Fixture directories must not make an empty operational database appear configured. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(EmptyDatabaseOverviewTest.Boundaries.class)
class EmptyDatabaseOverviewTest {
    @TestConfiguration
    static class Boundaries {
        @Bean @Primary PostgresConnectionFactory emptyDatabase() { return TestDatabase.freshMigrated(); }
    }

    @Autowired MockMvc mvc;

    @Test
    void fixturePacksAreNotOperationalStatesUntilActivatedInPostgres() throws Exception {
        mvc.perform(get("/api/v1/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.states").value(0))
                .andExpect(jsonPath("$.totals.rawRecords").value(0))
                .andExpect(jsonPath("$.totals.canonicalResidents").value(0))
                .andExpect(jsonPath("$.totals.goldenResidents").value(0))
                .andExpect(jsonPath("$.totals.onboardingRuns").value(0))
                .andExpect(jsonPath("$.states").isEmpty());
    }
}
