package gov.uddp.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.security.UserAccountStore.NewUser;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * P1 Part 1 through the real filter chain (no test principal): sessions, CSRF, lockout, forced password change,
 * sign-out, disabled accounts and the audit records of each.
 */
@SpringBootTest(properties = {"uddp.security.lockout.max-failed-attempts=3"})
class AuthenticationApiTest {
    static final String STRONG = "Correct-Horse-7-Battery";

    @TestConfiguration
    static class Database {
        @Bean
        @Primary
        PostgresConnectionFactory authenticationConnections() {
            return TestDatabase.freshMigrated();
        }
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    PostgresConnectionFactory connections;

    @Autowired
    UserAccountStore users;

    @Autowired
    PasswordEncoder encoder;

    final ObjectMapper json = new ObjectMapper();
    MockMvc mvc;

    @BeforeEach
    void plainMockMvc() {
        // No default principal: every request is what a browser would send.
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void apiRequestsWithoutASessionAre401WithAJsonBody() throws Exception {
        mvc.perform(get("/api/v1/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void writesWithoutTheCsrfTokenAreRefused() throws Exception {
        String user = newUser("csrf.user", false);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("username", user, "password", STRONG))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void signInStartsASessionMeShowsTheUserAndSignOutEndsIt() throws Exception {
        String user = newUser("session.user", false);
        Browser browser = new Browser();
        MvcResult login = browser.post("/api/v1/auth/login", Map.of("username", user, "password", STRONG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(user))
                .andExpect(jsonPath("$.roles[0]").value("DATA_STEWARD"))
                .andExpect(jsonPath("$.departmentScope[0]").value("DEPT-1"))
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andReturn();
        assertTrue(login.getResponse().getContentAsString().contains("STEWARDSHIP_DECIDE"));
        assertFalse(login.getResponse().getContentAsString().contains(STRONG), "no password in the reply");
        browser.get("/api/v1/auth/me").andExpect(status().isOk()).andExpect(jsonPath("$.username").value(user));

        browser.post("/api/v1/auth/logout", Map.of()).andExpect(status().isNoContent());
        browser.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
        Sql sql = new Sql(connections);
        assertEquals(1, sql.count("select count(*) from app_audit_event where action = 'LOGIN' and result = 'SUCCESS'"
                + " and actor_username = '" + user + "'"));
        assertEquals(1, sql.count("select count(*) from app_audit_event where action = 'LOGOUT'"
                + " and actor_username = '" + user + "'"));
    }

    @Test
    void signInReplacesTheSessionId() throws Exception {
        String user = newUser("fixation.user", false);
        Browser browser = new Browser();
        browser.get("/api/v1/auth/csrf").andExpect(status().isOk());
        MockHttpSession before = new MockHttpSession();
        browser.session = before;
        browser.post("/api/v1/auth/login", Map.of("username", user, "password", STRONG)).andExpect(status().isOk());
        assertTrue(before.isInvalid(), "the pre-login session is invalidated");
        assertNotEquals(before, browser.session);
    }

    @Test
    void repeatedFailuresLockTheAccountAndTheReplyNeverRevealsWhy() throws Exception {
        String user = newUser("locked.user", false);
        Browser browser = new Browser();
        for (int i = 0; i < 3; i++) {
            browser.post("/api/v1/auth/login", Map.of("username", user, "password", "Wrong-Password-1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(LocalAuthenticationService.INVALID));
        }
        browser.post("/api/v1/auth/login", Map.of("username", user, "password", STRONG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(LocalAuthenticationService.INVALID));
        browser.post("/api/v1/auth/login", Map.of("username", "nobody.here", "password", STRONG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(LocalAuthenticationService.INVALID));

        Sql sql = new Sql(connections);
        assertTrue(users.findByUsername(user).orElseThrow().lockedUntil() != null);
        assertEquals(1, sql.count("select count(*) from app_audit_event where action = 'ACCOUNT_LOCKED'"
                + " and actor_username = '" + user + "'"));
        assertEquals(4, sql.count("select count(*) from app_audit_event where action = 'LOGIN' and result = 'FAILURE'"
                + " and actor_username = '" + user + "'"));
        assertEquals(1, sql.count("select count(*) from app_audit_event where action = 'LOGIN' and result = 'FAILURE'"
                + " and actor_username = 'nobody.here' and metadata->>'reason' = 'UNKNOWN_USER'"));
        assertEquals(0, sql.count("select count(*) from app_audit_event where metadata::text like '%" + STRONG + "%'"
                + " or actor_username like '%Wrong-Password%'"));
    }

    @Test
    void aForcedPasswordChangeBlocksEverythingElseUntilAStrongPasswordIsChosen() throws Exception {
        String user = newUser("forced.user", true);
        Browser browser = new Browser();
        browser.post("/api/v1/auth/login", Map.of("username", user, "password", STRONG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.permissions").isEmpty());
        browser.get("/api/v1/golden-residents")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("PASSWORD_CHANGE_REQUIRED"));

        browser.post("/api/v1/auth/change-password", Map.of("currentPassword", STRONG, "newPassword", "short"))
                .andExpect(status().isBadRequest());
        browser.post("/api/v1/auth/change-password", Map.of("currentPassword", STRONG,
                        "newPassword", "X-" + user + "-Is-Not-OK-1"))
                .andExpect(status().isBadRequest());
        browser.post("/api/v1/auth/change-password", Map.of("currentPassword", "Not-The-Current-1",
                        "newPassword", "Brand-New-Secret-42"))
                .andExpect(status().isBadRequest());
        browser.post("/api/v1/auth/change-password", Map.of("currentPassword", STRONG,
                        "newPassword", "Brand-New-Secret-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false));
        browser.get("/api/v1/golden-residents").andExpect(status().isOk());
        UserAccount account = users.findByUsername(user).orElseThrow();
        assertFalse(account.mustChangePassword());
        assertTrue(encoder.matches("Brand-New-Secret-42", account.passwordHash()));
        assertTrue(account.passwordHash().startsWith("$2"), "bcrypt");
        assertEquals(3, new Sql(connections).count("select count(*) from app_audit_event"
                + " where action = 'PASSWORD_CHANGE' and result = 'FAILURE' and actor_username = '" + user + "'"));
    }

    @Test
    void aDisabledUserLosesTheSessionOnTheNextRequestAndRoleChangesApplyAtOnce() throws Exception {
        String user = newUser("revoked.user", false);
        Browser browser = new Browser();
        browser.post("/api/v1/auth/login", Map.of("username", user, "password", STRONG)).andExpect(status().isOk());
        UUID id = users.findByUsername(user).orElseThrow().userId();

        users.updateProfile(id, "Revoked", List.of("PROGRAM_USER"), List.of("DEPT-2"));
        browser.get("/api/v1/auth/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("PROGRAM_USER"))
                .andExpect(jsonPath("$.departmentScope[0]").value("DEPT-2"));

        users.setEnabled(id, false);
        browser.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
        users.setEnabled(id, true);
        browser.get("/api/v1/auth/me").andExpect(status().isUnauthorized());
    }

    private String newUser(String name, boolean mustChange) {
        String username = name + "." + UUID.randomUUID().toString().substring(0, 6);
        users.create(new NewUser(username, name, encoder.encode(STRONG), mustChange, "LOCAL", null,
                List.of("DATA_STEWARD"), List.of("DEPT-1")));
        return username;
    }

    private String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    /** A browser: keeps the session and the XSRF-TOKEN cookie and echoes the token on writes. */
    final class Browser {
        MockHttpSession session = new MockHttpSession();
        Cookie csrf;

        org.springframework.test.web.servlet.ResultActions get(String path) throws Exception {
            return perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path));
        }

        org.springframework.test.web.servlet.ResultActions post(String path, Object body) throws Exception {
            if (csrf == null) {
                get("/api/v1/auth/csrf");
            }
            assertNotNull(csrf, "the CSRF cookie is set");
            return perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body))
                    .header("X-XSRF-TOKEN", csrf.getValue()));
        }

        private org.springframework.test.web.servlet.ResultActions perform(MockHttpServletRequestBuilder request)
                throws Exception {
            if (csrf != null) {
                request.cookie(csrf);
            }
            var actions = mvc.perform(request.session(session));
            MvcResult result = actions.andReturn();
            Cookie token = result.getResponse().getCookie("XSRF-TOKEN");
            if (token != null && token.getMaxAge() != 0) {
                csrf = token;
            }
            if (result.getRequest().getSession(false) instanceof MockHttpSession current) {
                session = current;
            }
            return actions;
        }
    }
}
