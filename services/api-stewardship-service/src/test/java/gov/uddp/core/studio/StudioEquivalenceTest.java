package gov.uddp.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.onboarding.HopRunner;
import gov.uddp.core.onboarding.PythonMatchingRunner;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.testsupport.HopShapedRawLoader;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * Part 4 equivalence through the Studio API only, starting with zero states: States 1, 2 and 3 are onboarded from
 * their sample files by accepting the suggestions plus the corrections a user would make, then State 4
 * incrementally. Only the Hop and Splink process boundaries are replaced (by what they write; the real ones run in
 * the Playwright demo journey); mapping, validation, publication, lifecycle, Golden build and families are real.
 * The test also writes a comparison of every generated pack with its hand-written reference pack to
 * {@code target/studio-equivalence.md}.
 */
// Publishes as one test user; the maker-checker workflow itself is StatePackApprovalTest.
@SpringBootTest(properties = "uddp.security.maker-checker.state-pack-publish=false")
@AutoConfigureMockMvc
class StudioEquivalenceTest {
    static final Path PACKS = temporary("uddp-studio-packs");
    static final Path WORKSPACE = SampleData.REPOSITORY_ROOT.resolve("services/api-stewardship-service/target/studio-equivalence-workspace")
            .toAbsolutePath().normalize();
    static final Path REPORT = Path.of("target/studio-equivalence.md");

    /**
     * The crosswalk values a user types on the Crosswalks step because no generic signal can know them (local codes
     * with no name, or a first state using a code nobody used before). Everything else is accepted as suggested.
     */
    static final Map<String, Map<String, Map<String, String>>> CORRECTIONS = Map.of(
            "state-1", Map.of(
                    "district", Map.of("101", "100001", "102", "100002"),
                    "scheme", Map.of("PHH", "PDS-RATION", "AAY", "PDS-RATION")),
            "state-2", Map.of(
                    "gender", Map.of("1", "MALE", "2", "FEMALE"),
                    "benefit-status", Map.of("1", "ACTIVE"),
                    "scheme", Map.of("VRIDHA", "OLD-AGE-PENSION")));

    @TestConfiguration
    static class Boundaries {
        @Bean
        @Primary
        PostgresConnectionFactory studioConnections() {
            return TestDatabase.freshMigrated();
        }

        @Bean
        @Primary
        HopRunner studioHop(PostgresConnectionFactory connections) {
            var raw = new HopShapedRawLoader(connections, SampleData.REPOSITORY_ROOT);
            return (source, stateCode, version, batch) ->
                    new HopRunner.Result(0, "landed " + raw.landInto(source, stateCode, version, batch));
        }

        /** What Splink decides for the sample: every "Ram Singh" record is one AUTO_MATCH group. */
        @Bean
        @Primary
        PythonMatchingRunner studioMatching(PostgresConnectionFactory connections) {
            return new PythonMatchingRunner(new ObjectMapper()) {
                @Override
                public UUID run() {
                    return MatchingRunFixture.completed(connections, ram(connections));
                }

                @Override
                public UUID runIncremental() {
                    return MatchingRunFixture.incremental(connections, ram(connections));
                }
            };
        }

        private static List<Set<UUID>> ram(PostgresConnectionFactory connections) {
            var ram = new HashSet<>(new Sql(connections).canonicalsNamed("Ram Singh"));
            return ram.size() < 2 ? List.of() : List.of(ram);
        }
    }

