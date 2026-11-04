package gov.uddp.core.stewardship;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.read.ReadNotFoundException;
import gov.uddp.core.stewardship.StewardshipService.DecisionResult;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Stewardship maker-checker (P1 Part 4b): accept, reject, merge and split are proposed by one steward and applied by
 * the existing {@link StewardshipService} logic only when a different user approves. A proposal whose Golden
 * Residents changed since it was proposed is stale and refused. With {@code uddp.stewardship.maker-checker=false}
 * decisions are applied directly, as before.
 */
@Service
public class StewardshipProposalService {
    public static final String PENDING = "PENDING_APPROVAL";
    public static final Set<String> ACTIONS = Set.of("ACCEPT_MATCH", "REJECT_MATCH", "MERGE", "SPLIT");

    /** A proposed decision and its outcome. */
    public record Proposal(UUID proposalId, String action, UUID reviewTaskId, List<UUID> goldenResidentIds,
                           List<String> sourceRecordKeys, List<String> stateCodes, String reason, String status,
                           String proposedBy, OffsetDateTime proposedAt, String decidedBy, OffsetDateTime decidedAt,
                           String decisionReason, UUID decisionId) {
    }

    /** What a steward proposes; {@code action} is ACCEPT_MATCH, REJECT_MATCH, MERGE or SPLIT. */
    public record ProposalRequest(String action, UUID reviewTaskId, List<UUID> goldenResidentIds,
                                  List<String> sourceRecordKeys, String reason) {
    }

    private final PostgresConnectionFactory connections;
    private final StewardshipService stewardship;
    private final ObjectMapper json;
    private final boolean makerChecker;

    public StewardshipProposalService(PostgresConnectionFactory connections, StewardshipService stewardship,
                                      ObjectMapper json,
                                      @Value("${uddp.stewardship.maker-checker:true}") boolean makerChecker) {
        this.connections = connections;
        this.stewardship = stewardship;
        this.json = json;
        this.makerChecker = makerChecker;
    }

    public boolean makerChecker() {
        return makerChecker;
    }

    /** Direct decisions are allowed only with maker-checker off. */
    public void requireDirectAllowed() {
        if (makerChecker) {
            throw new StewardshipConflictException("Stewardship decisions need a second approver: submit a proposal"
                    + " (POST /api/stewardship/proposals)");
        }
    }

    /** The Golden Residents a request involves (for scope checks), validated as far as possible without writing. */
    public List<UUID> involvedGoldens(ProposalRequest request) {
        String action = action(request);
        try (Connection c = connections.open()) {
            if (action.endsWith("_MATCH")) {
                if (request.reviewTaskId() == null) {
                    throw new IllegalArgumentException("reviewTaskId is required");
                }
                return taskGoldens(c, request.reviewTaskId());
            }
            List<UUID> ids = request.goldenResidentIds() == null ? List.of()
                    : List.copyOf(new LinkedHashSet<>(request.goldenResidentIds()));
            if (action.equals("MERGE") && ids.size() < 2) {
                throw new IllegalArgumentException("merge needs at least two different goldenResidentIds");
            }
            if (action.equals("SPLIT") && (ids.size() != 1
                    || request.sourceRecordKeys() == null || request.sourceRecordKeys().isEmpty())) {
                throw new IllegalArgumentException("split needs one goldenResidentId and the sourceRecordKeys to detach");
            }
            return ids;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the proposal's Golden Residents", e);
        }
    }

    /** Every state any member of the Golden Residents comes from. */
    public List<String> states(List<UUID> goldens) {
        try (Connection c = connections.open()) {
            return states(c, goldens);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the proposal's states", e);
        }
    }

