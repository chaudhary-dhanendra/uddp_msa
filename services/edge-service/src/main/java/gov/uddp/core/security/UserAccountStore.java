package gov.uddp.core.security;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Users, roles, permissions and department scopes in PostgreSQL ({@code app_user}, {@code app_user_role},
 * {@code app_role_permission}, {@code user_department_scope}). Password hashes never leave this class except inside a
 * {@link UserAccount} for verification.
 */
@Repository
public class UserAccountStore {
    /** A role with its permissions, for the role permissions view and validation. */
    public record RoleDefinition(String roleCode, String description, List<String> permissions) {
    }

    /** A permission with its description. */
    public record PermissionDefinition(String permissionCode, String description) {
    }

    /** What a new user starts with. */
    public record NewUser(String username, String displayName, String passwordHash, boolean mustChangePassword,
                          String authSource, String externalSubject, Collection<String> roles,
                          Collection<String> scope) {
    }

    private static final String SELECT_USER = "select u.user_id, u.username, u.display_name, u.password_hash,"
            + " u.enabled, u.failed_attempts, u.locked_until, u.must_change_password, u.auth_source,"
            + " u.external_subject, u.last_login_at, u.created_at from app_user u";

    private final PostgresConnectionFactory connections;

    public UserAccountStore(PostgresConnectionFactory connections) {
        this.connections = connections;
    }

