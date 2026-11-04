package gov.uddp.core.read;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.security.StateScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Matching runs, their clusters and the persisted pair decisions with evidence. Read-only (rule 3). */
@Service
public class MatchingReadService {
    public static final Set<String> DECISIONS = Set.of("AUTO_MATCH", "POSSIBLE_MATCH", "NO_MATCH");

    public record PairCounts(long autoMatch, long possibleMatch, long noMatch) {
    }

    public record MatchingRunSummary(UUID runId, String modelVersion, String status, String mode,
                                     OffsetDateTime startedAt, OffsetDateTime completedAt, Long durationMs,
                                     int recordsConsidered, Double autoMatchThreshold, Double possibleMatchThreshold,
                                     PairCounts pairs, long clusters, long multiMemberClusters) {
    }

    /** A canonical record as it appears in matching: identity, state and the matched fields. */
    public record CanonicalBrief(UUID canonicalResidentId, String stateCode, String sourceSystem, String name,
                                 String dateOfBirth, String gender, String mobile, String districtLGD,
                                 boolean current) {
    }

    public record ClusterView(String clusterId, List<CanonicalBrief> members) {
    }

    public record GoldenBuildRef(UUID goldenBuildId, String mode, String survivorshipPolicyVersion,
                                 OffsetDateTime startedAt, OffsetDateTime completedAt) {
    }

    public record MatchingRunDetail(MatchingRunSummary run, List<ClusterView> clusters, List<GoldenBuildRef> goldenBuilds) {
    }

    public record MatchPairSummary(UUID runId, UUID canonicalRecordA, UUID canonicalRecordB, String nameA, String nameB,
                                   String stateCodeA, String stateCodeB, double matchProbability, double matchWeight,
                                   String decision) {
    }

    /**
     * One matched field side by side. {@code outcome} is SAME, DIFFERENT or MISSING (either side empty);
     * {@code basis} is EVIDENCE when the values are the normalised ones Splink compared, CANONICAL when the run stored
     * no per-field evidence and the canonical values are shown instead.
     */
    public record FieldComparison(String field, String valueA, String valueB, String outcome, String basis) {
    }

    public record MatchPairDetail(MatchPairSummary pair, CanonicalBrief recordA, CanonicalBrief recordB,
                                  List<FieldComparison> comparisons, Double autoMatchThreshold,
                                  Double possibleMatchThreshold, JsonNode evidence) {
    }

    private static final Map<String, String> RUN_SORT = Map.of("startedAt", "r.started_at", "status", "r.status",
            "recordsConsidered", "r.records_considered");
    private static final Map<String, String> PAIR_SORT = Map.of("matchProbability", "p.match_probability",
            "matchWeight", "p.match_weight", "decision", "p.decision");
    /** Evidence keys written by matching/run.py and the canonical field each one compares. */
    private static final Map<String, String> EVIDENCE_FIELDS = linked(
            "name", "name", "dateOfBirth", "dateOfBirth", "gender", "gender", "mobile", "mobile",
            "districtLGD", "address.districtLGD");
    private static final String RUN_COLUMNS = "select r.*,"
            + " (select count(*) from resident_match_pair p where p.run_id = r.run_id and p.decision = 'AUTO_MATCH') as auto_match,"
            + " (select count(*) from resident_match_pair p where p.run_id = r.run_id and p.decision = 'POSSIBLE_MATCH') as possible_match,"
            + " (select count(*) from resident_match_pair p where p.run_id = r.run_id and p.decision = 'NO_MATCH') as no_match,"
            + " (select count(distinct cluster_id) from resident_match_cluster_member m where m.run_id = r.run_id) as clusters,"
            + " (select count(*) from (select cluster_id from resident_match_cluster_member m where m.run_id = r.run_id"
            + " group by cluster_id having count(*) > 1) x) as multi_member_clusters"
            + " from matching_run r";
    private static final String BRIEF = "select c.canonical_resident_id, c.state_code, c.source_system,"
            + " c.canonical_payload::text as payload_text, not exists (select 1 from canonical_resident_version s"
            + " where s.supersedes_canonical_id = c.canonical_resident_id) as is_current from canonical_resident c";

