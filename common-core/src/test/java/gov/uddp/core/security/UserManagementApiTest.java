package gov.uddp.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.security.UserAccountStore.NewUser;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * P1 Part 6 through the real filter chain: a platform administrator creates a user, assigns a role and the ST1
 * scope, and that user signs in (changing the one-time password) and sees only ST1 data; everyone else gets 403 on
 * every user-management endpoint; the last platform administrator is protected; a disabled user cannot sign in and
 * loses their session; every change is audited, never with the one-time password.
 */
@SpringBootTest
class UserManagementApiTest {
    static final String STRONG = "Correct-Horse-7-Battery";
    static final String NEW_PASSWORD = "Another-Strong-9-Phrase";

    @TestConfiguration
    static class Database {
        @Bean
        @Primary
        PostgresConnectionFactory userManagementConnections() {
            PostgresConnectionFactory connections = TestDatabase.freshMigrated();
            var service = SampleData.processing(connections, SampleData.loader(SampleData.STATE_PACKS));
            SampleData.landAll(connections, SampleData.STATE_PACKS).values()
                    .forEach(batches -> batches.forEach(service::processBatch));
            return connections;
        }
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    PostgresConnectionFactory connections;

    @Autowired
    UserAccountStore users;

    @Autowired
    UserAdminService admin;

    @Autowired
    PasswordEncoder encoder;

    final ObjectMapper json = new ObjectMapper();
    MockMvc mvc;

    @BeforeEach
    void plainMockMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void adminCreatesAUserWithRoleAndDepartmentScopeWhoThenSeesOnlyThatDepartmentsSources() throws Exception {
        Browser root = signIn(account("root.admin", "PLATFORM_ADMIN", "ALL"));
        String username = "st1.engineer." + suffix();
        JsonNode created = root.send(HttpMethod.POST, "/api/v1/users", Map.of("username", username,
                        "displayName", "DEPT-1 engineer", "roles", List.of("PROGRAM_USER"), "departmentScope", List.of("DEPT-2")))
                .andExpect(status().isCreated()).json();
        String userId = created.path("user").path("userId").asText();
        String oneTime = created.path("oneTimePassword").asText();
        assertTrue(created.path("user").path("mustChangePassword").asBoolean());
        root.send(HttpMethod.PUT, "/api/v1/users/" + userId + "/roles", Map.of("roles", List.of("DATA_ENGINEER")))
                .andExpect(status().isOk());
        root.send(HttpMethod.PUT, "/api/v1/users/" + userId + "/department-scope",
                Map.of("departmentScope", List.of("NO-SUCH-DEPARTMENT"))).andExpect(status().isBadRequest());
        JsonNode scoped = root.send(HttpMethod.PUT, "/api/v1/users/" + userId + "/department-scope",
                Map.of("departmentScope", List.of("dept-1"))).andExpect(status().isOk()).json();
        assertEquals("[\"DATA_ENGINEER\"]", scoped.path("roles").toString());
        assertEquals("[\"DEPT-1\"]", scoped.path("departmentScope").toString());

        Browser user = new Browser();
        user.send(HttpMethod.POST, "/api/v1/auth/login", Map.of("username", username, "password", oneTime))
                .andExpect(status().isOk());
        user.send(HttpMethod.GET, "/api/v1/canonical-residents", null).andExpect(status().isForbidden());
        user.send(HttpMethod.POST, "/api/v1/auth/change-password",
                Map.of("currentPassword", oneTime, "newPassword", NEW_PASSWORD)).andExpect(status().isOk());
        JsonNode residents = user.send(HttpMethod.GET, "/api/v1/canonical-residents?size=100", null)
                .andExpect(status().isOk()).json();
        List<String> states = new ArrayList<>();
        residents.path("items").forEach(item -> states.add(item.path("stateCode").asText()));
        assertFalse(states.isEmpty());
        assertEquals(List.of("ST1"), states.stream().distinct().toList());
        assertTrue(new Sql(connections).count("select count(*) from canonical_resident where state_code <> 'ST1'") > 0);

        JsonNode reset = root.send(HttpMethod.POST, "/api/v1/users/" + userId + "/reset-password", Map.of())
                .andExpect(status().isOk()).json();
        assertTrue(reset.path("user").path("mustChangePassword").asBoolean());
        assertNotNull(reset.path("oneTimePassword").asText(null));

        Sql sql = new Sql(connections);
        for (String action : List.of("USER_CREATE", "USER_ROLES_UPDATE", "USER_SCOPE_UPDATE", "USER_PASSWORD_RESET")) {
            assertEquals(1, sql.count("select count(*) from app_audit_event where action = ? and result = 'SUCCESS'"
                    + " and resource_id = ? and actor_username = ?", action, userId, root.username), action);
        }
        assertEquals("[\"DEPT-1\"]", sql.text("select metadata->>'departmentScopeAfter' from app_audit_event"
                + " where action = 'USER_SCOPE_UPDATE' and result = 'SUCCESS' and resource_id = ?", userId));
        assertEquals(0, sql.count("select count(*) from app_audit_event where metadata::text like ?"
                + " or metadata::text like ?", "%" + oneTime + "%", "%" + reset.path("oneTimePassword").asText() + "%"));
    }

