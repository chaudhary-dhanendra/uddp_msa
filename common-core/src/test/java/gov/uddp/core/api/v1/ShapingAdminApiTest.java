package gov.uddp.core.api.v1;

import static gov.uddp.core.testsupport.TestSecurity.as;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.security.DepartmentScope;
import gov.uddp.core.security.Permission;
import gov.uddp.core.security.UddpPrincipal;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import gov.uddp.core.testsupport.TestSecurity;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The shaping administration API: word lists are proposed by one user and applied only when another approves, "test
 * on a value" runs the registered parser, and Tier-3 plug-ins show their startup status and are approved or revoked
 * the same way, writing approved-plugins.yaml and the PLUGIN_* audit events. Runs on a copy of the platform folder.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ShapingAdminApiTest {
    static final Path ROOT = temporary("uddp-shaping-admin");
    static final Path PLATFORM = ROOT.resolve("platform");
    static final Path PLUGINS = ROOT.resolve("plugins");
    static final Path APPROVED = ROOT.resolve("approved-plugins.yaml");
    static String pendingSha;
    static String okSha;
    static String brokenSha;

    static final UddpPrincipal MAKER = principal("maker", Permission.SHAPING_VIEW, Permission.SHAPING_MANAGE);
    static final UddpPrincipal CHECKER = principal("checker", Permission.SHAPING_VIEW, Permission.SHAPING_APPROVE);
    static final UddpPrincipal VIEWER = principal("viewer", Permission.SHAPING_VIEW);

    @TestConfiguration
    static class Database {
        @Bean
        @Primary
        PostgresConnectionFactory shapingConnections() {
            return TestDatabase.freshMigrated();
        }
    }

    @DynamicPropertySource
    static void locations(DynamicPropertyRegistry registry) throws IOException {
        copy(Path.of("../platform"), PLATFORM);
        Files.createDirectories(PLUGINS);
        okSha = jar("ok.jar", "name: ok-plugin\nversion: 1.0\napiVersion: 1.0\n");
        brokenSha = jar("broken.jar", "name: broken-plugin\nversion: 1.0\napiVersion: 2.0\n");
        pendingSha = jar("pending.jar", "name: pending-plugin\nversion: 1.0\napiVersion: 1.0\n");
        Files.writeString(APPROVED, "approved:\n  - sha256: " + okSha + "\n  - sha256: " + brokenSha + "\n");
        registry.add("uddp.platform-directory", PLATFORM::toString);
        registry.add("uddp.shaping.plugins-dir", PLUGINS::toString);
        registry.add("uddp.shaping.approved-plugins-file", APPROVED::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    PostgresConnectionFactory connections;

    @Test
    @Order(1)
    void wordListsAreListedWithTheirGroups() throws Exception {
        JsonNode lists = json.readTree(call(get("/api/shaping/word-lists"), VIEWER, 200));
        List<String> ids = lists.findValuesAsText("id");
        assertTrue(ids.containsAll(List.of("honorifics", "deceased-markers", "relation-markers", "name-variants",
                "ditto-tokens", "address-tokens", "geo-aliases")), ids.toString());

        JsonNode markers = json.readTree(call(get("/api/shaping/word-lists/relation-markers"), VIEWER, 200));
        JsonNode first = markers.path("groups").get(0);
        assertEquals("markers", first.path("section").asText());
        assertEquals("FATHER", first.path("attrs").path("relation").asText());
        assertTrue(first.path("values").toString().contains("S/O"));

        JsonNode variants = json.readTree(call(get("/api/shaping/word-lists/name-variants"), VIEWER, 200));
        assertEquals("MOHAMMED", variants.path("groups").get(0).path("key").asText());

        call(get("/api/shaping/word-lists/nope"), VIEWER, 404);
        call(get("/api/shaping/word-lists"), principal("nobody"), 403);
    }

    @Test
    @Order(2)
    void aWordListChangeNeedsAnotherUserAndThenTakesEffect() throws Exception {
        String before = testValue("name-normalise@1.0", "Dr Ram Kumar");
        assertTrue(before.contains("DR"), before);

        JsonNode honorifics = json.readTree(call(get("/api/shaping/word-lists/honorifics"), VIEWER, 200));
        String proposal = json.writeValueAsString(java.util.Map.of("groups", List.of(java.util.Map.of(
                "section", "honorifics", "key", "honorifics", "attrs", java.util.Map.of(),
                "values", withExtra(honorifics.path("groups").get(0).path("values"), "Dr")))));

        call(post("/api/shaping/word-lists/honorifics/proposals").contentType(MediaType.APPLICATION_JSON).content(proposal),
                VIEWER, 403);
        JsonNode change = json.readTree(call(post("/api/shaping/word-lists/honorifics/proposals")
                .contentType(MediaType.APPLICATION_JSON).content(proposal), MAKER, 200));
        assertEquals("PENDING_APPROVAL", change.path("status").asText());
        assertEquals("maker", change.path("createdBy").asText());
        String id = change.path("changeId").asText();
        assertFalse(Files.readString(PLATFORM.resolve("reference-data/shaping/honorifics.yaml")).contains("Dr"));
        assertEquals(id, json.readTree(call(get("/api/shaping/word-lists/honorifics"), VIEWER, 200))
                .path("pendingChangeId").asText());

        call(post("/api/shaping/word-lists/honorifics/proposals").contentType(MediaType.APPLICATION_JSON).content(proposal),
                MAKER, 409);
        call(post("/api/shaping/changes/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content("{}"),
                MAKER, 403);
        call(post("/api/shaping/changes/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content("{}"),
                VIEWER, 403);
        call(post("/api/shaping/changes/" + id + "/reject").contentType(MediaType.APPLICATION_JSON).content("{}"),
                CHECKER, 400);

        JsonNode approved = json.readTree(call(post("/api/shaping/changes/" + id + "/approve")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ok\"}"), CHECKER, 200));
        assertEquals("APPROVED", approved.path("status").asText());
        assertEquals("checker", approved.path("decidedBy").asText());

        String file = Files.readString(PLATFORM.resolve("reference-data/shaping/honorifics.yaml"));
        assertTrue(file.startsWith("# Stripped from the normalised name only"), "the file's comment is kept");
        assertTrue(file.contains("Dr"));
        String after = testValue("name-normalise@1.0", "Dr Ram Kumar");
        assertFalse(after.contains("DR"), "the parser reads the approved list at once: " + after);
        assertTrue(after.contains("RAM KUMAR"), after);
    }

    @Test
    @Order(3)
    void invalidAndRejectedWordListChangesLeaveTheFileAlone() throws Exception {
        String badRegex = "{\"groups\":[{\"section\":\"prefixes\",\"key\":\"prefixes\",\"values\":[\"Vill\"]},"
                + "{\"section\":\"suffixes\",\"key\":\"suffixes\",\"values\":[\"(unclosed\"]}]}";
        call(post("/api/shaping/word-lists/address-tokens/proposals").contentType(MediaType.APPLICATION_JSON)
                .content(badRegex), MAKER, 400);
        call(post("/api/shaping/word-lists/honorifics/proposals").contentType(MediaType.APPLICATION_JSON)
                .content("{\"groups\":[{\"section\":\"honorifics\",\"key\":\"honorifics\",\"values\":[]}]}"), MAKER, 400);

        String proposal = "{\"groups\":[{\"section\":\"markers\",\"key\":\"markers\",\"values\":[\"Late\",\"Gone\"]}]}";
        JsonNode change = json.readTree(call(post("/api/shaping/word-lists/deceased-markers/proposals")
                .contentType(MediaType.APPLICATION_JSON).content(proposal), MAKER, 200));
        JsonNode rejected = json.readTree(call(post("/api/shaping/changes/" + change.path("changeId").asText() + "/reject")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"not now\"}"), CHECKER, 200));
        assertEquals("REJECTED", rejected.path("status").asText());
        assertFalse(Files.readString(PLATFORM.resolve("reference-data/shaping/deceased-markers.yaml")).contains("Gone"));

        JsonNode rejectedChanges = json.readTree(call(get("/api/shaping/changes?status=REJECTED&kind=WORD_LIST"),
                VIEWER, 200));
        assertEquals(1, rejectedChanges.size());
    }

    @Test
    @Order(4)
    void testOnAValueRunsAnyRegisteredParser() throws Exception {
        JsonNode relation = json.readTree(call(post("/api/shaping/word-lists/test").contentType(MediaType.APPLICATION_JSON)
                .content("{\"parser\":\"name-relation@1.0\",\"value\":\"Tsering S/O Dorje\"}"), VIEWER, 200));
        assertEquals("FATHER", relation.path("values").path("relation.type").asText());
        assertTrue(relation.path("confidence").asDouble() > 0.9);
        call(post("/api/shaping/word-lists/test").contentType(MediaType.APPLICATION_JSON)
                .content("{\"parser\":\"nope@1.0\",\"value\":\"x\"}"), VIEWER, 400);
        call(post("/api/shaping/word-lists/test").contentType(MediaType.APPLICATION_JSON)
                .content("{\"parser\":\"name-relation@1.0\"}"), VIEWER, 400);
    }

    @Test
    @Order(5)
    void builtInOperationsListTheirVersions() throws Exception {
        JsonNode operations = json.readTree(call(get("/api/shaping/operations"), VIEWER, 200)).path("builtIn");
        boolean headerDetect = false;
        boolean nameParser = false;
        for (JsonNode item : operations) {
            headerDetect |= "operation".equals(item.path("kind").asText()) && "header-detect@1.0".equals(item.path("key").asText())
                    && "1.0".equals(item.path("version").asText());
            nameParser |= "parser".equals(item.path("kind").asText()) && "name-normalise".equals(item.path("name").asText());
        }
        assertTrue(headerDetect && nameParser, operations.toString());
    }

    @Test
    @Order(6)
    void pluginsShowTheirStartupStatusAndAreAuditedWhenTheyLoadOrAreRejected() throws Exception {
        JsonNode plugins = json.readTree(call(get("/api/shaping/plugins"), VIEWER, 200));
        assertEquals("LOADED", pluginStatus(plugins, okSha));
        assertEquals("REJECTED", pluginStatus(plugins, brokenSha));
        assertEquals("NOT_APPROVED", pluginStatus(plugins, pendingSha));
        JsonNode ok = byHash(plugins, okSha);
        assertEquals("ok-plugin", ok.path("name").asText());
        assertEquals("ok.jar", ok.path("fileName").asText());
        assertTrue(ok.path("approved").asBoolean());
        assertTrue(byHash(plugins, brokenSha).path("reason").asText().contains("apiVersion"));
        assertFalse(byHash(plugins, pendingSha).path("approved").asBoolean());

        Sql sql = new Sql(connections);
        assertEquals(1, sql.count("select count(*) from app_audit_event where action = 'PLUGIN_LOADED' and resource_id = '"
                + okSha + "'"));
        assertEquals(2, sql.count("select count(*) from app_audit_event where action = 'PLUGIN_REJECTED'"));
    }

    @Test
    @Order(7)
    void approvingAndRevokingAPluginIsMakerCheckerAndWritesTheApprovalFile() throws Exception {
        String url = "/api/shaping/plugins/" + pendingSha + "/proposals";
        call(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"REVOKE\"}"), MAKER, 409);
        call(post("/api/shaping/plugins/" + "0".repeat(64) + "/proposals").contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"APPROVE\"}"), MAKER, 400);
        call(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"APPROVE\"}"), VIEWER, 403);

        JsonNode change = json.readTree(call(post(url).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"APPROVE\"}"), MAKER, 200));
        String id = change.path("changeId").asText();
        assertEquals("PLUGIN", change.path("kind").asText());
        assertEquals(id, byHash(json.readTree(call(get("/api/shaping/plugins"), VIEWER, 200)), pendingSha)
                .path("pendingChangeId").asText());
        assertFalse(Files.readString(APPROVED).contains(pendingSha), "nothing is written before the approval");
        call(post("/api/shaping/changes/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content("{}"),
                MAKER, 403);
        call(post("/api/shaping/changes/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content("{}"),
                CHECKER, 200);

        assertTrue(Files.readString(APPROVED).contains(pendingSha));
        assertTrue(Files.readString(APPROVED).contains("approvedBy: \"checker\"") || Files.readString(APPROVED).contains("approvedBy: checker"));
        assertTrue(byHash(json.readTree(call(get("/api/shaping/plugins"), VIEWER, 200)), pendingSha)
                .path("approved").asBoolean());

        JsonNode revoke = json.readTree(call(post(url).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"REVOKE\"}"), MAKER, 200));
        call(post("/api/shaping/changes/" + revoke.path("changeId").asText() + "/approve")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), CHECKER, 200);
        assertFalse(Files.readString(APPROVED).contains(pendingSha));
        assertTrue(Files.readString(APPROVED).contains(okSha), "other approvals stay");

        Sql sql = new Sql(connections);
        assertEquals(1, sql.count("select count(*) from app_audit_event where action = 'PLUGIN_APPROVED' and resource_id = '"
                + pendingSha + "' and actor_username = 'checker'"));
        assertEquals(1, sql.count("select count(*) from app_audit_event where action = 'PLUGIN_REVOKED' and resource_id = '"
                + pendingSha + "' and actor_username = 'checker'"));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private String testValue(String parser, String value) throws Exception {
        return json.readTree(call(post("/api/shaping/word-lists/test").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("parser", parser, "value", value))), VIEWER, 200))
                .path("values").toString();
    }

    private String call(MockHttpServletRequestBuilder request, UddpPrincipal who, int expected) throws Exception {
        return mvc.perform(request.with(as(who))).andExpect(status().is(expected)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> withExtra(JsonNode values, String extra) {
        List<String> all = new java.util.ArrayList<>();
        values.forEach(v -> all.add(v.asText()));
        all.add(extra);
        return all;
    }

    private static JsonNode byHash(JsonNode plugins, String sha) {
        for (JsonNode plugin : plugins) {
            if (sha.equals(plugin.path("sha256").asText())) {
                return plugin;
            }
        }
        throw new AssertionError("no plugin with hash " + sha + " in " + plugins);
    }

    private static String pluginStatus(JsonNode plugins, String sha) {
        return byHash(plugins, sha).path("status").asText();
    }

    private static UddpPrincipal principal(String name, Permission... permissions) {
        return TestSecurity.principal(name, Set.of("TEST"),
                permissions.length == 0 ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(List.of(permissions)),
                List.of(DepartmentScope.ALL));
    }

    private static String jar(String name, String pluginYaml) throws IOException {
        Path file = PLUGINS.resolve(name);
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out)) {
            jar.putNextEntry(new JarEntry("plugin.yaml"));
            jar.write(pluginYaml.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path source : files.sorted(Comparator.naturalOrder()).toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static Path temporary(String prefix) {
        try {
            Path dir = Files.createTempDirectory(prefix);
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
