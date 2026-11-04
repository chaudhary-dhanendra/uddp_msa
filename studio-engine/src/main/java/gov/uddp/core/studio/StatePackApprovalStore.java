package gov.uddp.core.studio;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.studio.StudioModels.Approval;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Submitted State Pack versions ({@code state_pack_approval}): the exact pack document that was reviewed, who
 * submitted it and who approved or rejected it. A pack has at most one pending submission; the database refuses a
 * decision by the submitter.
 */
@Repository
public class StatePackApprovalStore {
    public static final String PENDING = "PENDING_APPROVAL";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private static final String COLUMNS = "select approval_id, draft_id, pack_id, state_code, version, status,"
            + " submitted_by, submitted_at, decided_by, decided_at, decision_reason, files::text as files_text"
            + " from state_pack_approval";

    private final PostgresConnectionFactory connections;
    private final ObjectMapper json;

    public StatePackApprovalStore(PostgresConnectionFactory connections, ObjectMapper json) {
        this.connections = connections;
        this.json = json;
    }

    public Approval submit(String draftId, String packId, String stateCode, String version, LoadedStatePack pack,
                           List<String> files, String submittedBy) {
        UUID id = UUID.randomUUID();
        try (var c = connections.open();
                var s = c.prepareStatement("insert into state_pack_approval(approval_id, draft_id, pack_id, state_code,"
                        + " version, status, submitted_by, pack_document, files)"
                        + " values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb))")) {
            s.setObject(1, id);
            s.setString(2, draftId);
            s.setString(3, packId);
            s.setString(4, stateCode);
            s.setString(5, version);
            s.setString(6, PENDING);
            s.setString(7, submittedBy);
            s.setString(8, json.writeValueAsString(pack));
            s.setString(9, json.writeValueAsString(files));
            s.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) {
                throw new StudioConflictException("Pack " + packId + " already has a version waiting for approval");
            }
            throw new IllegalStateException("Cannot submit the State Pack for approval", e);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot submit the State Pack for approval", e);
        }
        return find(id.toString()).orElseThrow();
    }

    public Optional<Approval> find(String approvalId) {
        UUID id;
        try {
            id = UUID.fromString(approvalId);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        List<Approval> rows = query(COLUMNS + " where approval_id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Newest first; {@code status} null lists every submission. */
    public List<Approval> list(String status) {
        return query(COLUMNS + " where (cast(? as text) is null or status = cast(? as text))"
                + " order by submitted_at desc", status, status);
    }

    /** The pack document exactly as it was submitted. */
    public LoadedStatePack document(String approvalId) {
        try (var c = connections.open();
                var s = c.prepareStatement("select pack_document::text from state_pack_approval where approval_id = ?")) {
            s.setObject(1, UUID.fromString(approvalId));
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    throw new StudioNotFoundException("Unknown approval: " + approvalId);
                }
                return json.readValue(r.getString(1), LoadedStatePack.class);
            }
        } catch (StudioNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the submitted State Pack", e);
        }
    }

    /** Records the decision on a pending submission; a submission already decided is a conflict. */
    public Approval decide(String approvalId, String status, String decidedBy, String reason) {
        try (var c = connections.open();
                var s = c.prepareStatement("update state_pack_approval set status = ?, decided_by = ?, decided_at = now(),"
                        + " decision_reason = ? where approval_id = ? and status = ?")) {
            s.setString(1, status);
            s.setString(2, decidedBy);
            s.setString(3, reason);
            s.setObject(4, UUID.fromString(approvalId));
            s.setString(5, PENDING);
            if (s.executeUpdate() == 0) {
                throw new StudioConflictException("The submission is no longer waiting for approval");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot record the approval decision", e);
        }
        return find(approvalId).orElseThrow();
    }

    private List<Approval> query(String sql, Object... parameters) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (var r = s.executeQuery()) {
                List<Approval> out = new ArrayList<>();
                while (r.next()) {
                    out.add(approval(r));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read State Pack approvals", e);
        }
    }

    private Approval approval(ResultSet r) throws SQLException {
        List<String> files;
        try {
            files = json.readValue(r.getString("files_text"), new TypeReference<List<String>>() { });
        } catch (Exception e) {
            files = List.of();
        }
        return new Approval(r.getString("approval_id"), r.getString("draft_id"), r.getString("pack_id"),
                r.getString("state_code"), r.getString("version"), r.getString("status"), r.getString("submitted_by"),
                r.getObject("submitted_at", OffsetDateTime.class), r.getString("decided_by"),
                r.getObject("decided_at", OffsetDateTime.class), r.getString("decision_reason"), files);
    }
}
