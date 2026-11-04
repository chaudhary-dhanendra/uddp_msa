package gov.uddp.core.security;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.security.UserAccountStore.NewUser;
import gov.uddp.core.setup.PlatformDatabaseReadyEvent;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Demo users, one per role (two stewards, for maker-checker), each with its own random one-time password that must
 * be changed at first sign-in (ADR-0017). Disabled unless {@code uddp.security.demo.seed-users=true} (demo mode).
 * Runs at startup and whenever the Setup screen makes a database ready; existing users are left alone.
 *
 * <p>The passwords are shown once: written to {@code uddp.security.demo.credentials-file} (owner-only) for
 * {@code scripts/run-demo.ps1} to print and delete, or else printed to the console. They never go to the log.
 */
@Component
public class DemoSecuritySeeder {
    private static final Logger LOG = LoggerFactory.getLogger(DemoSecuritySeeder.class);

    /** A demo account: username, role and department scope. */
    record DemoUser(String username, String role, String scope) {
    }

    static final List<DemoUser> USERS = List.of(
            new DemoUser("platform.admin", "PLATFORM_ADMIN", DepartmentScope.ALL),
            new DemoUser("state.admin", "STATE_ADMIN", DepartmentScope.ALL),
            new DemoUser("data.engineer", "DATA_ENGINEER", DepartmentScope.ALL),
            new DemoUser("data.steward", "DATA_STEWARD", DepartmentScope.ALL),
            new DemoUser("data.steward2", "DATA_STEWARD", DepartmentScope.ALL),
            new DemoUser("program.user", "PROGRAM_USER", DepartmentScope.ALL),
            new DemoUser("auditor", "AUDITOR", DepartmentScope.ALL));

    private final PostgresConnectionFactory connections;
    private final UserAccountStore users;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final SecurityProperties properties;
    private final AuditService audit;
    private final PrintStream console;

    public DemoSecuritySeeder(PostgresConnectionFactory connections, UserAccountStore users, PasswordEncoder encoder,
                              PasswordPolicy policy, SecurityProperties properties, AuditService audit) {
        this.connections = connections;
        this.users = users;
        this.encoder = encoder;
        this.policy = policy;
        this.properties = properties;
        this.audit = audit;
        this.console = System.out;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStartup() {
        seed();
    }

    @EventListener(PlatformDatabaseReadyEvent.class)
    public void whenDatabaseReady() {
        seed();
    }

    /** Creates the missing demo users; returns how many were created. */
    public synchronized int seed() {
        if (!properties.getDemo().isSeedUsers() || !connections.isConfigured()) {
            return 0;
        }
        List<String> lines = new ArrayList<>();
        for (DemoUser demo : USERS) {
            if (users.findByUsername(demo.username()).isPresent()) {
                continue;
            }
            String password = policy.generate();
            var userId = users.create(new NewUser(demo.username(), demo.role().replace('_', ' ').toLowerCase()
                    + (demo.scope().equals(DepartmentScope.ALL) ? "" : " (" + demo.scope() + ")"), encoder.encode(password),
                    true, LocalAuthenticationService.LOCAL, null, List.of(demo.role()), List.of(demo.scope())));
            audit.record(AuditService.AuditEvent.of("DEMO_USER_CREATE", "USER", userId.toString(), null,
                    AuditService.SUCCESS).with(AuditService.details("username", demo.username(), "role", demo.role(),
                    "departmentScope", demo.scope())));
            lines.add(String.format("%-18s %-22s %-15s %s", demo.username(), password, demo.role(), demo.scope()));
        }
        if (!lines.isEmpty()) {
            show(lines);
            LOG.info("Created {} demo user(s); their one-time passwords were shown once", lines.size());
        }
        return lines.size();
    }

    private void show(List<String> lines) {
        List<String> output = new ArrayList<>();
        output.add("UDDP demo users (one-time passwords; each must be changed at first sign-in):");
        output.add(String.format("%-18s %-22s %-15s %s", "USERNAME", "PASSWORD", "ROLE", "DEPARTMENTS"));
        output.addAll(lines);
        String file = properties.getDemo().getCredentialsFile();
        if (file == null || file.isBlank()) {
            output.forEach(console::println);
            return;
        }
        Path path = Path.of(file);
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            if (!Files.exists(path)) {
                try {
                    Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } catch (UnsupportedOperationException windows) {
                    Files.createFile(path);
                }
            }
            Files.write(path, output, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the demo credentials file", e);
        }
    }
}
