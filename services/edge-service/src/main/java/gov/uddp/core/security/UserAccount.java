package gov.uddp.core.security;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/** One {@code app_user} row with its roles, the permissions they grant and its assigned departments. */
public record UserAccount(UUID userId, String username, String displayName, String passwordHash, boolean enabled,
                          int failedAttempts, OffsetDateTime lockedUntil, boolean mustChangePassword,
                          String authSource, String externalSubject, OffsetDateTime lastLoginAt,
                          OffsetDateTime createdAt, Set<String> roles, Set<Permission> permissions,
                          DepartmentScope departmentScope) {
    public boolean lockedAt(OffsetDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** The principal, with its record-level scope resolved from the departments. */
    public UddpPrincipal principal(DepartmentScopeResolver resolver) {
        return new UddpPrincipal(userId, username, displayName, roles, permissions, departmentScope,
                resolver.sources(departmentScope), mustChangePassword, authSource);
    }

    @Override
    public String toString() {
        return "UserAccount[" + username + "]";
    }
}
