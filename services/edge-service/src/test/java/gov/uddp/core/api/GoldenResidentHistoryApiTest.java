package gov.uddp.core.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.golden.GoldenResidentService;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/** M11 read API: current version and history per Golden ID, and retired IDs that resolve to their merge target. */
@SpringBootTest
@AutoConfigureMockMvc
class GoldenResidentHistoryApiTest {
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

    @Test
    void detailHistoryAndRetiredIdsThroughHttpRoutes() throws Exception {
        var sql = new Sql(connections);
        service.build(SampleData.processedSampleWithMatchingRun(connections));
        var ram = new HashSet<>(sql.canonicalsNamed("Ram Singh"));
        UUID sita = sql.canonicalsNamed("Sita Singh").getFirst();
        UUID ramId = sql.goldenOf(ram.iterator().next());
        UUID sitaId = sql.goldenOf(sita);
        var merged = new HashSet<>(ram);
        merged.add(sita);
        service.build(MatchingRunFixture.completed(connections, List.of(merged)));

        mvc.perform(get("/api/golden-residents/{id}", ramId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.members.length()").value(4))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[1].changeReasons", Matchers.hasItem("MERGED")));

        mvc.perform(get("/api/golden-residents/{id}", sitaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RETIRED"))
                .andExpect(jsonPath("$.mergedIntoId").value(ramId.toString()))
                .andExpect(jsonPath("$.resolvedGoldenResidentId").value(ramId.toString()))
                .andExpect(jsonPath("$.members.length()").value(0));

        mvc.perform(get("/api/golden-residents/{id}/history", ramId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].members.length()").value(3))
                .andExpect(jsonPath("$.versions[1].members.length()").value(4))
                .andExpect(jsonPath("$.versions[0].fieldProvenance.length()").value(Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.events[*].event_type", Matchers.hasItems("CREATED", "MEMBER_ADDED", "MERGED")));

        mvc.perform(get("/api/golden-residents/{id}/history", sitaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[*].event_type", Matchers.hasItems("CREATED", "RETIRED")));

        mvc.perform(get("/api/golden-residents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
        mvc.perform(get("/api/golden-residents").param("includeRetired", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));

        mvc.perform(get("/api/golden-residents/{id}/history", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