    /** Records a proposal; nothing changes Golden data until it is approved. */
    public Proposal propose(ProposalRequest request, String proposedBy) {
        String action = action(request);
        requireText(request.reason(), "reason");
        List<UUID> goldens = involvedGoldens(request);
        UUID id = UUID.randomUUID();
        try (Connection c = connections.open();
                var s = c.prepareStatement("insert into stewardship_proposal(proposal_id, action, review_task_id,"
                        + " golden_resident_ids, detail, golden_fingerprint, state_codes, reason, status, proposed_by)"
                        + " values (?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?, ?, ?, ?, ?)")) {
            if (action.endsWith("_MATCH") && exists(c, "select 1 from stewardship_decision where review_task_id = ?",
                    request.reviewTaskId())) {
                throw new StewardshipConflictException("Review task is already decided: " + request.reviewTaskId());
            }
            for (UUID golden : goldens) {
                requireGolden(c, golden);
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("sourceRecordKeys", request.sourceRecordKeys() == null ? List.of() : request.sourceRecordKeys());
            s.setObject(1, id);
            s.setString(2, action);
            s.setObject(3, request.reviewTaskId());
            s.setString(4, json.writeValueAsString(goldens));
            s.setString(5, json.writeValueAsString(detail));
            s.setString(6, fingerprint(c, goldens));
            s.setArray(7, c.createArrayOf("text", states(c, goldens).toArray()));
            s.setString(8, request.reason().strip());
            s.setString(9, PENDING);
            s.setString(10, proposedBy);
            s.executeUpdate();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot record the stewardship proposal", e);
        }
        return find(id);
    }

    /**
     * A different user approves: the existing decision logic records the decision (as the proposer's) and rebuilds
     * Golden Residents. A proposal whose Golden Residents changed meanwhile is marked STALE and refused.
     */
    public DecisionResult approve(UUID proposalId, String approver, String reason) {
        Proposal proposal = requirePending(proposalId);
        requireOtherUser(proposal, approver);
        try (Connection c = connections.open()) {
            List<UUID> current = proposal.action().endsWith("_MATCH")
                    ? taskGoldens(c, proposal.reviewTaskId()) : proposal.goldenResidentIds();
            String stored = text(c, "select golden_fingerprint from stewardship_proposal where proposal_id = ?",
                    proposalId);
            if (!fingerprint(c, current).equals(stored)) {
                update(c, "update stewardship_proposal set status = 'STALE', decided_at = now(),"
                        + " decision_reason = 'The Golden Residents changed since the proposal was made'"
                        + " where proposal_id = ? and status = 'PENDING_APPROVAL'", proposalId);
                throw new StewardshipConflictException("The proposal is stale: its Golden Residents changed since it"
                        + " was proposed; propose again");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot check the proposal", e);
        }
        String note = proposal.reason() + " (approved by " + approver
                + (reason == null || reason.isBlank() ? "" : ": " + reason.strip()) + ")";
        DecisionResult result = switch (proposal.action()) {
            case "ACCEPT_MATCH" -> stewardship.decide(proposal.reviewTaskId(), "ACCEPT", proposal.proposedBy(), note);
            case "REJECT_MATCH" -> stewardship.decide(proposal.reviewTaskId(), "REJECT", proposal.proposedBy(), note);
            case "MERGE" -> stewardship.merge(proposal.goldenResidentIds(), proposal.proposedBy(), note);
            default -> stewardship.split(proposal.goldenResidentIds().getFirst(), proposal.sourceRecordKeys(),
                    proposal.proposedBy(), note);
        };
        try (Connection c = connections.open()) {
            update(c, "update stewardship_proposal set status = 'APPROVED', decided_by = ?, decided_at = now(),"
                    + " decision_reason = ?, decision_id = ? where proposal_id = ?", approver, blankToNull(reason),
                    result.decisionId(), proposalId);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot record the approval", e);
        }
        return result;
    }

    /** A different user rejects with a reason; nothing changes. */
    public Proposal reject(UUID proposalId, String approver, String reason) {
        requireText(reason, "reason");
        Proposal proposal = requirePending(proposalId);
        requireOtherUser(proposal, approver);
        try (Connection c = connections.open()) {
            update(c, "update stewardship_proposal set status = 'REJECTED', decided_by = ?, decided_at = now(),"
                    + " decision_reason = ? where proposal_id = ? and status = 'PENDING_APPROVAL'", approver,
                    reason.strip(), proposalId);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot record the rejection", e);
        }
        return find(proposalId);
    }

    /** Newest first; {@code status} null lists every proposal. */
    public List<Proposal> list(String status) {
        return query("select * from stewardship_proposal where (cast(? as text) is null or status = cast(? as text))"
                + " order by proposed_at desc", status, status);
    }

    public Proposal find(UUID proposalId) {
        var rows = query("select * from stewardship_proposal where proposal_id = ?", proposalId);
        if (rows.isEmpty()) {
            throw new ReadNotFoundException("Stewardship proposal does not exist: " + proposalId);
        }
        return rows.getFirst();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private Proposal requirePending(UUID proposalId) {
        Proposal proposal = find(proposalId);
        if (!PENDING.equals(proposal.status())) {
            throw new StewardshipConflictException("The proposal is no longer waiting for approval");
        }
        return proposal;
    }

    private static void requireOtherUser(Proposal proposal, String approver) {
        if (approver == null || approver.equalsIgnoreCase(proposal.proposedBy())) {
            throw new AccessDeniedException("You cannot approve or reject a proposal you made");
        }
    }

    private static String action(ProposalRequest request) {
        String action = request == null || request.action() == null ? ""
                : request.action().strip().toUpperCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException("action must be ACCEPT_MATCH, REJECT_MATCH, MERGE or SPLIT");
        }
        return action;
    }

    /** The current Golden Residents of a review task's two records. */
    private static List<UUID> taskGoldens(Connection c, UUID taskId) throws SQLException {
        try (var s = c.prepareStatement("select record_key_a, record_key_b from stewardship_review_task"
                + " where review_task_id = ?")) {
            s.setObject(1, taskId);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    throw new ReadNotFoundException("Review task does not exist: " + taskId);
                }
                Set<UUID> goldens = new LinkedHashSet<>();
                for (String key : List.of(r.getString(1), r.getString(2))) {
                    try (var g = c.prepareStatement("select golden_resident_id from golden_resident_membership"
                            + " where source_record_key = ? and effective_to is null")) {
                        g.setString(1, key);
                        try (var rows = g.executeQuery()) {
                            while (rows.next()) {
                                goldens.add(rows.getObject(1, UUID.class));
                            }
                        }
                    }
                }
                return List.copyOf(goldens);
            }
        }
    }

