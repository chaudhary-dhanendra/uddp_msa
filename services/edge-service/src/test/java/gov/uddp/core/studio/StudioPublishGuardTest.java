package gov.uddp.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Publishing is blocked while the preview has blocking errors, and nothing is written or loaded. */
@SpringBootTest
@AutoConfigureMockMvc
class StudioPublishGuardTest {
    static final Path PACKS = temporary("uddp-guard-packs");
    static final Path WORKSPACE = temporary("uddp-guard-workspace");

    @TestConfiguration
    static class Database {
        @Bean
        @Primary
        PostgresConnectionFactory guardConnections() {
            return TestDatabase.freshMigrated();
        }
    }

    @DynamicPropertySource
    static void locations(DynamicPropertyRegistry registry) {
        registry.add("uddp.studio.packs-directory", PACKS::toString);
        registry.add("uddp.state-pack-directory", PACKS::toString);
        registry.add("uddp.studio.workspace-directory", WORKSPACE::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    void anUnmappedCrosswalkValueBlocksPublication() throws Exception {
        String draft = mvc.perform(post("/api/studio/drafts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packId\":\"guard-pack\",\"stateCode\":\"GX\",\"displayName\":\"Guard\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(draft).path("draftId").asText();
        mvc.perform(multipart("/api/studio/drafts/" + id + "/upload").file(new MockMultipartFile("file", "people.csv",
                        "text/csv", "pid,name,sex\nP1,Asha,F\nP2,Ravi,X\n".getBytes())))
                .andExpect(status().isOk());
        JsonNode current = json.readTree(mvc.perform(get("/api/studio/drafts/" + id))
                .andReturn().getResponse().getContentAsString());
        ObjectNode configuration = json.createObjectNode();
        configuration.putArray("mappings")
                .add(json.createObjectNode().put("sourceField", "name").put("entity", "Resident").put("target", "name"))
                .add(json.createObjectNode().put("sourceField", "sex").put("entity", "Resident").put("target", "gender")
                        .put("crosswalk", "gender"));
        configuration.putArray("crosswalks").add(json.readTree(
                "{\"id\":\"gender\",\"codeSet\":\"gender\",\"entries\":[{\"sourceValue\":\"F\",\"targetValue\":\"FEMALE\"},"
                        + "{\"sourceValue\":\"X\",\"targetValue\":null}]}"));
        configuration.set("rules", current.path("rules"));
        configuration.put("recordIdField", "pid");
        mvc.perform(put("/api/studio/drafts/" + id + "/configuration").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(configuration)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/studio/drafts/" + id + "/preview"))
                .andExpect(jsonPath("$.blockingErrors").value(1))
                .andExpect(jsonPath("$.rows[1].issues[0].severity").value("error"));
        mvc.perform(post("/api/studio/drafts/" + id + "/publish"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("blocking error")));

        try (var children = Files.list(PACKS)) {
            assertEquals(0, children.count(), "nothing is published");
        }
        String generated = mvc.perform(get("/api/studio/drafts/" + id + "/generated"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(generated.contains("hopTemplate: generic-file-ingest.hpl"), generated);
    }

    @Test
    void aFewBadRowsInALargeSourceDoNotBlockPublication() throws Exception {
        String draft = mvc.perform(post("/api/studio/drafts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packId\":\"few-bad\",\"stateCode\":\"GY\",\"displayName\":\"Few bad rows\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(draft).path("draftId").asText();
        StringBuilder csv = new StringBuilder("pid,name,sex\n");
        for (int i = 1; i <= 100; i++) {
            csv.append('P').append(i).append(",Person ").append(i).append(',').append(i == 50 ? "X" : "F").append('\n');
        }
        mvc.perform(multipart("/api/studio/drafts/" + id + "/upload").file(new MockMultipartFile("file", "few.csv",
                        "text/csv", csv.toString().getBytes())))
                .andExpect(status().isOk());
        JsonNode current = json.readTree(mvc.perform(get("/api/studio/drafts/" + id))
                .andReturn().getResponse().getContentAsString());
        ObjectNode configuration = json.createObjectNode();
        configuration.putArray("mappings")
                .add(json.createObjectNode().put("sourceField", "name").put("entity", "Resident").put("target", "name"))
                .add(json.createObjectNode().put("sourceField", "sex").put("entity", "Resident").put("target", "gender")
                        .put("crosswalk", "gender"));
        configuration.putArray("crosswalks").add(json.readTree(
                "{\"id\":\"gender\",\"codeSet\":\"gender\",\"entries\":[{\"sourceValue\":\"F\",\"targetValue\":\"FEMALE\"},"
                        + "{\"sourceValue\":\"X\",\"targetValue\":null}]}"));
        configuration.set("rules", current.path("rules"));
        configuration.put("recordIdField", "pid");
        mvc.perform(put("/api/studio/drafts/" + id + "/configuration").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(configuration)))
                .andExpect(status().isOk());

        // 1 bad row of 100 (1%) is within the 5% held limit: it is rejected at load, the pack still publishes.
        mvc.perform(post("/api/studio/drafts/" + id + "/publish"))
                .andExpect(status().isOk());
    }

    @Test
    void aDryRunChecksALargeSampleInTheBackgroundAndGroupsTheIssuesByReason() throws Exception {
        String draft = mvc.perform(post("/api/studio/drafts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packId\":\"dry-pack\",\"stateCode\":\"DX\",\"displayName\":\"Dry run\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(draft).path("draftId").asText();
        StringBuilder csv = new StringBuilder("pid,name,sex\n");
        for (int i = 1; i <= 5001; i++) {
            csv.append('P').append(i).append(",Person ").append(i).append(',').append(i % 10 == 0 ? "X" : "F")
                    .append('\n');
        }
        mvc.perform(multipart("/api/studio/drafts/" + id + "/upload").file(new MockMultipartFile("file", "many.csv",
                        "text/csv", csv.toString().getBytes())))
                .andExpect(status().isOk());
        JsonNode current = json.readTree(mvc.perform(get("/api/studio/drafts/" + id))
                .andReturn().getResponse().getContentAsString());
        ObjectNode configuration = json.createObjectNode();
        configuration.putArray("mappings")
                .add(json.createObjectNode().put("sourceField", "name").put("entity", "Resident").put("target", "name"))
                .add(json.createObjectNode().put("sourceField", "sex").put("entity", "Resident").put("target", "gender")
                        .put("crosswalk", "gender"));
        configuration.putArray("crosswalks").add(json.readTree(
                "{\"id\":\"gender\",\"codeSet\":\"gender\",\"entries\":[{\"sourceValue\":\"F\",\"targetValue\":\"FEMALE\"},"
                        + "{\"sourceValue\":\"X\",\"targetValue\":null}]}"));
        configuration.set("rules", current.path("rules"));
        configuration.put("recordIdField", "pid");
        mvc.perform(put("/api/studio/drafts/" + id + "/configuration").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(configuration)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/studio/drafts/" + id + "/dry-run")).andExpect(jsonPath("$.status").value("NONE"));
        mvc.perform(post("/api/studio/drafts/" + id + "/dry-run?rows=5000"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"));
        JsonNode result = null;
        for (int i = 0; i < 600; i++) {
            result = json.readTree(mvc.perform(get("/api/studio/drafts/" + id + "/dry-run"))
                    .andReturn().getResponse().getContentAsString());
            if (!"RUNNING".equals(result.path("status").asText())) {
                break;
            }
            Thread.sleep(100);
        }
        assertEquals("COMPLETED", result.path("status").asText(), result.toString());
        assertEquals(5000, result.path("rowsChecked").asInt());
        assertTrue(result.path("moreRows").asBoolean(), "the source has a 5,001st row");
        assertEquals(500, result.path("rowsWithErrors").asInt(), result.toString());
        JsonNode first = result.path("groups").get(0);
        assertEquals("error", first.path("severity").asText());
        assertEquals(500, first.path("count").asInt());
        assertEquals("[10,20,30]", first.path("sampleRows").toString());
        assertTrue(first.path("fix").path("kind").asText().length() > 0, first.toString());
    }

    private static Path temporary(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
