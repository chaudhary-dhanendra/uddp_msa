package gov.uddp.shaping.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.database.ConnectionSource;
import gov.uddp.core.rules.MakerChecker;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Proposed shaping changes (word lists, plug-in approvals) and their maker-checker state, in {@code shaping_change}. */
@Component
public class ShapingChanges {
    public static final String WORD_LIST = "WORD_LIST";
    public static final String PLUGIN = "PLUGIN";

    /** One proposed change; {@code payload} is the proposed content (word list groups, or the plug-in's identity). */
    public record Change(UUID changeId, String kind, String action, String target, JsonNode payload, String status,
                         String createdBy, String submittedBy, String decidedBy, String decisionReason,
                         OffsetDateTime createdAt) {
    }

    private static final String COLUMNS = "change_id, kind, action, target, payload::text, status, created_by,"
            + " submitted_by, decided_by, decision_reason, created_at";

    private final ConnectionSource connections;
    private final ObjectMapper json;
    private final MakerChecker approvals;

    public ShapingChanges(ConnectionSource connections, ObjectMapper json) {
        this.connections = connections;
        this.json = json;
        this.approvals = new MakerChecker(connections, "shaping_change", "change_id", "shaping change");
    }

    /** Records the change and submits it at once; a different user then approves or rejects it. */
    public Change propose(String kind, String action, String target, JsonNode payload, String actor) {
        UUID id = UUID.randomUUID();
        try (var c = connections.open(); var s = c.prepareStatement("insert into shaping_change(change_id, kind, action,"
                + " target, payload, status, created_by) values (?, ?, ?, ?, cast(? as jsonb), 'DRAFT', ?)")) {
            s.setObject(1, id);
            s.setString(2, kind);
            s.setString(3, action);
            s.setString(4, target);
            s.setString(5, json.writeValueAsString(payload));
            s.setString(6, actor);
            s.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot record the shaping change", e);
        }
        approvals.submit(id, actor);
        return get(id);
    }

    public Change get(UUID id) {
        try (var c = connections.open();
                var s = c.prepareStatement("select " + COLUMNS + " from shaping_change where change_id = ?")) {
            s.setObject(1, id);
            var r = s.executeQuery();
            if (!r.next()) {
                throw new IllegalArgumentException("Shaping change does not exist: " + id);
            }
            return read(r);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the shaping change", e);
        }
    }

    /** Newest first; {@code status} and {@code kind} are optional filters. */
    public List<Change> list(String status, String kind) {
        List<Change> changes = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select " + COLUMNS + " from shaping_change where (?::text is null or status = ?)"
                        + " and (?::text is null or kind = ?) order by created_at desc, change_id")) {
            s.setString(1, status);
            s.setString(2, status);
            s.setString(3, kind);
            s.setString(4, kind);
            var r = s.executeQuery();
            while (r.next()) {
                changes.add(read(r));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot list shaping changes", e);
        }
        return changes;
    }

    /** The change waiting for approval for one target, if any. */
    public Optional<Change> pending(String kind, String target) {
        return list("PENDING_APPROVAL", kind).stream().filter(change -> change.target().equals(target)).findFirst();
    }

    /** Marks the change approved (never by its author); the caller applies it. */
    public Change approve(UUID id, String approver, String reason) {
        approvals.approve(id, approver, reason);
        return get(id);
    }

    public Change reject(UUID id, String approver, String reason) {
        approvals.reject(id, approver, reason);
        return get(id);
    }

    private Change read(java.sql.ResultSet r) throws Exception {
        return new Change(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
                json.readTree(r.getString(5)), r.getString(6), r.getString(7), r.getString(8), r.getString(9),
                r.getString(10), r.getObject(11, OffsetDateTime.class));
    }
}
