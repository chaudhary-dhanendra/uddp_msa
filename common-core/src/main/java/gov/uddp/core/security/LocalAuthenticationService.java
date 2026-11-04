package gov.uddp.core.security;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Username and password sign-in with bcrypt, account lockout after repeated failures and forced password change
 * (ADR-0017). Every outcome is audited; the reply to a failed sign-in never says whether the username exists.
 */
@Service
public class LocalAuthenticationService {
    public static final String INVALID = "Invalid username or password, or the account is locked or disabled";
    public static final String LOCAL = "LOCAL";

    private final UserAccountStore users;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final SecurityProperties properties;
    private final AuditService audit;
    private final DepartmentScopeResolver departments;
    private final Clock clock;
    /** Compared against when the username is unknown, so both paths cost one bcrypt verification. */
    private final String unknownUserHash;

    @Autowired
    public LocalAuthenticationService(UserAccountStore users, PasswordEncoder encoder, PasswordPolicy policy,
                                      SecurityProperties properties, AuditService audit,
                                      DepartmentScopeResolver departments) {
        this(users, encoder, policy, properties, audit, departments, Clock.systemUTC());
    }

    LocalAuthenticationService(UserAccountStore users, PasswordEncoder encoder, PasswordPolicy policy,
                               SecurityProperties properties, AuditService audit, DepartmentScopeResolver departments,
                               Clock clock) {
        this.users = users;
        this.departments = departments;
        this.encoder = encoder;
        this.policy = policy;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
        this.unknownUserHash = encoder.encode(policy.generate());
    }

    /** The principal for a correct username and password; otherwise {@link BadCredentialsException}. */
    public UddpPrincipal authenticate(String username, String password) {
        String name = username == null ? "" : username.strip();
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<UserAccount> found = users.findByUsername(name);
        if (found.isEmpty() || password == null) {
            encoder.matches(password == null ? "" : password, unknownUserHash);
            fail(name, "UNKNOWN_USER");
        }
        UserAccount account = found.get();
        if (!LOCAL.equals(account.authSource())) {
            fail(account.username(), "NOT_A_LOCAL_ACCOUNT");
        }
        if (!account.enabled()) {
            encoder.matches(password, account.passwordHash());
            fail(account.username(), "DISABLED");
        }
        if (account.lockedAt(now)) {
            encoder.matches(password, account.passwordHash());
            fail(account.username(), "LOCKED");
        }
        if (!encoder.matches(password, account.passwordHash())) {
            boolean locked = users.recordFailure(account.userId(), properties.getLockout().getMaxFailedAttempts(),
                    properties.getLockout().getDuration(), now);
            if (locked) {
                audit.record(AuditService.AuditEvent.of("ACCOUNT_LOCKED", "USER", account.userId().toString(), null,
                        AuditService.SUCCESS).by(account.username()).with(AuditService.details(
                        "failedAttempts", properties.getLockout().getMaxFailedAttempts(),
                        "lockedForMinutes", properties.getLockout().getDuration().toMinutes())));
            }
            fail(account.username(), "WRONG_PASSWORD");
        }
        users.recordSuccess(account.userId(), now);
        if (encoder.upgradeEncoding(account.passwordHash())) {
            users.updatePassword(account.userId(), encoder.encode(password), account.mustChangePassword());
        }
        audit.record(AuditService.AuditEvent.of("LOGIN", "SESSION", account.userId().toString(), null,
                AuditService.SUCCESS).by(account.username()).with(AuditService.details("method", LOCAL,
                "mustChangePassword", account.mustChangePassword())));
        return users.findById(account.userId()).orElseThrow().principal(departments);
    }

    /** Changes the signed-in user's own password; the new one must meet the policy and differ from the current. */
    public UddpPrincipal changePassword(UddpPrincipal principal, String currentPassword, String newPassword) {
        if (principal.userId() == null) {
            throw new IllegalArgumentException("This session has no stored account");
        }
        UserAccount account = users.findById(principal.userId()).orElseThrow(() -> new BadCredentialsException(INVALID));
        if (!LOCAL.equals(account.authSource())) {
            throw new IllegalArgumentException("Single sign-on accounts change their password at the identity provider");
        }
        if (currentPassword == null || !encoder.matches(currentPassword, account.passwordHash())) {
            refuse(account, "WRONG_CURRENT_PASSWORD", "The current password is not correct");
        }
        if (!policy.violations(account.username(), newPassword).isEmpty()) {
            refuse(account, "POLICY", "The password does not meet the policy: "
                    + String.join("; ", policy.violations(account.username(), newPassword)));
        }
        if (encoder.matches(newPassword, account.passwordHash())) {
            refuse(account, "SAME_AS_CURRENT", "The new password must differ from the current one");
        }
        users.updatePassword(account.userId(), encoder.encode(newPassword), false);
        audit.record(AuditService.AuditEvent.of("PASSWORD_CHANGE", "USER", account.userId().toString(), null,
                AuditService.SUCCESS));
        return users.findById(account.userId()).orElseThrow().principal(departments);
    }

    private void refuse(UserAccount account, String reason, String message) {
        audit.record(AuditService.AuditEvent.of("PASSWORD_CHANGE", "USER", account.userId().toString(), null,
                AuditService.FAILURE).with(AuditService.details("reason", reason)));
        throw new IllegalArgumentException(message);
    }

    private void fail(String username, String reason) {
        audit.record(AuditService.AuditEvent.of("LOGIN", "SESSION", null, null, AuditService.FAILURE)
                .by(auditableName(username))
                .with(AuditService.details("method", LOCAL, "reason", reason)));
        throw new BadCredentialsException(INVALID);
    }

    /**
     * The attempted username as recorded: only when it looks like a username, so a password typed into the
     * username field is never stored.
     */
    static String auditableName(String username) {
        if (username == null || username.isEmpty()) {
            return "(blank)";
        }
        return username.matches("[A-Za-z0-9._@-]{1,64}") ? username : "(not a valid username)";
    }
}