    @DynamicPropertySource
    static void locations(DynamicPropertyRegistry registry) {
        registry.add("uddp.studio.packs-directory", PACKS::toString);
        // Repository YAML fixtures are present but must never become operational packs automatically.
        registry.add("uddp.state-pack-directory", SampleData.STATE_PACKS::toString);
        registry.add("uddp.fixture-state-packs-enabled", () -> false);
        registry.add("uddp.studio.workspace-directory", WORKSPACE::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    PostgresConnectionFactory connections;
    @Autowired
    StatePackLoader packs;

    @Test
    void studioOnboardsAllFourStatesFromAnEmptyPlatformWithTheReferenceResults() throws Exception {
        deleteTree(WORKSPACE);
        assertEquals(0, json.readTree(get("/api/studio/states")).size(), "the platform starts with zero states");
        assertEquals(0, json.readTree(get("/api/state-packs")).size(), "no pack is loaded before the Studio publishes");
        StringBuilder report = new StringBuilder("# Studio-generated packs compared with the hand-written packs\n\n");

        onboard("state-1", "ST1", "State 1", "sample-data/state-1/pds.csv", report);
        onboard("state-2", "ST2", "State 2", "sample-data/state-2/pension.csv", report);
        onboard("state-3", "ST3", "State 3", "sample-data/state-3/beneficiaries.csv", report);

        Map<String, Integer> afterThree = counts();
        report.append("## Counts after States 1-3\n\n").append(afterThree).append("\n\n");
        assertEquals(7, afterThree.get("raw"));
        assertEquals(7, afterThree.get("canonical"));
        assertEquals(3, afterThree.get("autoMatch"));
        assertEquals(5, afterThree.get("golden"));
        assertEquals(6, afterThree.get("families"));
        assertEquals(5, afterThree.get("benefits"));
        String ramBefore = ramGoldenId();

        onboard("state-4", "ST4", "State 4", "sample-data/state-4/citizen-register.csv", report);

        Map<String, Integer> afterFour = counts();
        report.append("## Counts after State 4 (incremental)\n\n").append(afterFour).append("\n\n");
        assertEquals(9, afterFour.get("raw"));
        assertEquals(9, afterFour.get("canonical"));
        assertEquals(6, afterFour.get("golden"));
        assertEquals(8, afterFour.get("families"));
        assertEquals(ramBefore, ramGoldenId(), "Ram Singh keeps the same Golden ID");
        assertEquals(4, sql("select count(*) from golden_resident_membership m join canonical_resident c"
                + " on c.canonical_resident_id=m.canonical_resident_id where m.effective_to is null"
                + " and m.golden_resident_id='" + ramBefore + "'::uuid"), "Ram Singh has ST1-ST4 sources");
        JsonNode evidence = json.readTree(get("/api/studio/portability"));
        for (JsonNode state : evidence.path("states")) {
            assertEquals(0, state.path("platformCodeChanges").size(), state.toString());
            assertEquals("generic-file-ingest.hpl", state.path("hopTemplates").get(0).asText());
        }
        Files.writeString(REPORT, report.toString(), StandardCharsets.UTF_8);
    }

    private void onboard(String packId, String stateCode, String name, String sample, StringBuilder report)
            throws Exception {
        JsonNode draft = json.readTree(post("/api/studio/drafts",
                Map.of("packId", packId, "stateCode", stateCode, "displayName", name)));
        String id = draft.path("draftId").asText();
        assertEquals(1, sql("select count(*) from studio_draft where draft_id='" + id + "'::uuid"));
        Path file = SampleData.REPOSITORY_ROOT.resolve(sample);
        mvc.perform(multipart("/api/studio/drafts/" + id + "/upload")
                        .file(new MockMultipartFile("file", file.getFileName().toString(), "text/csv",
                                Files.readAllBytes(file))))
                .andExpect(status().isOk());
        JsonNode profile = json.readTree(get("/api/studio/drafts/" + id + "/profile"));
        assertTrue(profile.path("columns").size() > 0);
        JsonNode suggestions = json.readTree(get("/api/studio/drafts/" + id + "/suggestions"));
        JsonNode current = json.readTree(get("/api/studio/drafts/" + id));

        ObjectNode configuration = json.createObjectNode();
        ArrayNode mappings = configuration.putArray("mappings");
        for (JsonNode column : suggestions.path("columns")) {
            for (JsonNode target : column.path("targets")) {
                ObjectNode rule = mappings.addObject();
                rule.put("sourceField", column.path("column").asText());
                rule.put("entity", target.path("entity").asText());
                rule.put("target", target.path("target").asText());
                rule.set("conversion", target.path("conversion"));
                rule.set("crosswalk", target.path("crosswalk"));
                if ("name".equals(target.path("target").asText())) {
                    rule.put("required", true);
                }
            }
        }
        List<String> corrections = new ArrayList<>();
        ArrayNode crosswalks = configuration.putArray("crosswalks");
        for (JsonNode crosswalk : suggestions.path("crosswalks")) {
            ObjectNode copy = crosswalk.deepCopy();
            for (JsonNode entry : copy.path("entries")) {
                String typed = CORRECTIONS.getOrDefault(packId, Map.of())
                        .getOrDefault(crosswalk.path("id").asText(), Map.of())
                        .get(entry.path("sourceValue").asText());
                if (typed != null) {
                    corrections.add(crosswalk.path("id").asText() + ": " + entry.path("sourceValue").asText()
                            + " -> " + typed + " (suggested: " + entry.path("targetValue").asText("none") + ")");
                    ((ObjectNode) entry).put("targetValue", typed);
                }
                assertTrue(!entry.path("targetValue").isNull() && !entry.path("targetValue").asText().isBlank(),
                        packId + " crosswalk " + crosswalk.path("id").asText() + " still needs a value for "
                                + entry.path("sourceValue").asText());
            }
            crosswalks.add(copy);
        }
        configuration.set("rules", current.path("rules"));
        configuration.set("recordIdField", suggestions.path("recordIdField"));
        configuration.set("recordKeyFields", suggestions.path("recordKeyFields"));
        put("/api/studio/drafts/" + id + "/configuration", configuration);
        assertEquals(1, sql("select count(*) from studio_draft where draft_id='" + id + "'::uuid"));

        JsonNode preview = json.readTree(get("/api/studio/drafts/" + id + "/preview"));
        assertEquals(0, preview.path("blockingErrors").asInt(), preview.toString());
        assertTrue(preview.path("packError").isNull(), preview.toString());
        JsonNode published = json.readTree(post("/api/studio/drafts/" + id + "/publish", Map.of()));
        assertEquals("PUBLISHED", published.path("status").asText());
        assertEquals(1, sql("select count(*) from state_pack_document where pack_id='" + packId + "'"));
        JsonNode started = json.readTree(post("/api/studio/drafts/" + id + "/onboard", Map.of()));
        String run = started.path("runId").asText();
        JsonNode detail = waitFor(run);
        assertEquals("COMPLETED", detail.path("status").asText(), detail.toString());
        assertEquals("ONBOARDED", waitForStatus(id));

        report.append("## ").append(name).append(" (").append(packId).append(", ")
                .append(started.path("processingMode").asText()).append(")\n\n");
        report.append("Corrections typed on the Crosswalks step: ")
                .append(corrections.isEmpty() ? "none" : String.join("; ", corrections)).append("\n\n");
        report.append(PackComparison.compare(packs.find(packId).orElseThrow(), reference(packId))).append("\n");
    }

    private static Path reference(String packId) {
        Path hand = SampleData.REPOSITORY_ROOT.resolve("state-packs").resolve(packId);
        return Files.isDirectory(hand) ? hand : SampleData.REPOSITORY_ROOT.resolve("m10-state-packs").resolve(packId);
    }

    private JsonNode waitFor(String run) throws Exception {
        for (int i = 0; i < 600; i++) {
            JsonNode detail = json.readTree(get("/api/onboarding/runs/" + run));
            if (!"RUNNING".equals(detail.path("status").asText())) {
                return detail;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Run " + run + " did not finish");
    }

    private String waitForStatus(String draftId) throws Exception {
        String status = null;
        for (int i = 0; i < 100; i++) {
            status = json.readTree(get("/api/studio/drafts/" + draftId)).path("status").asText();
            if ("ONBOARDED".equals(status)) {
                return status;
            }
            Thread.sleep(50);
        }
        return status;
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("raw", sql("select count(*) from raw_record"));
        counts.put("canonical", sql("select count(*) from canonical_resident"));
        counts.put("autoMatch", sql("select count(*) from resident_match_pair where decision='AUTO_MATCH'"));
        counts.put("golden", sql("select count(*) from golden_resident where status='ACTIVE'"));
        counts.put("families", sql("select count(*) from golden_family where status='ACTIVE'"));
        counts.put("benefits", sql("select count(*) from benefit"));
        return counts;
    }

    private String ramGoldenId() {
        var ids = new Sql(connections).column("select distinct m.golden_resident_id from golden_resident_membership m"
                + " join canonical_resident c on c.canonical_resident_id=m.canonical_resident_id"
                + " where m.effective_to is null and c.canonical_payload->>'name'='Ram Singh'", UUID.class);
        assertEquals(1, ids.size(), "every Ram Singh record is in one Golden Resident");
        return ids.getFirst().toString();
    }

    private int sql(String query) {
        try (var c = connections.open(); var s = c.createStatement(); var r = s.executeQuery(query)) {
            r.next();
            return r.getInt(1);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String get(String path) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private String post(String path, Object body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
    }

    private void put(String path, Object body) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private static Path temporary(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path directory) throws Exception {
        if (!Files.exists(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }
}
