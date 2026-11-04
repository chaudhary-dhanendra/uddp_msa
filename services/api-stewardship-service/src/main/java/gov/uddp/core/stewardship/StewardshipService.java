package gov.uddp.core.stewardship;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.golden.GoldenBuildSummary;
import gov.uddp.core.read.ReadNotFoundException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Records stewardship decisions (M13, docs/stewardship.md, ADR-0010) and applies them by rebuilding Golden Residents
 * against the latest completed matching run. A decision and its link constraints are written in one transaction,
 * serialised with Golden builds; nothing here changes matching evidence, canonical rows or Golden history directly.
 */
@Service
public class StewardshipService {
    private static final String UNIQUE_VIOLATION = "23505";

    private final PostgresConnectionFactory connections;
    private final gov.uddp.core.onboarding.GoldenBuildRunner golden;
    private gov.uddp.core.events.PlatformEvents events;
    private final ObjectMapper json;

    public StewardshipService(PostgresConnectionFactory connections, gov.uddp.core.onboarding.GoldenBuildRunner golden,
                              ObjectMapper json) {
        this.connections = connections;
        this.golden = golden;
        this.json = json;
    }

    /** A recorded decision and the Golden build that applied it. */
    public record DecisionResult(UUID decisionId, String action, String reviewer, String reason,
                                 List<UUID> goldenResidentIds, int constraints, UUID matchingRunId,
                                 GoldenBuildSummary goldenBuild) {
    }

    private record Constraint(String keyA, String keyB, String type) {
        static Constraint of(String one, String other, String type) {
            return one.compareTo(other) < 0 ? new Constraint(one, other, type) : new Constraint(other, one, type);
        }
    }

    private record Pending(String action, UUID reviewTask, List<UUID> goldenIds, Map<String, Object> detail,
                           List<Constraint> constraints) {
    }

    /** ACCEPT records MUST_LINK for the task's pair, REJECT records CANNOT_LINK. */
    public DecisionResult decide(UUID reviewTaskId, String decision, String reviewer, String reason) {
        requireText(reviewer, "reviewer");
        requireText(reason, "reason");
        String normalized = decision == null ? "" : decision.strip().toUpperCase(Locale.ROOT);
        if (!normalized.equals("ACCEPT") && !normalized.equals("REJECT")) {
            throw new IllegalArgumentException("decision must be ACCEPT or REJECT");
        }
        return record(reviewer, reason, c -> {
            String keyA;
            String keyB;
            try (var s = c.prepareStatement("select record_key_a, record_key_b from stewardship_review_task"
                    + " where review_task_id = ?")) {
                s.setObject(1, reviewTaskId);
                var r = s.executeQuery();
                if (!r.next()) {
                    throw new ReadNotFoundException("Review task does not exist: " + reviewTaskId);
                }
                keyA = r.getString(1);
                keyB = r.getString(2);
            }
            if (exists(c, "select 1 from stewardship_decision where review_task_id = ?", reviewTaskId)) {
                throw new StewardshipConflictException("Review task is already decided: " + reviewTaskId);
            }
            List<UUID> goldens = new ArrayList<>();
            for (String key : List.of(keyA, keyB)) {
                UUID current = currentGolden(c, key);
                if (current != null && !goldens.contains(current)) {
                    goldens.add(current);
                }
            }
            boolean accept = normalized.equals("ACCEPT");
            return new Pending(accept ? "ACCEPT_MATCH" : "REJECT_MATCH", reviewTaskId, goldens,
                    Map.of("recordKeyA", keyA, "recordKeyB", keyB),
                    List.of(Constraint.of(keyA, keyB, accept ? "MUST_LINK" : "CANNOT_LINK")));
        });
    }

    /** MUST_LINK between every pair of current members across two or more active Golden Residents. */
    public DecisionResult merge(List<UUID> goldenResidentIds, String reviewer, String reason) {
        requireText(reviewer, "reviewer");
        requireText(reason, "reason");
        Set<UUID> ids = goldenResidentIds == null ? Set.of() : new LinkedHashSet<>(goldenResidentIds);
        if (ids.size() < 2 || ids.contains(null)) {
            throw new IllegalArgumentException("merge needs at least two different goldenResidentIds");
        }
        return record(reviewer, reason, c -> {
            List<List<String>> sides = new ArrayList<>();
            for (UUID id : ids) {
                requireActive(c, id);
                sides.add(members(c, id));
            }
            List<Constraint> constraints = new ArrayList<>();
            for (int i = 0; i < sides.size(); i++) {
                for (int j = i + 1; j < sides.size(); j++) {
                    for (String a : sides.get(i)) {
                        for (String b : sides.get(j)) {
                            constraints.add(Constraint.of(a, b, "MUST_LINK"));
                        }
                    }
                }
            }
            return new Pending("MERGE", null, List.copyOf(ids), Map.of(), constraints);
        });
    }

