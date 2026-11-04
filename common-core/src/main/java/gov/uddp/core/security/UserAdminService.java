package gov.uddp.core.security;

import gov.uddp.core.department.DepartmentRepository;
import gov.uddp.core.read.ReadNotFoundException;
import gov.uddp.core.security.UserAccountStore.NewUser;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * User and role administration (ADR-0017): create, change roles and department scopes, disable and enable, unlock and
 * reset passwords. A new or reset password is random, shown once to the administrator and must be changed at the
 * next sign-in. The last enabled platform administrator can be neither disabled nor demoted. Every change is
 * audited by its endpoint ({@link Audited}), with the details this service adds to the {@link AuditContext}.
 */
@Service
public class UserAdminService {
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._@-]{1,63}");

    private final UserAccountStore users;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final DepartmentRepository departments;

    public UserAdminService(UserAccountStore users, PasswordEncoder encoder, PasswordPolicy policy,
                            DepartmentRepository departments) {
        this.users = users;
        this.departments = departments;
        this.encoder = encoder;
        this.policy = policy;
    }

    /** A user as administrators see it; never the password hash. */
    public record UserView(UUID userId, String username, String displayName, boolean enabled, boolean locked,
                           OffsetDateTime lockedUntil, int failedAttempts, boolean mustChangePassword,
                           String authSource, List<String> roles, List<String> departmentScope,
                           OffsetDateTime lastLoginAt, OffsetDateTime createdAt) {
    }

    /** The result of creating a user or resetting a password: the one-time password is shown once. */
    public record UserWithPassword(UserView user, String oneTimePassword) {
    }

    public List<UserView> list() {
        return users.list().stream().map(UserAdminService::view).toList();
    }

    /**
     * Users whose username or display name contains {@code query} (any case), optionally only enabled or disabled
     * ones ({@code status} ENABLED or DISABLED), ordered by username.
     */
    public List<UserView> search(String query, String status) {
        String text = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        String wanted = status == null ? "" : status.strip().toUpperCase(java.util.Locale.ROOT);
        if (!wanted.isEmpty() && !wanted.equals("ENABLED") && !wanted.equals("DISABLED")) {
            throw new IllegalArgumentException("status is ENABLED or DISABLED");
        }
        return list().stream()
                .filter(user -> text.isEmpty()
                        || user.username().contains(text)
                        || user.displayName().toLowerCase(java.util.Locale.ROOT).contains(text))
                .filter(user -> wanted.isEmpty() || user.enabled() == wanted.equals("ENABLED"))
                .sorted(java.util.Comparator.comparing(UserView::username))
                .toList();
    }

    /** The user's roles become exactly {@code roles}; nobody removes PLATFORM_ADMIN from themselves. */
    public UserView setRoles(UUID userId, List<String> roles) {
        UserAccount account = account(userId);
        Set<String> roleCodes = validRoles(roles);
        if (account.roles().contains(PLATFORM_ADMIN) && !roleCodes.contains(PLATFORM_ADMIN)) {
            if (isCurrentUser(account)) {
                throw new UserConflictException("You cannot remove PLATFORM_ADMIN from yourself");
            }
            requireAnotherAdmin(account);
        }
        users.updateProfile(userId, account.displayName(), roleCodes, account.departmentScope().values());
        audited(userId, "username", account.username(), "rolesBefore", List.copyOf(new TreeSet<>(account.roles())),
                "rolesAfter", List.copyOf(roleCodes));
        return get(userId);
    }

    /** The user's department scope becomes exactly {@code scope}: existing department codes, or ALL. */
    public UserView setDepartmentScope(UUID userId, List<String> scope) {
        UserAccount account = account(userId);
        List<String> departmentScope = validScope(scope);
        users.updateProfile(userId, account.displayName(), account.roles(), departmentScope);
        audited(userId, "username", account.username(), "departmentScopeBefore", account.departmentScope().values(),
                "departmentScopeAfter", departmentScope);
        return get(userId);
    }

    public UserView get(UUID userId) {
        return view(account(userId));
    }

    public UserWithPassword create(String username, String displayName, List<String> roles, List<String> scope) {
        String name = username == null ? "" : username.strip().toLowerCase(java.util.Locale.ROOT);
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalArgumentException("A username has 2 to 64 characters: lower-case letters, digits and . _ @ -");
        }
        if (users.findByUsername(name).isPresent()) {
            throw new UserConflictException("A user named " + name + " already exists");
        }
        Set<String> roleCodes = validRoles(roles);
        List<String> departmentScope = validScope(scope);
        String password = policy.generate();
        UUID userId = users.create(new NewUser(name, displayName(displayName, name), encoder.encode(password), true,
                LocalAuthenticationService.LOCAL, null, roleCodes, departmentScope));
        UserView created = get(userId);
        audited(userId, "username", name, "roles", List.copyOf(roleCodes), "departmentScope", departmentScope);
        return new UserWithPassword(created, password);
    }

    public UserView update(UUID userId, String displayName, List<String> roles, List<String> scope) {
        UserAccount account = account(userId);
        Set<String> roleCodes = validRoles(roles);
        List<String> departmentScope = validScope(scope);
        if (account.roles().contains(PLATFORM_ADMIN) && !roleCodes.contains(PLATFORM_ADMIN)) {
            if (isCurrentUser(account)) {
                throw new UserConflictException("You cannot remove PLATFORM_ADMIN from yourself");
            }
            requireAnotherAdmin(account);
        }
        users.updateProfile(userId, displayName(displayName, account.username()), roleCodes, departmentScope);
        audited(userId, "username", account.username(), "rolesBefore", List.copyOf(account.roles()),
                "rolesAfter", List.copyOf(roleCodes), "departmentScopeBefore", account.departmentScope().values(),
                "departmentScopeAfter", departmentScope);
        return get(userId);
    }

    public UserView setEnabled(UUID userId, boolean enabled) {
        UserAccount account = account(userId);
        if (!enabled) {
            if (isCurrentUser(account)) {
                throw new UserConflictException("You cannot disable your own account");
            }
            if (account.roles().contains(PLATFORM_ADMIN)) {
                requireAnotherAdmin(account);
            }
        }
        users.setEnabled(userId, enabled);
        audited(userId, "username", account.username(), "enabled", enabled);
        return get(userId);
    }

    public UserView unlock(UUID userId) {
        UserAccount account = account(userId);
        users.unlock(userId);
        audited(userId, "username", account.username());
        return get(userId);
    }

    public UserWithPassword resetPassword(UUID userId) {
        UserAccount account = account(userId);
        if (!LocalAuthenticationService.LOCAL.equals(account.authSource())) {
            throw new UserConflictException("Single sign-on users have no local password");
        }
        String password = policy.generate();
        users.updatePassword(userId, encoder.encode(password), true);
        audited(userId, "username", account.username());
        return new UserWithPassword(get(userId), password);
    }

    /**
     * The first user of a new platform (setup): a platform administrator for every department, with the password the
     * person chose on the Setup screen. Only possible while no user exists.
     */
    public UserView bootstrapAdministrator(String username, String displayName, String password) {
        if (users.count() > 0) {
            throw new UserConflictException("The platform already has users; sign in instead");
        }
        String name = username == null ? "" : username.strip().toLowerCase(java.util.Locale.ROOT);
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalArgumentException("A username has 2 to 64 characters: lower-case letters, digits and . _ @ -");
        }
        policy.require(name, password);
        UUID userId = users.create(new NewUser(name, displayName(displayName, name), encoder.encode(password), false,
                LocalAuthenticationService.LOCAL, null, Set.of(PLATFORM_ADMIN), List.of(DepartmentScope.ALL)));
        audited(userId, "username", name, "roles", List.of(PLATFORM_ADMIN), "departmentScope", List.of(DepartmentScope.ALL));
        return get(userId);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** The request's audit event (written by the endpoint's {@link Audited}) gets the user and these details. */
    private static void audited(UUID userId, Object... details) {
        AuditContext.resource(userId);
        AuditService.details(details).forEach(AuditContext::detail);
    }

    private UserAccount account(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ReadNotFoundException("User does not exist: " + userId));
    }

    private static boolean isCurrentUser(UserAccount account) {
        return account.username().equalsIgnoreCase(CurrentUser.username());
    }

    private void requireAnotherAdmin(UserAccount account) {
        long admins = users.countEnabledWithRole(PLATFORM_ADMIN);
        if (account.enabled() && admins <= 1) {
            throw new UserConflictException("The platform must keep at least one enabled platform administrator");
        }
    }

    private Set<String> validRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one role");
        }
        Set<String> known = users.roles().stream().map(UserAccountStore.RoleDefinition::roleCode)
                .collect(Collectors.toSet());
        Set<String> chosen = new TreeSet<>();
        List<String> unknown = new ArrayList<>();
        for (String role : roles) {
            String code = role == null ? "" : role.strip().toUpperCase(java.util.Locale.ROOT);
            if (known.contains(code)) {
                chosen.add(code);
            } else {
                unknown.add(role);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown role(s): " + unknown);
        }
        return chosen;
    }

    private List<String> validScope(List<String> scope) {
        if (scope == null || scope.isEmpty()) {
            throw new IllegalArgumentException("Choose the departments the user may see, or ALL");
        }
        DepartmentScope parsed = DepartmentScope.of(scope);
        if (!parsed.all() && parsed.departments().isEmpty()) {
            throw new IllegalArgumentException("Choose the departments the user may see, or ALL");
        }
        Set<String> known = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        departments.departments().forEach(department -> known.add(department.departmentCode()));
        for (String code : parsed.departments()) {
            if (!known.contains(code)) {
                throw new IllegalArgumentException("Not a department: " + code);
            }
        }
        return parsed.values();
    }

    private static String displayName(String displayName, String username) {
        return displayName == null || displayName.isBlank() ? username : displayName.strip();
    }

    static UserView view(UserAccount account) {
        OffsetDateTime now = OffsetDateTime.now();
        return new UserView(account.userId(), account.username(), account.displayName(), account.enabled(),
                account.lockedAt(now), account.lockedAt(now) ? account.lockedUntil() : null, account.failedAttempts(),
                account.mustChangePassword(), account.authSource(), List.copyOf(account.roles()),
                account.departmentScope().values(), account.lastLoginAt(), account.createdAt());
    }
}
