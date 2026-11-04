package gov.uddp.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/**
 * Optional OIDC single sign-on against a real Keycloak (Testcontainers): the authorization code flow through the
 * running backend, the user created on first sign-in with roles and state scope from Keycloak groups, and a user
 * without a mapped group refused. The realm, client secret and passwords are generated for each run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OidcKeycloakTest {
    static final String CLIENT_SECRET = UUID.randomUUID().toString();
    static final String ALICE_PASSWORD = "Alice-" + UUID.randomUUID();
    static final String BOB_PASSWORD = "Bob-" + UUID.randomUUID();

    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.0.7")
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(8080)
            .withCopyToContainer(Transferable.of(realm()), "/opt/keycloak/data/import/uddp-realm.json")
            .waitingFor(Wait.forHttp("/realms/uddp/.well-known/openid-configuration").forPort(8080)
                    .withStartupTimeout(Duration.ofMinutes(4)));

    static {
        KEYCLOAK.start();
    }

    @TestConfiguration
    static class Database {
        @Bean
        @Primary
        PostgresConnectionFactory oidcConnections() {
            return TestDatabase.freshMigrated();
        }
    }

    @DynamicPropertySource
    static void oidc(DynamicPropertyRegistry registry) {
        registry.add("uddp.security.oidc.enabled", () -> "true");
        registry.add("uddp.security.oidc.issuer-uri", OidcKeycloakTest::issuer);
        registry.add("uddp.security.oidc.client-id", () -> "uddp");
        registry.add("uddp.security.oidc.client-secret", () -> CLIENT_SECRET);
        registry.add("uddp.security.oidc.group-roles.uddp-stewards", () -> "DATA_STEWARD");
        registry.add("uddp.security.oidc.group-departments.uddp-st1", () -> "DEPT-1");
        registry.add("uddp.security.oidc.success-url", () -> "/signed-in");
    }

    @LocalServerPort
    int port;

    @Autowired
    UserAccountStore users;

    @Autowired
    PostgresConnectionFactory connections;

    final ObjectMapper json = new ObjectMapper();

    @Test
    void aKeycloakUserSignsInWithRolesAndStatesFromGroupsAndLocalSignInStaysAvailable() throws Exception {
        var browser = browser();
        String landing = signIn(browser, "alice", ALICE_PASSWORD);
        assertEquals("/signed-in", URI.create(landing).getPath());

        var me = browser.send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/me")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, me.statusCode(), me.body());
        JsonNode body = json.readTree(me.body());
        assertEquals("alice", body.path("username").asText());
        assertEquals("OIDC", body.path("authSource").asText());
        assertEquals("DATA_STEWARD", body.path("roles").get(0).asText());
        assertEquals("DEPT-1", body.path("departmentScope").get(0).asText());
        assertTrue(body.path("permissions").toString().contains("STEWARDSHIP_DECIDE"));

        UserAccount alice = users.findByUsername("alice").orElseThrow();
        assertEquals("OIDC", alice.authSource());
        assertTrue(alice.externalSubject() != null && !alice.externalSubject().isBlank());
        Sql sql = new Sql(connections);
        assertEquals(1, sql.count("select count(*) from app_audit_event where action = 'LOGIN' and result = 'SUCCESS'"
                + " and actor_username = 'alice' and metadata->>'method' = 'OIDC'"));

        var providers = browser.send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/providers")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode methods = json.readTree(providers.body());
        assertTrue(methods.path("local").asBoolean(), "local sign-in remains the fallback");
        assertTrue(methods.path("oidc").asBoolean());
    }

    @Test
    void aKeycloakUserWithoutAMappedGroupIsRefused() throws Exception {
        var browser = browser();
        String landing = signIn(browser, "bob", BOB_PASSWORD);
        assertTrue(landing.contains("sso=denied"), landing);
        var me = browser.send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/me")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, me.statusCode());
        assertTrue(users.findByUsername("bob").isEmpty());
        assertEquals(1, new Sql(connections).count("select count(*) from app_audit_event where action = 'LOGIN'"
                + " and result = 'FAILURE' and actor_username = 'bob' and metadata->>'reason' = 'NO_MAPPED_ROLE'"));
    }

    /** The authorization code flow as a browser does it; returns where the backend finally redirects. */
    private String signIn(Browser browser, String username, String password) throws Exception {
        var start = browser.send(HttpRequest.newBuilder(URI.create(base() + "/oauth2/authorization/uddp")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, start.statusCode());
        String authorize = start.headers().firstValue("Location").orElseThrow();
        var loginPage = browser.send(HttpRequest.newBuilder(URI.create(authorize)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher action = Pattern.compile("action=\"([^\"]+)\"").matcher(loginPage.body());
        assertTrue(action.find(), loginPage.body());
        String form = Map.of("username", username, "password", password, "credentialId", "").entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        var submitted = browser.send(HttpRequest.newBuilder(URI.create(action.group(1).replace("&amp;", "&")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, submitted.statusCode(), submitted.body());
        String callback = submitted.headers().firstValue("Location").orElseThrow();
        assertTrue(callback.startsWith(base() + "/login/oauth2/code/uddp"), callback);
        var done = browser.send(HttpRequest.newBuilder(URI.create(callback)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, done.statusCode(), done.body());
        return done.headers().firstValue("Location").orElseThrow();
    }

    private static Browser browser() {
        return new Browser();
    }

    /**
     * A minimal browser: follows no redirects and keeps cookies per host and port (the JDK cookie manager drops
     * cookies for "localhost").
     */
    static final class Browser {
        private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        private final Map<String, Map<String, String>> cookies = new java.util.HashMap<>();

        HttpResponse<String> send(HttpRequest request, HttpResponse.BodyHandler<String> handler) throws Exception {
            String origin = request.uri().getHost() + ":" + request.uri().getPort();
            Map<String, String> jar = cookies.computeIfAbsent(origin, key -> new java.util.LinkedHashMap<>());
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                    .method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
            request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
            if (!jar.isEmpty()) {
                builder.header("Cookie", jar.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining("; ")));
            }
            HttpResponse<String> response = client.send(builder.build(), handler);
            for (String header : response.headers().allValues("Set-Cookie")) {
                String pair = header.split(";", 2)[0];
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    String name = pair.substring(0, equals).strip();
                    String value = pair.substring(equals + 1).strip();
                    if (value.isEmpty() || header.toLowerCase(java.util.Locale.ROOT).contains("max-age=0")) {
                        jar.remove(name);
                    } else {
                        jar.put(name, value);
                    }
                }
            }
            return response;
        }
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private static String issuer() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/uddp";
    }

    private static String realm() {
        Map<String, Object> groupsMapper = Map.of("name", "groups", "protocol", "openid-connect",
                "protocolMapper", "oidc-group-membership-mapper",
                "config", Map.of("full.path", "false", "id.token.claim", "true", "access.token.claim", "true",
                        "userinfo.token.claim", "true", "claim.name", "groups"));
        Map<String, Object> client = Map.of("clientId", "uddp", "enabled", true, "publicClient", false,
                "secret", CLIENT_SECRET, "redirectUris", List.of("*"), "standardFlowEnabled", true,
                "directAccessGrantsEnabled", false, "protocol", "openid-connect",
                "protocolMappers", List.of(groupsMapper));
        Map<String, Object> realm = Map.of("realm", "uddp", "enabled", true, "sslRequired", "none",
                "groups", List.of(Map.of("name", "uddp-stewards"), Map.of("name", "uddp-st1"), Map.of("name", "other")),
                "clients", List.of(client),
                "users", List.of(user("alice", "Alice", ALICE_PASSWORD, List.of("/uddp-stewards", "/uddp-st1")),
                        user("bob", "Bob", BOB_PASSWORD, List.of("/other"))));
        try {
            return new ObjectMapper().writeValueAsString(realm);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> user(String username, String firstName, String password, List<String> groups) {
        return Map.of("username", username, "enabled", true, "email", username + "@example.org",
                "emailVerified", true, "firstName", firstName, "lastName", "Tester",
                "credentials", List.of(Map.of("type", "password", "value", password, "temporary", false)),
                "groups", groups);
    }
}