    @Test
    void nonAdministratorsGet403OnEveryUserManagementEndpoint() throws Exception {
        String target = users.findByUsername(account("target.user", "PROGRAM_USER", "DEPT-1")).orElseThrow()
                .userId().toString();
        for (String role : List.of("STATE_ADMIN", "AUDITOR", "DATA_STEWARD")) {
            Browser browser = signIn(account("not.admin", role, "ALL"));
            List<Object[]> calls = List.of(
                    new Object[] {HttpMethod.GET, "/api/v1/users", null},
                    new Object[] {HttpMethod.GET, "/api/v1/users/" + target, null},
                    new Object[] {HttpMethod.GET, "/api/v1/roles", null},
                    new Object[] {HttpMethod.POST, "/api/v1/users", Map.of("username", "x." + suffix(),
                            "roles", List.of("PLATFORM_ADMIN"), "departmentScope", List.of("ALL"))},
                    new Object[] {HttpMethod.POST, "/api/v1/users/" + target + "/enable", Map.of()},
                    new Object[] {HttpMethod.POST, "/api/v1/users/" + target + "/disable", Map.of()},
                    new Object[] {HttpMethod.POST, "/api/v1/users/" + target + "/unlock", Map.of()},
                    new Object[] {HttpMethod.POST, "/api/v1/users/" + target + "/reset-password", Map.of()},
                    new Object[] {HttpMethod.PUT, "/api/v1/users/" + target + "/roles",
                            Map.of("roles", List.of("PLATFORM_ADMIN"))},
                    new Object[] {HttpMethod.PUT, "/api/v1/users/" + target + "/department-scope",
                            Map.of("departmentScope", List.of("ALL"))});
            for (Object[] call : calls) {
                browser.send((HttpMethod) call[0], (String) call[1], call[2])
                        .andExpect(status().isForbidden());
            }
        }
        assertEquals(Set.of("PROGRAM_USER"), users.findById(UUID.fromString(target)).orElseThrow().roles());
    }