    /**
     * Detaches {@code sourceRecordKeys} (a non-empty proper subset of the identity's current members) into their own
     * identity: CANNOT_LINK across the two sides, MUST_LINK along each side.
     */
    public DecisionResult split(UUID goldenResidentId, List<String> sourceRecordKeys, String reviewer, String reason) {
        requireText(reviewer, "reviewer");
        requireText(reason, "reason");
        if (goldenResidentId == null) {
            throw new IllegalArgumentException("goldenResidentId is required");
        }
        Set<String> detached = sourceRecordKeys == null ? Set.of() : new TreeSet<>(sourceRecordKeys);
        if (detached.isEmpty()) {
            throw new IllegalArgumentException("split needs at least one sourceRecordKey to detach");
        }
        return record(reviewer, reason, c -> {
            requireActive(c, goldenResidentId);
            List<String> members = members(c, goldenResidentId);
            List<String> unknown = detached.stream().filter(key -> !members.contains(key)).toList();
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("Not current members of " + goldenResidentId + ": " + unknown);
            }
            List<String> remaining = members.stream().filter(key -> !detached.contains(key)).toList();
            if (remaining.isEmpty()) {
                throw new IllegalArgumentException("split must leave at least one member with " + goldenResidentId);
            }
            List<Constraint> constraints = new ArrayList<>();
            for (String a : detached) {
                for (String b : remaining) {
                    constraints.add(Constraint.of(a, b, "CANNOT_LINK"));
                }
            }
            chain(new ArrayList<>(detached), constraints);
            chain(remaining, constraints);
            return new Pending("SPLIT", null, List.of(goldenResidentId),
                    Map.of("detachedSourceRecordKeys", List.copyOf(detached), "remainingSourceRecordKeys", remaining),
                    constraints);
        });
    }

    @FunctionalInterface
    private interface Plan {
        Pending in(Connection c) throws Exception;
    }

    private DecisionResult record(String reviewer, String reason, Plan plan) {
        UUID run = latestCompletedRun();
        UUID decision = UUID.randomUUID();
        Pending pending;
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                try (var s = c.prepareStatement("select pg_advisory_xact_lock(hashtext('uddp.golden-resident-build'))")) {
                    s.execute();
                }
                pending = plan.in(c);
                insertDecision(c, decision, pending, reviewer.strip(), reason.strip());
                if (events != null) {
                    events.decisionRecorded(c, decision);
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new StewardshipConflictException("Review task is already decided");
            }
            throw new IllegalStateException("Cannot record stewardship decision", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot record stewardship decision", e);
        }
        GoldenBuildSummary build = golden.build(run);
        return new DecisionResult(decision, pending.action(), reviewer.strip(), reason.strip(), pending.goldenIds(),
                pending.constraints().size(), run, build);
    }

    /** MSA Phase 3: with events enabled, each decision is published (registry-service applies its constraints). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEvents(gov.uddp.core.events.PlatformEvents events) {
        this.events = events;
    }

    private void insertDecision(Connection c, UUID id, Pending pending, String reviewer, String reason) throws Exception {
        try (var s = c.prepareStatement("insert into stewardship_decision(decision_id, action, review_task_id, reviewer,"
                + " reason, golden_resident_ids, detail) values (?,?,?,?,?,cast(? as jsonb),cast(? as jsonb))")) {
            s.setObject(1, id);
            s.setString(2, pending.action());
            s.setObject(3, pending.reviewTask());
            s.setString(4, reviewer);
            s.setString(5, reason);
            s.setString(6, json.writeValueAsString(pending.goldenIds()));
            s.setString(7, json.writeValueAsString(pending.detail()));
            s.executeUpdate();
        }
        Map<String, Constraint> unique = new LinkedHashMap<>();
        pending.constraints().forEach(x -> unique.put(x.keyA() + "\u0000" + x.keyB(), x));
        try (var s = c.prepareStatement("insert into stewardship_link_constraint(decision_id, record_key_a,"
                + " record_key_b, constraint_type) values (?,?,?,?)")) {
            for (Constraint x : unique.values()) {
                s.setObject(1, id);
                s.setString(2, x.keyA());
                s.setString(3, x.keyB());
                s.setString(4, x.type());
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    private UUID latestCompletedRun() {
        try (var c = connections.open();
                var s = c.prepareStatement("select run_id from matching_run where status = 'COMPLETED'"
                        + " order by completed_at desc nulls last, started_at desc limit 1");
                var r = s.executeQuery()) {
            if (!r.next()) {
                throw new StewardshipConflictException("No completed matching run to rebuild Golden Residents with");
            }
            return r.getObject(1, UUID.class);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read matching runs", e);
        }
    }

    private static void requireActive(Connection c, UUID id) throws SQLException {
        try (var s = c.prepareStatement("select status from golden_resident where golden_resident_id = ?")) {
            s.setObject(1, id);
            var r = s.executeQuery();
            if (!r.next()) {
                throw new ReadNotFoundException("Golden Resident does not exist: " + id);
            }
            if (!"ACTIVE".equals(r.getString(1))) {
                throw new StewardshipConflictException("Golden Resident is " + r.getString(1) + ": " + id);
            }
        }
    }

    /** Current members' source record keys, in key order. */
    private static List<String> members(Connection c, UUID id) throws SQLException {
        List<String> out = new ArrayList<>();
        try (var s = c.prepareStatement("select source_record_key from golden_resident_membership"
                + " where golden_resident_id = ? and effective_to is null order by source_record_key")) {
            s.setObject(1, id);
            var r = s.executeQuery();
            while (r.next()) {
                out.add(r.getString(1));
            }
        }
        return out;
    }

    private static UUID currentGolden(Connection c, String key) throws SQLException {
        try (var s = c.prepareStatement("select golden_resident_id from golden_resident_membership"
                + " where source_record_key = ? and effective_to is null")) {
            s.setString(1, key);
            var r = s.executeQuery();
            return r.next() ? r.getObject(1, UUID.class) : null;
        }
    }

    private static boolean exists(Connection c, String sql, UUID id) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            return s.executeQuery().next();
        }
    }

    private static void chain(List<String> keys, List<Constraint> constraints) {
        for (int i = 1; i < keys.size(); i++) {
            constraints.add(Constraint.of(keys.get(i - 1), keys.get(i), "MUST_LINK"));
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
