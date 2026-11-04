package gov.uddp.core.read;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.security.StateScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The stewardship review queue and decision log (M13, docs/stewardship.md): each task's two records as they are now
 * (latest canonical version, current Golden identity), its match scores and its decision. Read-only.
 */
@Service
public class StewardshipReadService {
    /** A review task; {@code linkedNow} is true when both records currently belong to the same Golden Resident. */
    public record ReviewTaskSummary(UUID reviewTaskId, String status, String taskType, String recordKeyA,
                                    String recordKeyB, UUID canonicalResidentIdA, UUID canonicalResidentIdB,
                                    String nameA, String nameB, String stateCodeA, String stateCodeB,
                                    UUID goldenResidentIdA, UUID goldenResidentIdB, boolean linkedNow,
                                    UUID matchingRunId, Double matchProbability, Double matchWeight,
                                    OffsetDateTime createdAt, UUID decisionId, String decisionAction,
                                    String decidedBy, OffsetDateTime decidedAt) {
    }

    /** One task side by side: both records' current canonical payloads and the pair's match evidence. */
    public record ReviewTaskDetail(ReviewTaskSummary task, JsonNode recordA, JsonNode recordB, String matchDecision,
                                   JsonNode evidence, String decisionReason) {
    }

    public record ConstraintView(String recordKeyA, String recordKeyB, String constraintType, boolean effective) {
    }

    public record DecisionView(UUID decisionId, long sequence, String action, UUID reviewTaskId, String reviewer,
                               String reason, JsonNode goldenResidentIds, JsonNode detail,
                               List<ConstraintView> constraints, OffsetDateTime createdAt) {
    }

    private static final Map<String, String> TASK_SORT = Map.of(
            "matchProbability", "t.match_probability", "createdAt", "t.created_at", "decidedAt", "d.created_at");
    private static final Map<String, String> DECISION_SORT = Map.of(
            "sequence", "d.decision_sequence", "createdAt", "d.created_at", "action", "d.action");
    private static final String TASKS = "select t.review_task_id, t.task_type, t.record_key_a, t.record_key_b,"
            + " t.matching_run_id, t.match_probability, t.match_weight, t.created_at, t.canonical_record_a,"
            + " t.canonical_record_b, d.decision_id, d.action, d.reviewer, d.reason, d.created_at as decided_at,"
            + " ca.canonical_resident_id as current_a, ca.canonical_payload::text as payload_a, ca.state_code as state_a,"
            + " cb.canonical_resident_id as current_b, cb.canonical_payload::text as payload_b, cb.state_code as state_b,"
            + " ga.golden_resident_id as golden_a, gb.golden_resident_id as golden_b"
            + " from stewardship_review_task t"
            + " left join stewardship_decision d on d.review_task_id = t.review_task_id"
            + " left join canonical_resident_current ca on ca.source_record_key = t.record_key_a"
            + " left join canonical_resident_current cb on cb.source_record_key = t.record_key_b"
            + " left join golden_resident_membership ga on ga.source_record_key = t.record_key_a and ga.effective_to is null"
            + " left join golden_resident_membership gb on gb.source_record_key = t.record_key_b and gb.effective_to is null";
    private static final String STATUS_FILTER = " where (cast(? as text) = 'ALL'"
            + " or (cast(? as text) = 'OPEN' and d.decision_id is null)"
            + " or (cast(? as text) = 'DECIDED' and d.decision_id is not null))";

    private final Db db;

    public StewardshipReadService(Db db) {
        this.db = db;
    }