    /** Status and current members of each Golden Resident, so any change since the proposal is detected. */
    private static String fingerprint(Connection c, List<UUID> goldens) throws SQLException {
        List<String> parts = new ArrayList<>();
        for (UUID golden : new TreeSet<>(goldens)) {
            try (var s = c.prepareStatement("select g.status, coalesce((select string_agg(m.source_record_key, ','"
                    + " order by m.source_record_key) from golden_resident_membership m"
                    + " where m.golden_resident_id = g.golden_resident_id and m.effective_to is null), '')"
                    + " from golden_resident g where g.golden_resident_id = ?")) {
                s.setObject(1, golden);
                try (var r = s.executeQuery()) {
                    parts.add(golden + "=" + (r.next() ? r.getString(1) + ":" + r.getString(2) : "missing"));
                }
            }
        }
        return String.join(";", parts);
    }

    private static List<String> states(Connection c, List<UUID> goldens) throws SQLException {
        Set<String> states = new TreeSet<>();
        for (UUID golden : goldens) {
            try (var s = c.prepareStatement("select distinct cr.state_code from golden_resident_membership m"
                    + " join canonical_resident cr on cr.canonical_resident_id = m.canonical_resident_id"
                    + " where m.golden_resident_id = ? and m.effective_to is null")) {
                s.setObject(1, golden);
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        states.add(r.getString(1));
                    }
                }
            }
        }
        return List.copyOf(states);
    }

    private static void requireGolden(Connection c, UUID golden) throws SQLException {
        try (var s = c.prepareStatement("select status from golden_resident where golden_resident_id = ?")) {
            s.setObject(1, golden);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    throw new ReadNotFoundException("Golden Resident does not exist: " + golden);
                }
                if (!"ACTIVE".equals(r.getString(1))) {
                    throw new StewardshipConflictException("Golden Resident is retired: " + golden);
                }
            }
        }
    }

    private static boolean exists(Connection c, String sql, Object parameter) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            s.setObject(1, parameter);
            try (var r = s.executeQuery()) {
                return r.next();
            }
        }
    }

    private static String text(Connection c, String sql, Object parameter) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            s.setObject(1, parameter);
            try (var r = s.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        }
    }

    private static void update(Connection c, String sql, Object... parameters) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.executeUpdate();
        }
    }

    private List<Proposal> query(String sql, Object... parameters) {
        try (Connection c = connections.open(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (var r = s.executeQuery()) {
                List<Proposal> out = new ArrayList<>();
                while (r.next()) {
                    out.add(proposal(r));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read stewardship proposals", e);
        }
    }

    private Proposal proposal(ResultSet r) throws SQLException {
        try {
            List<UUID> goldens = json.readValue(r.getString("golden_resident_ids"), new TypeReference<List<UUID>>() { });
            Map<String, List<String>> detail = json.readValue(r.getString("detail"),
                    new TypeReference<Map<String, List<String>>>() { });
            String[] states = (String[]) r.getArray("state_codes").getArray();
            return new Proposal(r.getObject("proposal_id", UUID.class), r.getString("action"),
                    r.getObject("review_task_id", UUID.class), goldens,
                    detail.getOrDefault("sourceRecordKeys", List.of()), List.of(states), r.getString("reason"),
                    r.getString("status"), r.getString("proposed_by"), r.getObject("proposed_at", OffsetDateTime.class),
                    r.getString("decided_by"), r.getObject("decided_at", OffsetDateTime.class),
                    r.getString("decision_reason"), r.getObject("decision_id", UUID.class));
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read a stewardship proposal", e);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
