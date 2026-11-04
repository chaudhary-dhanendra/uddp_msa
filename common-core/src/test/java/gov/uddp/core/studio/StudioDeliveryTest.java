package gov.uddp.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.charset.StandardCharsets;
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

/**
 * Track C1 (Studio): a new delivery of a published file source replaces the landing file the pack points at, keeps
 * the source's identity and record key, and is refused (file untouched) when it lacks a mapped column.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StudioDeliveryTest {
    static final Path PACKS = temporary("uddp-delivery-packs");
    static final Path WORKSPACE = temporary("uddp-delivery-workspace");

    @TestConfiguration
    static class Database {
        @Bean
        @Primary
        PostgresConnectionFactory deliveryConnections() {
            return TestDatabase.freshMigrated();
        }
    }

    @DynamicPropertySource
    static void locations(DynamicPropertyRegistry registry) {
        registry.add("uddp.studio.packs-directory", PACKS::toString);
        registry.add("uddp.state-pack-directory", PACKS::toString);
        registry.add("uddp.studio.workspace-directory", WORKSPACE::toString);
        registry.add("uddp.security.maker-checker.state-pack-publish", () -> "false");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    void aNewDeliveryReplacesThePublishedLandingFile() throws Exception {
        String id = json.readTree(mvc.perform(post("/api/studio/drafts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packId\":\"delivery-pack\",\"stateCode\":\"DX\",\"displayName\":\"Delivery\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("draftId").asText();

        mvc.perform(multipart("/api/studio/drafts/" + id + "/delivery").file(csv("people.csv", "pid,name\nP1,Asha\n")))
                .andExpect(status().isConflict());

        mvc.perform(multipart("/api/studio/drafts/" + id + "/upload").file(csv("people.csv", "pid,name\nP1,Asha\nP2,Ravi\n")))
                .andExpect(status().isOk());
        JsonNode current = json.readTree(mvc.perform(get("/api/studio/drafts/" + id)).andReturn().getResponse().getContentAsString());
        ObjectNode configuration = json.createObjectNode();
        configuration.putArray("mappings")
                .add(json.createObjectNode().put("sourceField", "name").put("entity", "Resident").put("target", "name"));
        configuration.putArray("crosswalks");
        configuration.set("rules", current.path("rules"));
        configuration.put("recordIdField", "pid");
        mvc.perform(put("/api/studio/drafts/" + id + "/configuration").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(configuration))).andExpect(status().isOk());
        mvc.perform(post("/api/studio/drafts/" + id + "/publish")).andExpect(status().isOk());

        JsonNode published = json.readTree(mvc.perform(get("/api/studio/drafts/" + id)).andReturn().getResponse().getContentAsString());
        Path landing = Path.of(published.path("source").path("location").asText());
        String sourceId = published.path("source").path("sourceId").asText();

        mvc.perform(multipart("/api/studio/drafts/" + id + "/delivery").file(csv("june.csv", "pid,full_name\nP1,Asha\n")))
                .andExpect(status().isConflict());
        assertEquals("pid,name\nP1,Asha\nP2,Ravi\n", Files.readString(landing, StandardCharsets.UTF_8), "a refused delivery changes nothing");

        String june = "pid,name,remarks\nP1,Asha Rao,changed\nP3,Meena,new\n";
        JsonNode delivered = json.readTree(mvc.perform(multipart("/api/studio/drafts/" + id + "/delivery").file(csv("june.csv", june)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(june, Files.readString(landing, StandardCharsets.UTF_8));
        assertEquals(sourceId, delivered.path("source").path("sourceId").asText());
        assertEquals("pid", delivered.path("source").path("recordIdField").asText());
        assertEquals(landing.toString(), delivered.path("source").path("location").asText());
        assertTrue(delivered.path("published").isObject(), "the pack stays published; onboarding runs it next");
    }

    private static MockMultipartFile csv(String name, String content) {
        return new MockMultipartFile("file", name, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    private static Path temporary(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
