package gov.uddp.core.rules;

import gov.uddp.core.database.ConnectionSource;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;

/**
 * The shared maker-checker steps for versioned configuration rows (rule sets, Scheme Pack versions): DRAFT is
 * submitted to PENDING_APPROVAL, then a different user approves or rejects it. There is no auto-approval path, so a
 * change to eligibility or any other rule always needs a human approver.
 */
public final class MakerChecker {
    private final ConnectionSource connections;
    private final String table;
    private final String idColumn;
    private final String label;

    public MakerChecker(ConnectionSource connections, String table, String idColumn, String label) {
        this.connections = connections;
        this.table = table;
        this.idColumn = idColumn;
        this.label = label;
    }

    public void submit(UUID id, String actor) {
        requireActor(actor);
        State state = state(id);
        if (!"DRAFT".equals(state.status())) {
            throw new ApprovalConflictException("Only a DRAFT " + label + " can be submitted, not " + state.status());
        }
        update(id, "update " + table + " set status = 'PENDING_APPROVAL', submitted_by = ?, submitted_at = now()"
                + " where " + idColumn + " = ? and status = 'DRAFT'", actor);
    }

    public void approve(UUID id, String approver, String reason) {
        decide(id, approver, "APPROVED", reason);
    }

    public void reject(UUID id, String approver, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A rejection needs a reason");
        }
        decide(id, approver, "REJECTED", reason);
    }

    /** Current status, maker and submitter of one row. */
    public State state(UUID id) {
        try (var c = connections.open();
                var s = c.prepareStatement("select status, created_by, submitted_by from " + table
                        + " where " + idColumn + " = ?")) {
            s.setObject(1, id);
            var r = s.executeQuery();
            if (!r.next()) {
                throw new IllegalArgumentException(label + " does not exist: " + id);
            }
            return new State(r.getString(1), r.getString(2), r.getString(3));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the " + label, e);
        }
    }

    public record State(String status, String createdBy, String submittedBy) {
    }

    private void decide(UUID id, String approver, String decision, String reason) {
        requireActor(approver);
        State state = state(id);
        if (!"PENDING_APPROVAL".equals(state.status())) {
            throw new ApprovalConflictException("Only a submitted " + label + " can be decided, not " + state.status());
        }
        if (approver.equals(state.createdBy()) || approver.equals(state.submittedBy())) {
            throw new AccessDeniedException("A " + label + " must be approved or rejected by someone other than its"
                    + " author and submitter");
        }
        update(id, "update " + table + " set status = '" + decision + "', decided_by = ?, decided_at = now(),"
                + " decision_reason = ? where " + idColumn + " = ? and status = 'PENDING_APPROVAL'", approver,
                reason == null || reason.isBlank() ? null : reason.strip());
    }

    private void update(UUID id, String sql, String... values) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            int i = 1;
            for (String value : values) {
                s.setString(i++, value);
            }
            s.setObject(i, id);
            if (s.executeUpdate() != 1) {
                throw new ApprovalConflictException("The " + label + " changed meanwhile: " + id);
            }
        } catch (ApprovalConflictException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot update the " + label, e);
        }
    }

    private void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new AccessDeniedException("A signed-in user is required to submit or decide a " + label);
        }
    }
}
