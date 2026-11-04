package gov.uddp.core.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.testsupport.Migrations;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The backend starts without database settings (setup mode), is configured through /api/setup against a disposable
 * Testcontainers server (a new *_demo database is created), switches to normal mode without a restart, never
 * returns or stores the password in plain text, and resets only a *_demo database confirmed by name.
 */
@SpringBootTest(properties = "uddp.database.migrate-on-startup=true")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SetupApiTest {
    static final Path SETTINGS = temporary("uddp-setup-settings");
    static final Path STUDIO_PACKS = temporary("uddp-setup-packs");
    static final Path STUDIO_WORKSPACE = temporary("uddp-setup-workspace");
    static final String DEMO_DATABASE = "uddp_setup_" + UUID.randomUUID().toString().substring(0, 8) + "_demo";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @DynamicPropertySource
    static void locations(DynamicPropertyRegistry registry) {
        registry.add("uddp.settings-directory", SETTINGS::toString);
        registry.add("uddp.studio.packs-directory", STUDIO_PACKS::toString);
        registry.add("uddp.studio.workspace-directory", STUDIO_WORKSPACE::toString);
        registry.add("uddp.state-pack-directory", STUDIO_PACKS::toString);
    }

    @Test
    @Order(1)
    void startsInSetupModeWhereOnlyHealthAndSetupWork() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("SETUP"));
        mvc.perform(get("/api/v1/overview")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SETUP_REQUIRED"));
        mvc.perform(get("/api/state-packs")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/setup/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SETUP"))
                .andExpect(jsonPath("$.source").value("NONE"));
    }

    @Test
    @Order(2)
    void aFailedConnectionTestNeverEchoesThePassword() throws Exception {
        var request = request(DEMO_DATABASE, "Wrong-" + UUID.randomUUID(), false);
        String body = mvc.perform(post("/api/setup/test-connection").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains((String) request.get("password")), body);
    }

    @Test
    @Order(3)
    void applyCreatesTheDatabaseMigratesItAndSwitchesToNormalModeWithoutARestart() throws Exception {
        String password = TestDatabase.serverSettings()[3];
        var request = request(DEMO_DATABASE, password, true);
        mvc.perform(post("/api/setup/test-connection").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.databaseExists").value(false));

        String body = mvc.perform(post("/api/setup/apply").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("READY"))
                .andExpect(jsonPath("$.source").value("LOCAL_STORE"))
                .andExpect(jsonPath("$.schemaVersion").value(String.valueOf(Migrations.LATEST)))
                .andExpect(jsonPath("$.settings.database").value(DEMO_DATABASE))
                .andExpect(jsonPath("$.settings.passwordSet").value(true))
                .andExpect(jsonPath("$.resetAllowed").value(true))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(password), "the API never returns the password");
        String stored = Files.readString(SETTINGS.resolve("uddp.properties"), StandardCharsets.UTF_8);
        assertFalse(stored.contains(password), "the password is stored only encrypted");
        mvc.perform(get("/health")).andExpect(jsonPath("$.mode").value("READY"))
                .andExpect(jsonPath("$.databaseSource").value("LOCAL_STORE"));
        mvc.perform(get("/api/v1/overview")).andExpect(status().isOk());
        String status = mvc.perform(get("/api/setup/status")).andReturn().getResponse().getContentAsString();
        assertFalse(status.contains(password), status);
    }

    @Test
    @Order(4)
    void resetNeedsTheExactDemoDatabaseNameAndClearsStudioFiles() throws Exception {
        Files.createDirectories(STUDIO_PACKS.resolve("generated-pack"));
        Files.writeString(STUDIO_WORKSPACE.resolve("draft.json"), "{}");

        mvc.perform(post("/api/setup/reset-demo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmDatabaseName\":\"uddp\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(Files.exists(STUDIO_WORKSPACE.resolve("draft.json")), "a refused reset changes nothing");

        mvc.perform(post("/api/setup/reset-demo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmDatabaseName\":\"" + DEMO_DATABASE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("READY"))
                .andExpect(jsonPath("$.schemaVersion").value(String.valueOf(Migrations.LATEST)));
        try (var children = Files.list(STUDIO_PACKS)) {
            assertEquals(0, children.count(), "Studio packs are removed");
        }
        try (var children = Files.list(STUDIO_WORKSPACE)) {
            assertEquals(0, children.count(), "drafts and uploads are removed");
        }
    }

    @Test
    @Order(5)
    void aDatabaseWhoseNameDoesNotEndWithDemoIsNeverReset() throws Exception {
        String other = "uddp_setup_" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/api/setup/apply").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request(other, TestDatabase.serverSettings()[3], true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resetAllowed").value(false));

        mvc.perform(post("/api/setup/reset-demo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmDatabaseName\":\"" + other + "\"}"))
                .andExpect(status().isConflict());
    }

    private static Map<String, Object> request(String database, String password, boolean create) {
        String[] server = TestDatabase.serverSettings();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("host", server[0]);
        request.put("port", Integer.parseInt(server[1]));
        request.put("database", database);
        request.put("user", server[2]);
        request.put("password", password);
        request.put("createDatabaseIfMissing", create);
        return request;
    }

    private static Path temporary(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
