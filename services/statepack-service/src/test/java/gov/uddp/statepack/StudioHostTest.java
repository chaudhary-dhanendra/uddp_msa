package gov.uddp.statepack;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Onboarding Studio hosted by statepack-service (MSA): a draft is created, its file uploaded and mapped, and its
 * preview is clean; publishing submits the version as a PENDING_APPROVAL version here, by its maker; the maker cannot
 * approve it, a different user does, and the version is ACTIVE here (which announces it to the edge). The edge's
 * onboarding run reports back into the state list, and the uploaded file can be fetched for onboarding.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StudioHostTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("uddp_platform");

    static final Path WORKSPACE = temporary("uddp-statepack-studio");
    static final Path SETTINGS = temporary("uddp-statepack-settings");

    @DynamicPropertySource
    static void folders(DynamicPropertyRegistry registry) {
        registry.add("uddp.studio.workspace-directory", WORKSPACE::toString);
        registry.add("uddp.studio.settings-directory", SETTINGS::toString);
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void draftsArePublishedAsVersionsHereWithMakerAndChecker() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from statepack.lgd_entity", Integer.class))
                .as("the geography master is seeded from the platform snapshot").isEqualTo(3);
        String csv = "pid,name,sex\nP1,Asha,F\nP2,Ravi,M\n";
        JsonNode draft = call("create", "eve.engineer", json.createArrayNode().add(json.createObjectNode()
                .put("packId", "host-pack").put("stateCode", "HX").put("displayName", "Hosted"))).getBody();
        String id = draft.path("draftId").asText();
        call("upload", "eve.engineer", json.createArrayNode().add(id).add("people.csv")
                .add(Base64.getEncoder().encodeToString(csv.getBytes(StandardCharsets.UTF_8))));
        JsonNode current = call("draft", "eve.engineer", json.createArrayNode().add(id)).getBody();

        ObjectNode configuration = json.createObjectNode();
        configuration.putArray("mappings")
                .add(json.createObjectNode().put("sourceField", "name").put("entity", "Resident").put("target", "name"))
                .add(json.createObjectNode().put("sourceField", "sex").put("entity", "Resident").put("target", "gender")
                        .put("crosswalk", "gender"));
        configuration.putArray("crosswalks").add(json.readTree(
                "{\"id\":\"gender\",\"codeSet\":\"gender\",\"entries\":[{\"sourceValue\":\"F\",\"targetValue\":\"FEMALE\"},"
                        + "{\"sourceValue\":\"M\",\"targetValue\":\"MALE\"}]}"));
        configuration.set("rules", current.path("rules"));
        configuration.put("recordIdField", "pid");
        call("saveConfiguration", "eve.engineer", json.createArrayNode().add(id).add(configuration));
        JsonNode preview = call("preview", "eve.engineer", json.createArrayNode().add(id).addNull()).getBody();
        assertThat(preview.path("blockingErrors").asInt()).as(preview.toString()).isZero();

        JsonNode submitted = call("publish", "eve.engineer", json.createArrayNode().add(id)).getBody();
        assertThat(submitted.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(version("status")).isEqualTo("PENDING_APPROVAL");
        assertThat(version("maker")).isEqualTo("eve.engineer");
        String approval = submitted.path("approval").path("approvalId").asText();

        assertThat(call("approve", "eve.engineer", json.createArrayNode().add(approval).addNull()).getStatusCode()
                .value()).isEqualTo(403);
        JsonNode approved = call("approve", "sam.admin", json.createArrayNode().add(approval).add("Reviewed"))
                .getBody();
        assertThat(approved.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(approved.path("published").path("packDirectory").asText()).isEqualTo("statepack:host-pack/1.0.0");
        assertThat(version("status")).isEqualTo("ACTIVE");
        assertThat(version("checker")).isEqualTo("sam.admin");
        assertThat(jdbc.queryForObject("select count(*) from statepack.outbox_event where topic = ?", Integer.class,
                gov.uddp.contracts.Topics.PACK_ACTIVATED)).isEqualTo(1);

        call("runStarted", "eve.engineer", json.createArrayNode().add(id).add("run-1"));
        ArrayNode states = (ArrayNode) call("states", "eve.engineer", json.createArrayNode()).getBody();
        assertThat(states.get(0).path("lastRunStatus").asText()).isEqualTo("RUNNING");
        call("runFinished", "eve.engineer", json.createArrayNode().add(id).add("run-1").add("COMPLETED"));
        assertThat(call("draft", "eve.engineer", json.createArrayNode().add(id)).getBody().path("status").asText())
                .isEqualTo("ONBOARDED");
        String file = call("sourceFile", "eve.engineer", json.createArrayNode().add(id)).getBody().asText();
        assertThat(new String(Base64.getDecoder().decode(file), StandardCharsets.UTF_8)).isEqualTo(csv);

        assertThat(call("onboard", "eve.engineer", json.createArrayNode().add(id).addNull()).getStatusCode().value())
                .as("runs are started by the edge").isEqualTo(404);
    }

    private String version(String column) {
        return jdbc.queryForObject("select " + column + " from statepack.state_pack_version where pack_id = 'host-pack'"
                + " and version = '1.0.0'", String.class);
    }

    private ResponseEntity<JsonNode> call(String operation, String actor, ArrayNode args) {
        var headers = new HttpHeaders();
        headers.add("X-UDDP-Actor", actor);
        headers.add("Content-Type", "application/json");
        return http.postForEntity("/api/studio-operations/" + operation, new HttpEntity<>(args.toString(), headers),
                JsonNode.class);
    }

    private static Path temporary(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
