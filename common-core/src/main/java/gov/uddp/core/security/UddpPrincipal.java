package gov.uddp.core.security;

import java.io.Serializable;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The signed-in user as the platform sees it: roles, the permissions they grant, the assigned departments and whether the
 * password must be changed before anything else is allowed. Built from the user's database row at login and
 * refreshed on every request ({@link PrincipalRefreshFilter}), so role, scope and enabled changes apply at once.
 *
 * @param userId     the {@code app_user} row; null only for principals that tests create directly
 * @param departmentScope the assigned departments (R1b)
 * @param scope      the record-level scope: the source codes of the departments' Source Packs, resolved on every
 *                   request by {@link PrincipalRefreshFilter}
 * @param authSource LOCAL (username and password) or OIDC (single sign-on)
 */
public record UddpPrincipal(UUID userId, String username, String displayName, Set<String> roles,
                            Set<Permission> permissions, DepartmentScope departmentScope,
                            StateScope scope, boolean mustChangePassword,
                            String authSource) implements Serializable {
    public UddpPrincipal {
        roles = Set.copyOf(new TreeSet<>(roles));
        permissions = permissions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(permissions));
    }

    /** This principal with another record-level scope. */
    public UddpPrincipal withScope(StateScope resolved) {
        return resolved.equals(scope) ? this : new UddpPrincipal(userId, username, displayName, roles, permissions,
                departmentScope, resolved, mustChangePassword, authSource);
    }

    /** Permissions in force: none until a required password change is done. */
    public Set<Permission> effectivePermissions() {
        return mustChangePassword ? Set.of() : permissions;
    }

    public boolean has(Permission permission) {
        return effectivePermissions().contains(permission);
    }

    @Override
    public String toString() {
        return "UddpPrincipal[" + username + "]";
    }
}