    private final Db db;

    public MatchingReadService(Db db) {
        this.db = db;
    }

    public Page<MatchingRunSummary> runs(String status, String mode, Integer page, Integer size, String sort) {
        var query = PageQuery.of(page, size, sort, RUN_SORT, "startedAt,desc", "r.run_id");
        String s = upper(status);
        String m = upper(mode);
        String where = " where (cast(? as text) is null or r.status = cast(? as text))"
                + " and (cast(? as text) is null or r.mode = cast(? as text))";
        long total = db.count("select count(*) from matching_run r" + where, s, s, m, m);
        var items = db.list(RUN_COLUMNS + where + query.sql(), MatchingReadService::run, s, s, m, m);
        return Page.of(items.stream().map(this::scoped).toList(), query, total);
    }

    public MatchingRunDetail run(UUID runId) {
        var run = scoped(db.one(RUN_COLUMNS + " where r.run_id = ?", MatchingReadService::run, runId)
                .orElseThrow(() -> new ReadNotFoundException("Matching run does not exist: " + runId)));
        StateScope scope = CurrentUser.scope();
        Map<String, List<CanonicalBrief>> clusters = new LinkedHashMap<>();
        db.list(BRIEF.replace(" from canonical_resident c", ", m.cluster_id from canonical_resident c") + " join resident_match_cluster_member m on m.canonical_record_id = c.canonical_resident_id"
                        + " where m.run_id = ? and m.cluster_id in (select cluster_id from resident_match_cluster_member"
                        + " where run_id = ? group by cluster_id having count(*) > 1) order by m.cluster_id, c.canonical_resident_id",
                r -> {
                    clusters.computeIfAbsent(r.getString("cluster_id"), x -> new ArrayList<>()).add(brief(r));
                    return null;
                }, runId, runId);
        var builds = db.list("select golden_build_id, mode, survivorship_policy_version, started_at, completed_at"
                        + " from golden_build where matching_run_id = ? order by started_at",
                r -> new GoldenBuildRef(Db.uuid(r, "golden_build_id"), r.getString("mode"),
                        r.getString("survivorship_policy_version"), Db.time(r, "started_at"), Db.time(r, "completed_at")),
                runId);
        // A scoped user sees only members from states in scope, and only clusters that still have two of them.
        var views = clusters.entrySet().stream()
                .map(e -> new ClusterView(e.getKey(), e.getValue().stream()
                        .filter(member -> scope.allows(member.stateCode()))
                        .toList()))
                .filter(cluster -> cluster.members().size() > 1)
                .toList();
        return new MatchingRunDetail(run, views, builds);
    }

    public Page<MatchPairSummary> pairs(UUID runId, String decision, UUID canonicalId, Integer page, Integer size,
                                        String sort) {
        requireRun(runId);
        var query = PageQuery.of(page, size, sort, PAIR_SORT, "matchProbability,desc",
                "p.canonical_record_a, p.canonical_record_b");
        String d = upper(decision);
        if (d != null && !DECISIONS.contains(d)) {
            throw new IllegalArgumentException("decision must be one of AUTO_MATCH, POSSIBLE_MATCH, NO_MATCH");
        }
        String from = " from resident_match_pair p"
                + " join canonical_resident ca on ca.canonical_resident_id = p.canonical_record_a"
                + " join canonical_resident cb on cb.canonical_resident_id = p.canonical_record_b"
                + " where p.run_id = ? and (cast(? as text) is null or p.decision = cast(? as text))"
                + " and (cast(? as uuid) is null or p.canonical_record_a = cast(? as uuid)"
                + " or p.canonical_record_b = cast(? as uuid))"
                // Both records of a pair must be in the user's scope (ADR-0019).
                + Db.inScope("ca.state_code") + Db.inScope("cb.state_code");
        Object[] scope = Db.scopeParameters();
        Object[] parameters = {runId, d, d, canonicalId, canonicalId, canonicalId, scope[0], scope[1], scope[0],
            scope[1]};
        long total = db.count("select count(*)" + from, parameters);
        var items = db.list("select p.*, ca.canonical_payload->>'name' as name_a, cb.canonical_payload->>'name' as name_b,"
                + " ca.state_code as state_a, cb.state_code as state_b" + from + query.sql(), MatchingReadService::pair,
                parameters);
        return Page.of(items, query, total);
    }