    public Optional<UserAccount> findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return one(SELECT_USER + " where lower(u.username) = lower(?)", username.strip());
    }

    public Optional<UserAccount> findById(UUID userId) {
        return one(SELECT_USER + " where u.user_id = ?", userId);
    }

    public Optional<UserAccount> findExternal(String authSource, String externalSubject) {
        return one(SELECT_USER + " where u.auth_source = ? and u.external_subject = ?", authSource, externalSubject);
    }

    public List<UserAccount> list() {
        try (Connection c = connections.open();
                var s = c.prepareStatement(SELECT_USER + " order by lower(u.username)");
                var r = s.executeQuery()) {
            List<UserAccount> users = new ArrayList<>();
            while (r.next()) {
                users.add(account(c, r));
            }
            return users;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read users", e);
        }
    }

    public long count() {
        try (Connection c = connections.open();
                var s = c.prepareStatement("select count(*) from app_user");
                var r = s.executeQuery()) {
            r.next();
            return r.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot count users", e);
        }
    }

    /** Enabled users holding the role, to keep at least one platform administrator. */
    public long countEnabledWithRole(String roleCode) {
        try (Connection c = connections.open();
                var s = c.prepareStatement("select count(*) from app_user u join app_user_role r on r.user_id = u.user_id"
                        + " where u.enabled and r.role_code = ?")) {
            s.setString(1, roleCode);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot count users", e);
        }
    }

    public UUID create(NewUser user) {
        UUID userId = UUID.randomUUID();
        inTransaction(c -> {
            try (var s = c.prepareStatement("insert into app_user(user_id, username, display_name, password_hash,"
                    + " must_change_password, auth_source, external_subject, password_changed_at)"
                    + " values (?, ?, ?, ?, ?, ?, ?, now())")) {
                s.setObject(1, userId);
                s.setString(2, user.username());
                s.setString(3, user.displayName());
                s.setString(4, user.passwordHash());
                s.setBoolean(5, user.mustChangePassword());
                s.setString(6, user.authSource());
                s.setString(7, user.externalSubject());
                s.executeUpdate();
            }
            replaceRoles(c, userId, user.roles());
            replaceScope(c, userId, user.scope());
        });
        return userId;
    }

    public void updateProfile(UUID userId, String displayName, Collection<String> roles, Collection<String> scope) {
        inTransaction(c -> {
            try (var s = c.prepareStatement("update app_user set display_name = ?, updated_at = now() where user_id = ?")) {
                s.setString(1, displayName);
                s.setObject(2, userId);
                s.executeUpdate();
            }
            replaceRoles(c, userId, roles);
            replaceScope(c, userId, scope);
        });
    }

    public void updatePassword(UUID userId, String passwordHash, boolean mustChangePassword) {
        update("update app_user set password_hash = ?, must_change_password = ?, password_changed_at = now(),"
                + " failed_attempts = 0, locked_until = null, updated_at = now() where user_id = ?",
                passwordHash, mustChangePassword, userId);
    }

    /** One more failed sign-in; locks the account for {@code lockFor} once {@code maxAttempts} is reached. */
    public boolean recordFailure(UUID userId, int maxAttempts, Duration lockFor, OffsetDateTime now) {
        try (Connection c = connections.open();
                var s = c.prepareStatement("update app_user set failed_attempts = failed_attempts + 1,"
                        + " locked_until = case when failed_attempts + 1 >= ? then ? else locked_until end,"
                        + " updated_at = now() where user_id = ? returning failed_attempts")) {
            s.setInt(1, maxAttempts);
            s.setObject(2, now.plus(lockFor));
            s.setObject(3, userId);
            try (var r = s.executeQuery()) {
                return r.next() && r.getInt(1) >= maxAttempts;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot record a failed sign-in", e);
        }
    }

    public void recordSuccess(UUID userId, OffsetDateTime now) {
        update("update app_user set failed_attempts = 0, locked_until = null, last_login_at = ?, updated_at = now()"
                + " where user_id = ?", now, userId);
    }

    public void unlock(UUID userId) {
        update("update app_user set failed_attempts = 0, locked_until = null, updated_at = now() where user_id = ?",
                userId);
    }

    public void setEnabled(UUID userId, boolean enabled) {
        update("update app_user set enabled = ?, updated_at = now() where user_id = ?", enabled, userId);
    }

    /** OIDC users: roles and scope come from the identity provider on every sign-in. */
    public void syncExternal(UUID userId, String displayName, Collection<String> roles, Collection<String> scope) {
        updateProfile(userId, displayName, roles, scope);
    }

    public List<RoleDefinition> roles() {
        try (Connection c = connections.open();
                var s = c.prepareStatement("select r.role_code, r.description, rp.permission_code from app_role r"
                        + " left join app_role_permission rp on rp.role_code = r.role_code"
                        + " order by r.role_code, rp.permission_code");
                var r = s.executeQuery()) {
            Map<String, String> descriptions = new LinkedHashMap<>();
            Map<String, List<String>> permissions = new LinkedHashMap<>();
            while (r.next()) {
                String role = r.getString(1);
                descriptions.put(role, r.getString(2));
                List<String> list = permissions.computeIfAbsent(role, key -> new ArrayList<>());
                if (r.getString(3) != null) {
                    list.add(r.getString(3));
                }
            }
            List<RoleDefinition> roles = new ArrayList<>();
            descriptions.forEach((role, description) ->
                    roles.add(new RoleDefinition(role, description, List.copyOf(permissions.get(role)))));
            return roles;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read roles", e);
        }
    }

    public List<PermissionDefinition> permissions() {
        try (Connection c = connections.open();
                var s = c.prepareStatement("select permission_code, description from app_permission"
                        + " order by permission_code");
                var r = s.executeQuery()) {
            List<PermissionDefinition> permissions = new ArrayList<>();
            while (r.next()) {
                permissions.add(new PermissionDefinition(r.getString(1), r.getString(2)));
            }
            return permissions;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read permissions", e);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private Optional<UserAccount> one(String sql, Object... parameters) {
        try (Connection c = connections.open(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(account(c, r)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the user", e);
        }
    }

    private UserAccount account(Connection c, ResultSet r) throws SQLException {
        UUID userId = r.getObject("user_id", UUID.class);
        Set<String> roles = new TreeSet<>();
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        try (var s = c.prepareStatement("select ur.role_code, rp.permission_code from app_user_role ur"
                + " left join app_role_permission rp on rp.role_code = ur.role_code where ur.user_id = ?")) {
            s.setObject(1, userId);
            try (var rows = s.executeQuery()) {
                while (rows.next()) {
                    roles.add(rows.getString(1));
                    String permission = rows.getString(2);
                    if (permission != null && isPermission(permission)) {
                        permissions.add(Permission.valueOf(permission));
                    }
                }
            }
        }
        List<String> scope = new ArrayList<>();
        try (var s = c.prepareStatement("select department_code from user_department_scope where user_id = ?")) {
            s.setObject(1, userId);
            try (var rows = s.executeQuery()) {
                while (rows.next()) {
                    scope.add(rows.getString(1));
                }
            }
        }
        return new UserAccount(userId, r.getString("username"), r.getString("display_name"),
                r.getString("password_hash"), r.getBoolean("enabled"), r.getInt("failed_attempts"),
                r.getObject("locked_until", OffsetDateTime.class), r.getBoolean("must_change_password"),
                r.getString("auth_source"), r.getString("external_subject"),
                r.getObject("last_login_at", OffsetDateTime.class), r.getObject("created_at", OffsetDateTime.class),
                roles, permissions, DepartmentScope.of(scope));
    }

    private static boolean isPermission(String code) {
        for (Permission permission : Permission.values()) {
            if (permission.name().equals(code)) {
                return true;
            }
        }
        return false;
    }

    private static void replaceRoles(Connection c, UUID userId, Collection<String> roles) throws SQLException {
        try (var s = c.prepareStatement("delete from app_user_role where user_id = ?")) {
            s.setObject(1, userId);
            s.executeUpdate();
        }
        for (String role : new TreeSet<>(roles)) {
            try (var s = c.prepareStatement("insert into app_user_role(user_id, role_code) values (?, ?)")) {
                s.setObject(1, userId);
                s.setString(2, role);
                s.executeUpdate();
            }
        }
    }

    private static void replaceScope(Connection c, UUID userId, Collection<String> scope) throws SQLException {
        try (var s = c.prepareStatement("delete from user_department_scope where user_id = ?")) {
            s.setObject(1, userId);
            s.executeUpdate();
        }
        for (String code : DepartmentScope.of(scope).values()) {
            try (var s = c.prepareStatement("insert into user_department_scope(user_id, department_code) values (?, ?)")) {
                s.setObject(1, userId);
                s.setString(2, code);
                s.executeUpdate();
            }
        }
    }

    private void update(String sql, Object... parameters) {
        try (Connection c = connections.open(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot update the user", e);
        }
    }

    @FunctionalInterface
    private interface Work {
        void run(Connection c) throws SQLException;
    }

    private void inTransaction(Work work) {
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            try {
                work.run(c);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save the user", e);
        }
    }
}
