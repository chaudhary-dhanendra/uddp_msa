package gov.uddp.core.security;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.sql.SQLException;
import java.util.Collection;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Checks for service entry points and for {@code @PreAuthorize} expressions that need more than one permission
 * name ({@code @access.*}): permissions, the state scope, and the setup-mode rules (ADR-0018, ADR-0019).
 */
@Service("access")
public class AuthorizationService {
    private final PostgresConnectionFactory connections;
    private final UserAccountStore users;

    public AuthorizationService(PostgresConnectionFactory connections, UserAccountStore users) {
        this.connections = connections;
        this.users = users;
    }

    public void require(Permission permission) {
        if (!CurrentUser.has(permission)) {
            throw new AccessDeniedException("Missing permission: " + permission);
        }
    }

    /** The permission, and the state in the user's scope. */
    public void requireState(Permission permission, String stateCode) {
        require(permission);
        if (!CurrentUser.scope().allows(stateCode)) {
            throw new AccessDeniedException("Source is outside the assigned departments: " + stateCode);
        }
    }

    /** The permission, and every one of the states in the user's scope. */
    public void requireStates(Permission permission, Collection<String> stateCodes) {
        require(permission);
        if (!CurrentUser.scope().allowsAll(stateCodes)) {
            throw new AccessDeniedException("A source is outside the assigned departments: " + stateCodes);
        }
    }

    /**
     * Setup and settings endpoints: open while the platform has no database (setup mode, nothing to protect yet and
     * nobody to sign in), afterwards only for {@link Permission#PLATFORM_SETTINGS}.
     */
    public boolean setupAllowed() {
        return !connections.isConfigured() || CurrentUser.has(Permission.PLATFORM_SETTINGS);
    }

    /** Creating the first administrator: only once the database is configured and while no user exists. */
    public boolean bootstrapAllowed() {
        return connections.isConfigured() && users.count() == 0;
    }

    /**
     * For platform-wide operations and the pre-v1 views that are not state-scoped (a Golden build spans every state):
     * only users who see every state.
     */
    public boolean everyState() {
        return CurrentUser.scope().all();
    }

    /** True when the ingestion batch belongs to a state in the user's scope (false for an unknown batch). */
    public boolean batchInScope(UUID batchId) {
        StateScope scope = CurrentUser.scope();
        if (scope.all()) {
            return true;
        }
        try (var c = connections.open();
                var s = c.prepareStatement("select state_code from ingestion_batch where batch_id = ?")) {
            s.setObject(1, batchId);
            try (var r = s.executeQuery()) {
                return r.next() && scope.allows(r.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the ingestion batch", e);
        }
    }
}
