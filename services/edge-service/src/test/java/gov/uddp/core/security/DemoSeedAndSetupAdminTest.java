package gov.uddp.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The first administrator from the Setup screen, and the demo seed: off by default, one random one-time password
 * per user written only to the credentials file (never the shared "demo" password, never the log).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoSeedAndSetupAdminTest {
    @TestConfiguration
    static class Database {
        @Bean
        @Primary
        PostgresConnectionFactory seedConnections() {
            return TestDatabase.freshMigrated();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgresConnectionFactory connections;

    @Autowired
    UserAccountStore users;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    PasswordPolicy policy;

    @Autowired
    AuditService audit;

    @Autowired
    SecurityProperties properties;

    final ObjectMapper json = new ObjectMapper();

    @Test
    @Order(1)
    void theFirstUserFromTheSetupScreenBecomesAPlatformAdministratorForEveryStateOnlyOnce() throws Exception {
        assertEquals(0, users.count(), "the demo seed is off by default");
        mvc.perform(get("/api/setup/status").with(anonymous()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("READY"))
                .andExpect(jsonPath("$.adminRequired").value(true))
                .andExpect(jsonPath("$.settings").doesNotExist())
                .andExpect(jsonPath("$.settingsFile").doesNotExist());

        mvc.perform(post("/api/setup/admin").with(anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "first.admin", "password", "weak"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/setup/admin").with(anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "first.admin", "displayName", "First Admin",
                                "password", "Setup-Screen-Secret-9"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("first.admin"))
                .andExpect(jsonPath("$.roles[0]").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.departmentScope[0]").value("ALL"))
                .andExpect(jsonPath("$.mustChangePassword").value(false));
        UserAccount admin = users.findByUsername("first.admin").orElseThrow();
        assertEquals(Set.of("PLATFORM_ADMIN"), admin.roles());
        assertTrue(admin.departmentScope().all());
        assertTrue(admin.permissions().containsAll(Set.of(Permission.values())));

        mvc.perform(post("/api/setup/admin").with(anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "second.admin",
                                "password", "Setup-Screen-Secret-9"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/setup/status").with(anonymous()))
                .andExpect(jsonPath("$.adminRequired").value(false));
        assertEquals(1, new Sql(connections).count("select count(*) from app_audit_event"
                + " where action = 'SETUP_ADMIN_CREATE' and result = 'SUCCESS' and actor_username = 'first.admin'"));

        // Setup endpoints are closed to anyone without PLATFORM_SETTINGS once the platform is configured.
        mvc.perform(post("/api/setup/test-connection").with(anonymous()).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    void theDemoSeedCreatesOneUserPerRoleWithItsOwnOneTimePasswordShownOnlyInTheCredentialsFile() throws Exception {
        Path file = Files.createTempDirectory("uddp-demo-credentials").resolve("credentials.txt");
        var demo = properties.getDemo();
        PrintStream console = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            demo.setSeedUsers(true);
            demo.setCredentialsFile(file.toString());
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            var seeder = new DemoSecuritySeeder(connections, users, encoder, policy, properties, audit);
            assertEquals(DemoSecuritySeeder.USERS.size(), seeder.seed());
            assertEquals(0, seeder.seed(), "existing users are left alone");
        } finally {
            System.setOut(console);
            demo.setSeedUsers(false);
            demo.setCredentialsFile(null);
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Set<String> passwords = new HashSet<>();
        for (var expected : DemoSecuritySeeder.USERS) {
            String line = lines.stream().filter(l -> l.startsWith(expected.username() + " ")).findFirst().orElseThrow();
            String password = line.trim().split("\\s+")[1];
            assertTrue(passwords.add(password), "one password per user");
            assertEquals(List.of(), policy.violations(expected.username(), password));
            UserAccount account = users.findByUsername(expected.username()).orElseThrow();
            assertTrue(account.mustChangePassword(), "must be changed at first sign-in");
            assertTrue(encoder.matches(password, account.passwordHash()));
            assertFalse(encoder.matches("demo", account.passwordHash()), "no shared default password");
            assertTrue(account.roles().contains(expected.role()));
            assertFalse(captured.toString(StandardCharsets.UTF_8).contains(password), "not printed when a file is set");
            assertEquals(0, new Sql(connections).count("select count(*) from app_audit_event where metadata::text"
                    + " like ?", "%" + password + "%"));
        }
        assertTrue(users.findByUsername("platform.admin").orElseThrow().departmentScope().all());
    }
}