    /** @param status OPEN (default), DECIDED or ALL */
    public Page<ReviewTaskSummary> reviews(String status, Integer page, Integer size, String sort) {
        var query = PageQuery.of(page, size, sort, TASK_SORT, "matchProbability,desc", "t.review_task_id");
        String s = status == null || status.isBlank() ? "OPEN" : status.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("OPEN", "DECIDED", "ALL").contains(s)) {
            throw new IllegalArgumentException("status must be OPEN, DECIDED or ALL");
        }
        // Both records of a task must be from states in the user's scope (ADR-0019).
        String scoped = STATUS_FILTER + Db.inScope("ca.state_code") + Db.inScope("cb.state_code");
        Object[] codes = Db.scopeParameters();
        Object[] parameters = {s, s, s, codes[0], codes[1], codes[0], codes[1]};
        long total = db.count("select count(*) from stewardship_review_task t"
                + " left join stewardship_decision d on d.review_task_id = t.review_task_id"
                + " left join canonical_resident_current ca on ca.source_record_key = t.record_key_a"
                + " left join canonical_resident_current cb on cb.source_record_key = t.record_key_b" + scoped,
                parameters);
        var items = db.list(TASKS + scoped + query.sql(), this::task, parameters);
        return Page.of(items, query, total);
    }

    public ReviewTaskDetail review(UUID id) {
        var row = db.one(TASKS + " where t.review_task_id = ?",
                        r -> new TaskRow(task(r), Db.uuid(r, "canonical_record_a"), Db.uuid(r, "canonical_record_b"),
                                db.json(r.getString("payload_a")), db.json(r.getString("payload_b")),
                                r.getString("reason")), id)
                .orElseThrow(() -> new ReadNotFoundException("Review task does not exist: " + id));
        StateScope scope = CurrentUser.scope();
        if (!scope.allows(row.task().stateCodeA()) || !scope.allows(row.task().stateCodeB())) {
            throw new ReadNotFoundException("Review task does not exist: " + id);
        }
        // The evidence of the pair as scored by the run that raised the task (stored with canonical_record_a < b).
        var evidence = db.one("select decision, evidence::text from resident_match_pair where run_id = ?"
                        + " and canonical_record_a = least(cast(? as uuid), cast(? as uuid))"
                        + " and canonical_record_b = greatest(cast(? as uuid), cast(? as uuid))",
                r -> new Object[] {r.getString(1), db.json(r.getString(2))},
                row.task().matchingRunId(), row.canonicalA(), row.canonicalB(), row.canonicalA(), row.canonicalB());
        return new ReviewTaskDetail(row.task(), row.recordA(), row.recordB(),
                evidence.map(e -> (String) e[0]).orElse(null), evidence.map(e -> (JsonNode) e[1]).orElse(null),
                row.reason());
    }

    private record TaskRow(ReviewTaskSummary task, UUID canonicalA, UUID canonicalB, JsonNode recordA,
                           JsonNode recordB, String reason) {
    }

    /** The decision log, newest first by default, optionally for one Golden Resident or action. */
    public Page<DecisionView> decisions(UUID goldenResidentId, String action, Integer page, Integer size, String sort) {
        var query = PageQuery.of(page, size, sort, DECISION_SORT, "sequence,desc", "d.decision_id");
        String a = action == null || action.isBlank() ? null : action.strip().toUpperCase(Locale.ROOT);
        if (a != null && !Set.of("ACCEPT_MATCH", "REJECT_MATCH", "MERGE", "SPLIT").contains(a)) {
            throw new IllegalArgumentException("action must be ACCEPT_MATCH, REJECT_MATCH, MERGE or SPLIT");
        }
        String golden = goldenResidentId == null ? null : "[\"" + goldenResidentId + "\"]";
        String where = " where (cast(? as text) is null or d.action = cast(? as text))"
                + " and (cast(? as text) is null or d.golden_resident_ids @> cast(? as jsonb))"
                // Every record a decision constrains must be from a state in the user's scope (ADR-0019).
                + " and (cast(? as text[]) is null or not exists (select 1 from stewardship_link_constraint l"
                + " join canonical_resident_current x on x.source_record_key in (l.record_key_a, l.record_key_b)"
                + " where l.decision_id = d.decision_id and not (x.state_code = any(cast(? as text[])))))";
        Object[] parameters = Db.withScope(a, a, golden, golden);
        long total = db.count("select count(*) from stewardship_decision d" + where, parameters);
        var rows = db.list("select d.decision_id, d.decision_sequence, d.action, d.review_task_id, d.reviewer, d.reason,"
                        + " d.golden_resident_ids::text, d.detail::text, d.created_at from stewardship_decision d"
                        + where + query.sql(),
                r -> new DecisionView(Db.uuid(r, "decision_id"), r.getLong("decision_sequence"), r.getString("action"),
                        Db.uuid(r, "review_task_id"), r.getString("reviewer"), r.getString("reason"),
                        db.json(r.getString("golden_resident_ids")), db.json(r.getString("detail")), List.of(),
                        Db.time(r, "created_at")),
                parameters);
        List<DecisionView> items = new ArrayList<>();
        for (var row : rows) {
            var constraints = db.list("select l.record_key_a, l.record_key_b, l.constraint_type,"
                            + " exists (select 1 from stewardship_link_constraint_effective e where e.decision_id = l.decision_id"
                            + " and e.record_key_a = l.record_key_a and e.record_key_b = l.record_key_b) as effective"
                            + " from stewardship_link_constraint l where l.decision_id = ?"
                            + " order by l.record_key_a, l.record_key_b",
                    r -> new ConstraintView(r.getString(1), r.getString(2), r.getString(3), r.getBoolean(4)),
                    row.decisionId());
            items.add(new DecisionView(row.decisionId(), row.sequence(), row.action(), row.reviewTaskId(),
                    row.reviewer(), row.reason(), row.goldenResidentIds(), row.detail(), constraints, row.createdAt()));
        }
        return Page.of(items, query, total);
    }

    private ReviewTaskSummary task(ResultSet r) throws SQLException {
        UUID goldenA = Db.uuid(r, "golden_a");
        UUID goldenB = Db.uuid(r, "golden_b");
        UUID decision = Db.uuid(r, "decision_id");
        JsonNode a = db.json(r.getString("payload_a"));
        JsonNode b = db.json(r.getString("payload_b"));
        return new ReviewTaskSummary(Db.uuid(r, "review_task_id"), decision == null ? "OPEN" : "DECIDED",
                r.getString("task_type"), r.getString("record_key_a"), r.getString("record_key_b"),
                Db.uuid(r, "current_a"), Db.uuid(r, "current_b"), a == null ? null : a.path("name").asText(null),
                b == null ? null : b.path("name").asText(null), r.getString("state_a"), r.getString("state_b"),
                goldenA, goldenB, goldenA != null && goldenA.equals(goldenB), Db.uuid(r, "matching_run_id"),
                Db.decimal(r, "match_probability"), Db.decimal(r, "match_weight"), Db.time(r, "created_at"),
                decision, r.getString("action"), r.getString("reviewer"), Db.time(r, "decided_at"));
    }
}