    /** The pair in either order of its two canonical IDs. */
    public MatchPairDetail pair(UUID runId, UUID first, UUID second) {
        var run = requireRun(runId);
        var row = db.one("select p.*, p.evidence::text as evidence_text, ca.canonical_payload->>'name' as name_a,"
                        + " cb.canonical_payload->>'name' as name_b, ca.state_code as state_a, cb.state_code as state_b"
                        + " from resident_match_pair p"
                        + " join canonical_resident ca on ca.canonical_resident_id = p.canonical_record_a"
                        + " join canonical_resident cb on cb.canonical_resident_id = p.canonical_record_b"
                        + " where p.run_id = ? and ((p.canonical_record_a = ? and p.canonical_record_b = ?)"
                        + " or (p.canonical_record_a = ? and p.canonical_record_b = ?))",
                        r -> Map.entry(pair(r), r.getString("evidence_text")), runId, first, second, second, first)
                .orElseThrow(() -> new ReadNotFoundException("Matching run " + runId + " has no pair " + first + " / " + second));
        var summary = row.getKey();
        StateScope scope = CurrentUser.scope();
        if (!scope.allows(summary.stateCodeA()) || !scope.allows(summary.stateCodeB())) {
            throw new ReadNotFoundException("Matching run " + runId + " has no pair " + first + " / " + second);
        }
        JsonNode evidence = db.json(row.getValue());
        var recordA = brief(summary.canonicalRecordA());
        var recordB = brief(summary.canonicalRecordB());
        return new MatchPairDetail(summary, recordA, recordB, comparisons(evidence, summary), run.autoMatchThreshold(),
                run.possibleMatchThreshold(), evidence);
    }

    private List<FieldComparison> comparisons(JsonNode evidence, MatchPairSummary pair) {
        boolean fromEvidence = evidence != null && EVIDENCE_FIELDS.keySet().stream()
                .anyMatch(key -> evidence.path(key).isArray());
        List<FieldComparison> out = new ArrayList<>();
        if (fromEvidence) {
            EVIDENCE_FIELDS.keySet().forEach(key -> {
                JsonNode values = evidence.path(key);
                out.add(compare(key, value(values.path(0)), value(values.path(1)), "EVIDENCE"));
            });
            return out;
        }
        JsonNode a = payload(pair.canonicalRecordA());
        JsonNode b = payload(pair.canonicalRecordB());
        EVIDENCE_FIELDS.forEach((key, path) -> out.add(compare(key, CanonicalReadService.text(a, path),
                CanonicalReadService.text(b, path), "CANONICAL")));
        return out;
    }

    private static FieldComparison compare(String field, String a, String b, String basis) {
        String outcome = blank(a) || blank(b) ? "MISSING" : Objects.equals(a, b) ? "SAME" : "DIFFERENT";
        return new FieldComparison(field, a, b, outcome, basis);
    }

    private JsonNode payload(UUID id) {
        return db.one("select canonical_payload::text from canonical_resident where canonical_resident_id = ?",
                r -> db.json(r.getString(1)), id).orElse(null);
    }

    private CanonicalBrief brief(UUID id) {
        return db.one(BRIEF + " where c.canonical_resident_id = ?", this::brief, id)
                .orElseThrow(() -> new ReadNotFoundException("Canonical Resident does not exist: " + id));
    }

    private CanonicalBrief brief(ResultSet r) throws SQLException {
        JsonNode payload = db.json(r.getString("payload_text"));
        return new CanonicalBrief(Db.uuid(r, "canonical_resident_id"), r.getString("state_code"),
                r.getString("source_system"), CanonicalReadService.text(payload, "name"),
                CanonicalReadService.text(payload, "dateOfBirth"), CanonicalReadService.text(payload, "gender"),
                CanonicalReadService.text(payload, "mobile"), CanonicalReadService.text(payload, "address.districtLGD"),
                r.getBoolean("is_current"));
    }

