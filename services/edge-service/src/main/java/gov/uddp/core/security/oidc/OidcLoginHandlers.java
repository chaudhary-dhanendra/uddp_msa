package gov.uddp.core.security.oidc;

import gov.uddp.core.security.AuditService;
import gov.uddp.core.security.DepartmentScope;
import gov.uddp.core.security.DepartmentScopeResolver;
import gov.uddp.core.security.PasswordPolicy;
import gov.uddp.core.security.SecurityProperties;
import gov.uddp.core.security.SessionSignIn;
import gov.uddp.core.security.UserAccount;
import gov.uddp.core.security.UserAccountStore;
import gov.uddp.core.security.UserAccountStore.NewUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Turns a successful OIDC sign-in into a platform session (ADR-0017). The user is matched by issuer subject and
 * created on first sign-in; roles and department scopes come only from the identity provider's groups, mapped by
 * {@code uddp.security.oidc.group-roles} and {@code group-departments}, and are refreshed at every sign-in. A user with
 * no mapped role, a disabled user, or a username already taken by a local account is refused.
 */
@Component
@ConditionalOnProperty(prefix = "uddp.security.oidc", name = "enabled", havingValue = "true")
public class OidcLoginHandlers {
    static final String OIDC = "OIDC";

    private final UserAccountStore users;
    private final SessionSignIn sessions;
    private final SecurityProperties properties;
    private final AuditService audit;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final DepartmentScopeResolver departments;

    public OidcLoginHandlers(UserAccountStore users, SessionSignIn sessions, SecurityProperties properties,
                             AuditService audit, PasswordEncoder encoder, PasswordPolicy policy,
                             DepartmentScopeResolver departments) {
        this.users = users;
        this.departments = departments;
        this.sessions = sessions;
        this.properties = properties;
        this.audit = audit;
        this.encoder = encoder;
        this.policy = policy;
    }

    /** Roles and departments a set of identity-provider groups maps to. */
    record Mapping(Set<String> roles, Set<String> departments) {
    }

    public void success(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        if (!(authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            deny(request, response, "unknown", "NOT_AN_OIDC_USER");
            return;
        }
        SecurityProperties.Oidc oidc = properties.getOidc();
        String subject = oidcUser.getSubject();
        String username = username(oidcUser, oidc.getUsernameClaim());
        Mapping mapping = map(groups(oidcUser.getClaims().get(oidc.getGroupsClaim())), oidc);
        if (mapping.roles().isEmpty()) {
            deny(request, response, username, "NO_MAPPED_ROLE");
            return;
        }
        List<String> scope = mapping.departments().isEmpty() ? List.of() : List.copyOf(mapping.departments());
        if (scope.isEmpty()) {
            deny(request, response, username, "NO_MAPPED_DEPARTMENT");
            return;
        }
        String displayName = oidcUser.getFullName() == null ? username : oidcUser.getFullName();
        Optional<UserAccount> existing = users.findExternal(OIDC, subject);
        UserAccount account;
        if (existing.isEmpty()) {
            if (users.findByUsername(username).isPresent()) {
                deny(request, response, username, "USERNAME_TAKEN_BY_ANOTHER_ACCOUNT");
                return;
            }
            // An unusable random hash: OIDC users never sign in with a local password.
            var userId = users.create(new NewUser(username, displayName, encoder.encode(policy.generate()), false,
                    OIDC, subject, mapping.roles(), scope));
            account = users.findById(userId).orElseThrow();
            audit.record(AuditService.AuditEvent.of("USER_CREATE", "USER", userId.toString(), null,
                    AuditService.SUCCESS).by(username).with(AuditService.details("authSource", OIDC,
                    "roles", List.copyOf(mapping.roles()), "departmentScope", scope)));
        } else {
            account = existing.get();
            if (!account.enabled()) {
                deny(request, response, username, "DISABLED");
                return;
            }
            users.syncExternal(account.userId(), displayName, mapping.roles(), scope);
            account = users.findById(account.userId()).orElseThrow();
        }
        users.recordSuccess(account.userId(), java.time.OffsetDateTime.now());
        sessions.signIn(request, response, account.principal(departments));
        audit.record(AuditService.AuditEvent.of("LOGIN", "SESSION", account.userId().toString(), null,
                AuditService.SUCCESS).by(account.username()).with(AuditService.details("method", OIDC,
                "roles", List.copyOf(account.roles()), "departmentScope", account.departmentScope().values())));
        response.sendRedirect(oidc.getSuccessUrl());
    }

    public void failure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        audit.record(AuditService.AuditEvent.of("LOGIN", "SESSION", null, null, AuditService.FAILURE)
                .by("(single sign-on)").with(AuditService.details("method", OIDC, "reason", "PROVIDER_ERROR")));
        response.sendRedirect(withParameter(properties.getOidc().getSuccessUrl(), "sso=failed"));
    }

    private void deny(HttpServletRequest request, HttpServletResponse response, String username, String reason)
            throws IOException {
        sessions.signOut(request);
        SecurityContextHolder.clearContext();
        audit.record(AuditService.AuditEvent.of("LOGIN", "SESSION", null, null, AuditService.FAILURE)
                .by(username).with(AuditService.details("method", OIDC, "reason", reason)));
        response.sendRedirect(withParameter(properties.getOidc().getSuccessUrl(), "sso=denied"));
    }

    static Mapping map(Collection<String> groups, SecurityProperties.Oidc oidc) {
        Set<String> roles = new TreeSet<>();
        Set<String> departmentCodes = new TreeSet<>();
        for (String group : groups) {
            addAll(roles, oidc.getGroupRoles().get(group));
            addAll(departmentCodes, oidc.getGroupDepartments().get(group));
        }
        return new Mapping(roles, new TreeSet<>(DepartmentScope.of(departmentCodes).values()));
    }

    /** Group names without a leading path separator (Keycloak's full-path group names start with "/"). */
    static List<String> groups(Object claim) {
        List<String> groups = new ArrayList<>();
        if (claim instanceof Collection<?> values) {
            for (Object value : values) {
                String name = String.valueOf(value);
                groups.add(name.startsWith("/") ? name.substring(1) : name);
            }
        } else if (claim instanceof String value) {
            groups.add(value.startsWith("/") ? value.substring(1) : value);
        }
        return groups;
    }

    private static String username(OidcUser user, String claim) {
        Map<String, Object> claims = user.getClaims();
        Object value = claims.get(claim);
        if (value == null) {
            value = user.getEmail() != null ? user.getEmail() : user.getSubject();
        }
        return value.toString().strip().toLowerCase(Locale.ROOT);
    }

    private static void addAll(Set<String> target, String commaSeparated) {
        if (commaSeparated == null) {
            return;
        }
        for (String value : commaSeparated.split(",")) {
            if (!value.isBlank()) {
                target.add(value.strip().toUpperCase(Locale.ROOT));
            }
        }
    }

    private static String withParameter(String url, String parameter) {
        return url + (url.contains("?") ? "&" : "?") + parameter;
    }
}
