package gov.uddp.core.api;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.geography.LgdSyncService;
import gov.uddp.core.read.Db;
import gov.uddp.core.security.Permission;
import gov.uddp.core.testsupport.SyntheticLgdSnapshot;
import gov.uddp.core.testsupport.TestDatabase;
import gov.uddp.core.testsupport.TestSecurity;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The geography master upload endpoints: administrators only (403 before any 400), clear 400s, 409 while busy. */
@SpringBootTest
@AutoConfigureMockMvc
class GeographySnapshotApiTest {
    @TestConfiguration
    static class Database {
        @Bean
        @Primary
        PostgresConnectionFactory snapshotApiConnections() {
            return TestDatabase.freshMigrated();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    PostgresConnectionFactory connections;
    @Autowired
    LgdSyncService sync;

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "text/csv", bytes);
    }

    private static MockMultipartFile csv(String text) {
        return file("snapshot.csv", text.getBytes(StandardCharsets.UTF_8));
    }

    private static RequestPostProcessor engineer() {
        return TestSecurity.as(TestSecurity.principal("eng", Set.of("DATA_ENGINEER"),
                EnumSet.of(Permission.GEOGRAPHY_VIEW, Permission.GEOGRAPHY_SYNC), List.of("ALL")));
    }

    @BeforeEach
    void emptyMaster() throws Exception {
        try (var c = connections.open(); var s = c.createStatement()) {
            s.execute("truncate lgd_snapshot, lgd_entity, lgd_sync_run, lgd_master_state cascade");
        }
    }

    private long entities() {
        return new Db(connections, new ObjectMapper()).count("select count(*) from lgd_entity");
    }

    @Test
    void nonAdministratorsGet403BeforeAnyValidation() throws Exception {
        // no file and no confirm is a 400 for an administrator, but everyone else is refused first
        mvc.perform(multipart("/api/geography/snapshots/validate").with(engineer())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/geography/snapshots").with(engineer())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/geography/snapshots").file(file("x.exe", new byte[] {1})).with(engineer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/geography/snapshots/template").with(engineer())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/geography/snapshots").with(TestSecurity.as(TestSecurity.principal("nobody",
                Set.of(), EnumSet.noneOf(Permission.class), List.of("ALL"))))).andExpect(status().isForbidden());
    }

    @Test
    void unusableFilesAreRefusedWithClearMessages() throws Exception {
        mvc.perform(multipart("/api/geography/snapshots/validate")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/geography/snapshots/validate").file(file("a.txt", new byte[] {'x'})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Unsupported")));
        mvc.perform(multipart("/api/geography/snapshots/validate").file(file("a.csv", new byte[] {(byte) 0xE9, '\n'})))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("UTF-8")));
        mvc.perform(multipart("/api/geography/snapshots/validate").file(csv("STATE,1,A\n")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("header")));
        mvc.perform(multipart("/api/geography/snapshots/validate").file(csv("entityType,code,name\nSTATE,1,A\n"))
                .param("asOf", "soon")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/geography/snapshots").file(csv("entityType,code,name\nSTATE,1,A\n")))
                .andExpect(status().isBadRequest());
        assertEquals(0, entities());
    }

    @Test
    void validateReportsCountsAndChangesNothing() throws Exception {
        mvc.perform(multipart("/api/geography/snapshots/validate").file(csv(SyntheticLgdSnapshot.csv())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").value(SyntheticLgdSnapshot.ROWS))
                .andExpect(jsonPath("$.rowsByType.STATE").value(2))
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andExpect(jsonPath("$.changeTotals.ADDED").value(SyntheticLgdSnapshot.ROWS))
                .andExpect(jsonPath("$.samples.ADDED.length()").value(20));
        mvc.perform(multipart("/api/geography/snapshots/validate")
                        .file(csv("entityType,code,name\nVILLAGE,5,V\nSTATE,,S\n")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0].line").value(3));
        assertEquals(0, entities());
    }

    @Test
    void uploadLoadsTheSnapshotAndTheTemplateIsAValidSnapshot() throws Exception {
        var template = mvc.perform(get("/api/geography/snapshots/template")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(5, template.lines().filter(l -> !l.startsWith("#") && !l.startsWith("entityType")).count());
        mvc.perform(multipart("/api/geography/snapshots/validate").file(csv(template)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errors.length()").value(0));

        var response = mvc.perform(multipart("/api/geography/snapshots").file(csv(SyntheticLgdSnapshot.csv()))
                        .param("confirm", "true"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.triggerKind").value("UPLOAD"))
                .andExpect(jsonPath("$.actor").value("test-admin"))
                .andExpect(jsonPath("$.sourceLabel").value("snapshot.csv"))
                .andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(new ObjectMapper().readTree(response).get("runId").asText());
        String state = "RUNNING";
        for (int i = 0; i < 200 && state.equals("RUNNING"); i++) {
            Thread.sleep(100);
            state = sync.run(runId).orElseThrow().status();
        }
        assertEquals("SUCCEEDED", state);
        assertEquals(SyntheticLgdSnapshot.ROWS, entities());
        assertNotNull(sync.status().activeSnapshotId());
    }

    @Test
    void aBusySlotAnswers409() throws Exception {
        try (var c = connections.open(); var s = c.prepareStatement("insert into lgd_sync_run(run_id,trigger_kind,"
                + "actor,status,started_at) values(?,'MANUAL','x','RUNNING',now())")) {
            s.setObject(1, UUID.randomUUID());
            s.executeUpdate();
        }
        mvc.perform(multipart("/api/geography/snapshots").file(csv(SyntheticLgdSnapshot.csv())).param("confirm", "true"))
                .andExpect(status().isConflict());
        assertEquals(0, entities());
    }
}