    @Test
    void theLastPlatformAdministratorIsProtected() throws Exception {
        String rootName = account("solo.admin", "PLATFORM_ADMIN", "ALL");
        UUID rootId = users.findByUsername(rootName).orElseThrow().userId();
        Browser root = signIn(rootName);
        root.send(HttpMethod.POST, "/api/v1/users/" + rootId + "/disable", Map.of()).andExpect(status().isConflict());
        root.send(HttpMethod.PUT, "/api/v1/users/" + rootId + "/roles", Map.of("roles", List.of("AUDITOR")))
                .andExpect(status().isConflict());
        assertTrue(users.findById(rootId).orElseThrow().roles().contains("PLATFORM_ADMIN"));

        // Every other platform administrator disabled: an operator who is not the target still cannot remove the last.
        new Sql(connections).execute("update app_user set enabled = false where user_id <> ? and user_id in"
                + " (select user_id from app_user_role where role_code = 'PLATFORM_ADMIN')", rootId);
        SecurityContextHolder.getContext().setAuthentication(new UddpAuthentication(new UddpPrincipal(null,
                "operator", "operator", Set.of(), Set.of(Permission.USER_MANAGE), DepartmentScope.everyDepartment(),
                StateScope.everyState(),
                false, "LOCAL")));
        try {
            assertThrows(UserConflictException.class, () -> admin.setEnabled(rootId, false));
            assertThrows(UserConflictException.class, () -> admin.setRoles(rootId, List.of("AUDITOR")));
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertTrue(users.findById(rootId).orElseThrow().enabled());
    }

    @Test
    void aDisabledUserLosesTheSessionAndCannotSignIn() throws Exception {
        Browser root = signIn(account("gate.admin", "PLATFORM_ADMIN", "ALL"));
        String name = account("leaver", "PROGRAM_USER", "ST1");
        UUID id = users.findByUsername(name).orElseThrow().userId();
        Browser leaver = signIn(name);
        leaver.send(HttpMethod.GET, "/api/v1/auth/me", null).andExpect(status().isOk());

        root.send(HttpMethod.POST, "/api/v1/users/" + id + "/disable", Map.of()).andExpect(status().isOk());

        leaver.send(HttpMethod.GET, "/api/v1/auth/me", null).andExpect(status().isUnauthorized());
        new Browser().send(HttpMethod.POST, "/api/v1/auth/login", Map.of("username", name, "password", STRONG))
                .andExpect(status().isUnauthorized());
        JsonNode listed = root.send(HttpMethod.GET, "/api/v1/users?status=DISABLED&q=leaver", null)
                .andExpect(status().isOk()).json();
        assertEquals(name, listed.path(0).path("username").asText());

        root.send(HttpMethod.POST, "/api/v1/users/" + id + "/enable", Map.of()).andExpect(status().isOk());
        signIn(name);
        Sql sql = new Sql(connections);
        assertEquals(1, sql.count("select count(*) from app_audit_event where action = 'USER_DISABLE'"
                + " and result = 'SUCCESS' and resource_id = ?", id.toString()));
        assertEquals(1, sql.count("select count(*) from app_audit_event where action = 'USER_ENABLE'"
                + " and result = 'SUCCESS' and resource_id = ?", id.toString()));
    }

    @Test
    void rolesShowThePermissionMatrix() throws Exception {
        Browser root = signIn(account("matrix.admin", "PLATFORM_ADMIN", "ALL"));
        JsonNode matrix = root.send(HttpMethod.GET, "/api/v1/roles", null).andExpect(status().isOk()).json();
        List<String> roles = new ArrayList<>();
        matrix.path("roles").forEach(role -> roles.add(role.path("roleCode").asText()));
        assertTrue(roles.containsAll(List.of("PLATFORM_ADMIN", "STATE_ADMIN", "AUDITOR")), roles.toString());
        assertTrue(matrix.path("permissions").size() > 10);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private String account(String name, String role, String scope) {
        String username = name + "." + suffix();
        users.create(new NewUser(username, name, encoder.encode(STRONG), false, "LOCAL", null, List.of(role),
                List.of(scope)));
        return username;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    private Browser signIn(String username) throws Exception {
        Browser browser = new Browser();
        browser.username = username;
        browser.send(HttpMethod.POST, "/api/v1/auth/login", Map.of("username", username, "password", STRONG))
                .andExpect(status().isOk());
        return browser;
    }

    /** The result of a request, readable as JSON. */
    record Response(ResultActions actions, ObjectMapper mapper) {
        Response andExpect(org.springframework.test.web.servlet.ResultMatcher matcher) throws Exception {
            actions.andExpect(matcher);
            return this;
        }

        JsonNode json() throws Exception {
            return mapper.readTree(actions.andReturn().getResponse().getContentAsString());
        }
    }

    /** A browser: keeps the session and the XSRF-TOKEN cookie and echoes the token on writes. */
    final class Browser {
        MockHttpSession session = new MockHttpSession();
        Cookie csrf;
        String username;

        Response send(HttpMethod method, String path, Object body) throws Exception {
            if (method != HttpMethod.GET && csrf == null) {
                send(HttpMethod.GET, "/api/v1/auth/csrf", null);
            }
            MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path);
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
            }
            if (csrf != null) {
                request.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
            }
            ResultActions actions = mvc.perform(request.session(session));
            MvcResult result = actions.andReturn();
            Cookie token = result.getResponse().getCookie("XSRF-TOKEN");
            if (token != null && token.getMaxAge() != 0) {
                csrf = token;
            }
            if (result.getRequest().getSession(false) instanceof MockHttpSession current) {
                session = current;
            }
            return new Response(actions, json);
        }
    }
}
