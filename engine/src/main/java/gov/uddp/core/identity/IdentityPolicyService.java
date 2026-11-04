package gov.uddp.core.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.rules.MakerChecker;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Versions of the Identity Policy (Track E3, V82): a draft is submitted and approved by someone other than its author
 * ({@link MakerChecker}); approving a version retires the previously approved one. {@link #effective(Connection)} is
 * the approved version, if any; with none, matching and Golden builds are unchanged.
 */
@Component
public class IdentityPolicyService {
    /** One stored version. */
    public record IdentityPolicyVersion(UUID policyId, int version, String status, IdentityPolicy policy, String createdBy,
                          String submittedBy, String decidedBy, String decisionReason, OffsetDateTime createdAt) {
    }

    private final PostgresConnectionFactory connections;
    private final IdentifierTypeRegistry registry;
    private final ObjectMapper json = new ObjectMapper();
    private final MakerChecker approvals;

    public IdentityPolicyService(PostgresConnectionFactory connections, IdentifierTypeRegistry registry) {
        this.connections = connections;
        this.registry = registry;
        this.approvals = new MakerChecker(connections, "identity_policy", "policy_id", "identity policy");
    }

    public IdentifierTypeRegistry registry() {
        return registry;
    }

    public IdentityPolicyVersion draft(IdentityPolicy policy, String actor) {
        policy.validate(registry);
        UUID id = UUID.randomUUID();
        try (var c = connections.open(); var s = c.prepareStatement("insert into identity_policy(policy_id, version,"
                + " status, config, created_by) values (?, (select coalesce(max(version), 0) + 1 from identity_policy),"
                + " 'DRAFT', cast(? as jsonb), ?)")) {
            s.setObject(1, id);
            s.setString(2, json.writeValueAsString(policy));
            s.setString(3, actor);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot save the identity policy draft", e);
        }
        return version(id);
    }

    public IdentityPolicyVersion submit(UUID id, String actor) {
        approvals.submit(id, actor);
        return version(id);
    }

    public IdentityPolicyVersion approve(UUID id, String approver, String reason) {
        var state = approvals.state(id);
        if ("PENDING_APPROVAL".equals(state.status()) && approver != null
                && !approver.equals(state.createdBy()) && !approver.equals(state.submittedBy())) {
            try (var c = connections.open(); var s = c.prepareStatement("update identity_policy set status = 'RETIRED'"
                    + " where status = 'APPROVED' and policy_id <> ?")) {
                s.setObject(1, id);
                s.executeUpdate();
            } catch (Exception e) {
                throw new IllegalStateException("Cannot retire the previous identity policy", e);
            }
        }
        approvals.approve(id, approver, reason);
        return version(id);
    }

    public IdentityPolicyVersion reject(UUID id, String approver, String reason) {
        approvals.reject(id, approver, reason);
        return version(id);
    }

    public Optional<IdentityPolicy> effective() {
        try (var c = connections.open()) {
            return effective(c);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the identity policy", e);
        }
    }

    /** The approved policy, read on the caller's connection (inside a Golden build's transaction). */
    public Optional<IdentityPolicy> effective(Connection c) throws Exception {
        try (var s = c.prepareStatement("select config::text from identity_policy where status = 'APPROVED'")) {
            var r = s.executeQuery();
            return r.next() ? Optional.of(json.readValue(r.getString(1), IdentityPolicy.class)) : Optional.empty();
        }
    }

    public Optional<IdentityPolicyVersion> effectiveVersion() {
        return list().stream().filter(v -> "APPROVED".equals(v.status())).findFirst();
    }

    public List<IdentityPolicyVersion> list() {
        List<IdentityPolicyVersion> out = new ArrayList<>();
        try (var c = connections.open(); var s = c.prepareStatement("select policy_id from identity_policy"
                + " order by version desc"); var r = s.executeQuery()) {
            while (r.next()) {
                out.add(version(r.getObject(1, UUID.class)));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot list identity policies", e);
        }
        return out;
    }

    public IdentityPolicyVersion version(UUID id) {
        try (var c = connections.open(); var s = c.prepareStatement("select version, status, config::text, created_by,"
                + " submitted_by, decided_by, decision_reason, created_at from identity_policy where policy_id = ?")) {
            s.setObject(1, id);
            var r = s.executeQuery();
            if (!r.next()) {
                throw new IllegalArgumentException("identity policy does not exist: " + id);
            }
            return new IdentityPolicyVersion(id, r.getInt(1), r.getString(2), json.readValue(r.getString(3), IdentityPolicy.class),
                    r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getObject(8, OffsetDateTime.class));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the identity policy", e);
        }
    }
}