    /**
     * For a user who does not see every state, the run's counts over what they may see: pairs with both records in
     * scope, clusters and records from states in scope (ADR-0019).
     */
    private MatchingRunSummary scoped(MatchingRunSummary run) {
        StateScope scope = CurrentUser.scope();
        if (scope.all()) {
            return run;
        }
        Object[] codes = Db.scopeParameters();
        long[] decisions = new long[3];
        db.list("select p.decision, count(*) from resident_match_pair p"
                        + " join canonical_resident ca on ca.canonical_resident_id = p.canonical_record_a"
                        + " join canonical_resident cb on cb.canonical_resident_id = p.canonical_record_b"
                        + " where p.run_id = ?" + Db.inScope("ca.state_code") + Db.inScope("cb.state_code")
                        + " group by p.decision",
                r -> {
                    int index = List.of("AUTO_MATCH", "POSSIBLE_MATCH", "NO_MATCH").indexOf(r.getString(1));
                    if (index >= 0) {
                        decisions[index] = r.getLong(2);
                    }
                    return null;
                }, run.runId(), codes[0], codes[1], codes[0], codes[1]);
        long[] clusters = db.one("select count(*), count(distinct m.cluster_id),"
                        + " (select count(*) from (select x.cluster_id from resident_match_cluster_member x"
                        + " join canonical_resident xc on xc.canonical_resident_id = x.canonical_record_id"
                        + " where x.run_id = ?" + Db.inScope("xc.state_code")
                        + " group by x.cluster_id having count(*) > 1) multi)"
                        + " from resident_match_cluster_member m"
                        + " join canonical_resident c on c.canonical_resident_id = m.canonical_record_id"
                        + " where m.run_id = ?" + Db.inScope("c.state_code"),
                r -> new long[] {r.getLong(1), r.getLong(2), r.getLong(3)},
                run.runId(), codes[0], codes[1], run.runId(), codes[0], codes[1]).orElse(new long[3]);
        return new MatchingRunSummary(run.runId(), run.modelVersion(), run.status(), run.mode(), run.startedAt(),
                run.completedAt(), run.durationMs(), (int) clusters[0], run.autoMatchThreshold(),
                run.possibleMatchThreshold(), new PairCounts(decisions[0], decisions[1], decisions[2]), clusters[1],
                clusters[2]);
    }

    private MatchingRunSummary requireRun(UUID runId) {
        return db.one(RUN_COLUMNS + " where r.run_id = ?", MatchingReadService::run, runId)
                .orElseThrow(() -> new ReadNotFoundException("Matching run does not exist: " + runId));
    }

    private static MatchingRunSummary run(ResultSet r) throws SQLException {
        var started = Db.time(r, "started_at");
        var completed = Db.time(r, "completed_at");
        return new MatchingRunSummary(Db.uuid(r, "run_id"), r.getString("model_version"), r.getString("status"),
                r.getString("mode"), started, completed, Db.millis(started, completed), r.getInt("records_considered"),
                Db.decimal(r, "threshold"), Db.decimal(r, "possible_match_threshold"),
                new PairCounts(r.getLong("auto_match"), r.getLong("possible_match"), r.getLong("no_match")),
                r.getLong("clusters"), r.getLong("multi_member_clusters"));
    }

    private static MatchPairSummary pair(ResultSet r) throws SQLException {
        return new MatchPairSummary(Db.uuid(r, "run_id"), Db.uuid(r, "canonical_record_a"),
                Db.uuid(r, "canonical_record_b"), r.getString("name_a"), r.getString("name_b"), r.getString("state_a"),
                r.getString("state_b"), r.getDouble("match_probability"), r.getDouble("match_weight"),
                r.getString("decision"));
    }

    private static String value(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    private static Map<String, String> linked(String... keyValues) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            out.put(keyValues[i], keyValues[i + 1]);
        }
        return out;
    }
}
