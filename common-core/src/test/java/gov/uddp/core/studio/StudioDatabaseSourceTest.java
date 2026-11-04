package gov.uddp.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.onboarding.PythonMatchingRunner;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Part 5: a "Database table" source. A table in a separate Testcontainers database is onboarded through the Studio
 * with the REAL generic Hop template {@code generic-db-ingest.hpl} (needs HOP_HOME and the repository registered as
 * the Hop project; skipped otherwise). The pack names the connection only; its credentials stay in the encrypted
 * local settings store. Matching is replaced by what Splink writes; everything else is real.
 */
// Publishes as one test user; the maker-checker workflow itself is StatePackApprovalTest.
@SpringBootTest(properties = "uddp.security.maker-checker.state-pack-publish=false")
@AutoConfigureMockMvc
class StudioDatabaseSourceTest {
    static final Path PACKS = temporary("uddp-db-packs");
    static final Path WORKSPACE = temporary("uddp-db-workspace");
    static final Path SETTINGS = temporary("uddp-db-settings");

    @TestConfiguration
    static class Boundaries {
        @Bean
        @Primary
        PostgresConnectionFactory dbSourceConnections() {
            return TestDatabase.freshMigrated();
        }

        @Bean
        @Primary
        PythonMatchingRunner dbSourceMatching(PostgresConnectionFactory connections) {
            return new PythonMatchingRunner(new ObjectMapper()) {
                @Override
                public UUID run() {
                    return MatchingRunFixture.completed(connections, List.of());
                }
            };
        }
    }

    @DynamicPropertySource
    static void locations(DynamicPropertyRegistry registry) {
        registry.add("uddp.studio.packs-directory", PACKS::toString);
        registry.add("uddp.state-pack-directory", PACKS::toString);
        registry.add("uddp.studio.workspace-directory", WORKSPACE::toString);
        registry.add("uddp.settings-directory", SETTINGS::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    PostgresConnectionFactory connections;

    @Test
    void aSourceTableIsIngestedByTheGenericDatabaseTemplate() throws Exception {
        String hopHome = System.getenv("HOP_HOME");
        assumeTrue(hopHome != null && !hopHome.isBlank(), "HOP_HOME is needed for the real Hop template");
        String[] server = TestDatabase.serverSettings();
        String sourceDatabase = "source_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String admin = "jdbc:postgresql://" + server[0] + ":" + server[1] + "/postgres";
        try (var c = DriverManager.getConnection(admin, server[2], server[3]); var s = c.createStatement()) {
            s.execute("create database " + sourceDatabase);
        }
        String source = "jdbc:postgresql://" + server[0] + ":" + server[1] + "/" + sourceDatabase;
        try (var c = DriverManager.getConnection(source, server[2], server[3]); var s = c.createStatement()) {
            s.execute("create table citizen_register (household_token text, resident_label text, born_on text,"
                    + " identity_marker text, district_text text, contact_number text)");
            s.execute("insert into citizen_register values"
                    + " ('H9-A','Kiran Rao','1971/04/02','1','North-Patna','9000000011'),"
                    + " ('H9-B','Meena Rao','1975/08/19','2','South-Gaya','9000000012')");
        }

        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("host", server[0]);
        connection.put("port", Integer.parseInt(server[1]));
        connection.put("database", sourceDatabase);
        connection.put("user", server[2]);
        connection.put("password", server[3]);
        String saved = send("PUT", "/api/studio/connections/registry-db", connection);
        assertFalse(saved.contains(server[3]), "the API never returns the password");
        assertFalse(Files.readString(SETTINGS.resolve("uddp.properties"), StandardCharsets.UTF_8).contains(server[3]),
                "the source password is stored only encrypted");

        String id = json.readTree(send("POST", "/api/studio/drafts",
                Map.of("packId", "db-pack", "stateCode", "DB9", "displayName", "Database source"))).path("draftId").asText();
        send("POST", "/api/studio/drafts/" + id + "/database-source",
                Map.of("connectionName", "registry-db", "table", "citizen_register"));
        JsonNode suggestions = json.readTree(send("GET", "/api/studio/drafts/" + id + "/suggestions", null));
        JsonNode draft = json.readTree(send("GET", "/api/studio/drafts/" + id, null));

        ObjectNode configuration = json.createObjectNode();
        ArrayNode mappings = configuration.putArray("mappings");
        for (JsonNode column : suggestions.path("columns")) {
            for (JsonNode target : column.path("targets")) {
                mappings.addObject()
                        .put("sourceField", column.path("column").asText())
                        .put("entity", target.path("entity").asText())
                        .put("target", target.path("target").asText())
                        .set("conversion", target.path("conversion"));
                ((ObjectNode) mappings.get(mappings.size() - 1)).set("crosswalk", target.path("crosswalk"));
            }
        }
        ArrayNode crosswalks = configuration.putArray("crosswalks");
        for (JsonNode crosswalk : suggestions.path("crosswalks")) {
            ObjectNode copy = crosswalk.deepCopy();
            for (JsonNode entry : copy.path("entries")) {
                if ("gender".equals(crosswalk.path("id").asText())) {
                    ((ObjectNode) entry).put("targetValue", "1".equals(entry.path("sourceValue").asText()) ? "MALE" : "FEMALE");
                }
            }
            crosswalks.add(copy);
        }
        configuration.set("rules", draft.path("rules"));
        configuration.set("recordIdField", suggestions.path("recordIdField"));
        configuration.set("recordKeyFields", suggestions.path("recordKeyFields"));
        send("PUT", "/api/studio/drafts/" + id + "/configuration", configuration);

        JsonNode preview = json.readTree(send("GET", "/api/studio/drafts/" + id + "/preview", null));
        assertEquals(0, preview.path("blockingErrors").asInt(), preview.toString());
        assertEquals(2, preview.path("rowsPreviewed").asInt());
        send("POST", "/api/studio/drafts/" + id + "/publish", Map.of());
        // Published packs are database documents (V14); the source names the connection, never its credentials.
        String document = text("select configuration::text from state_pack_document where pack_id = 'db-pack'");
        JsonNode publishedSource = json.readTree(document).path("sources").get(0);
        assertEquals("generic-db-ingest.hpl", publishedSource.path("hopTemplate").asText(), document);
        assertEquals("registry-db", publishedSource.path("connectionRef").asText(), document);
        assertFalse(document.contains(server[3]), "no credential in the pack");

        String run = json.readTree(send("POST", "/api/studio/drafts/" + id + "/onboard", Map.of())).path("runId").asText();
        JsonNode detail = null;
        for (int i = 0; i < 1200; i++) {
            detail = json.readTree(send("GET", "/api/onboarding/runs/" + run, null));
            if (!"RUNNING".equals(detail.path("status").asText())) {
                break;
            }
            Thread.sleep(250);
        }
        assertEquals("COMPLETED", detail.path("status").asText(), detail.toString());
        assertEquals(2, count("select count(*) from raw_record where pipeline_template='generic-db-ingest.hpl'"));
        assertEquals(2, count("select count(*) from canonical_resident where canonical_payload->>'name' like '% Rao'"));
        assertEquals(0, count("select count(*) from onboarding_run where failure_message like '%" + server[3] + "%'"));
    }

    private String send(String method, String path, Object body) throws Exception {
        var request = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn().getResponse()
                .getContentAsString();
    }

    private String text(String sql) {
        try (var c = connections.open(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int count(String sql) {
        try (var c = connections.open(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path temporary(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
