package gov.uddp.core.matching;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.stewardship.StewardshipStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Dry run of a candidate matching profile against the last completed FULL run (Matching Studio). The candidate is
 * scored by the Python module, which only reads; everything else is computed here from SELECTs on a read-only
 * connection, so a preview never writes. Stewardship decisions always override a model: a CANNOT_LINK pair is never
 * linked or queued for review and no cluster is merged across one, a MUST_LINK pair is always linked. The same rules
 * are applied to the baseline, so the difference shown is the model's alone.
 */
@Service
public class MatchingImpactService {
    private static final int SAMPLE_SIZE = 20;

    public record MatchingChangedPair(
            UUID recordA,
            UUID recordB,
            String nameA,
            String nameB,
            String baselineDecision,
            String candidateDecision,
            Double baselineProbability,
            Double candidateProbability) {
    }

    public record MatchingImpact(
            UUID baselineRunId,
            String baselineModel,
            UUID profileId,
            double autoMatch,
            double possibleMatch,
            int records,
            int baselineAutoPairs,
            int candidateAutoPairs,
            int baselineReviewQueue,
            int candidateReviewQueue,
            int newMerges,
            int splits,
            int goldenIdsAffected,
            int constraintsApplied,
            int changedPairCount,
            List<MatchingChangedPair> changedPairs) {
    }

    private final PostgresConnectionFactory connections;
    private final PythonModules.Runner python;
    private final StewardshipStore constraints;

    @org.springframework.beans.factory.annotation.Autowired
    public MatchingImpactService(
            PostgresConnectionFactory connections,
            PythonModules modules,
            StewardshipStore constraints) {
        this(connections, modules::run, constraints);
    }

    public MatchingImpactService(
            PostgresConnectionFactory connections,
            PythonModules.Runner python,
            StewardshipStore constraints) {
        this.connections = connections;
        this.python = python;
        this.constraints = constraints;
    }

    /** {@code autoMatch}/{@code possibleMatch} override the profile's thresholds for a what-if; null keeps them. */
    public MatchingImpact preview(
            UUID profileId,
            UUID baselineRunId,
            Double autoMatch,
            Double possibleMatch) {

        JsonNode candidate = python.run("candidate", List.of("--profile-id", profileId.toString()));

        double auto = autoMatch != null
                ? autoMatch
                : candidate.path("autoMatch").asDouble(0.95);

        double possible = possibleMatch != null
                ? possibleMatch
                : candidate.path("possibleMatch").asDouble(0.7);

        if (!(0 <= possible && possible <= auto && auto <= 1)) {
            throw new IllegalArgumentException(
                    "Thresholds must satisfy 0 <= possibleMatch <= autoMatch <= 1");
        }

        try (Connection c = connections.open()) {
            c.setReadOnly(true);

            UUID run = baselineRunId != null
                    ? baselineRunId
                    : latestFullRun(c);

            String model = text(
                    c,
                    "select model_version from matching_run where run_id = ?",
                    run);

            Map<UUID, String> keys = new HashMap<>();

            try (var s = c.prepareStatement(
                    "select canonical_resident_id, source_record_key "
                            + "from canonical_resident_current");
                 var r = s.executeQuery()) {

                while (r.next()) {
                    keys.put(
                            r.getObject(1, UUID.class),
                            r.getString(2));
                }
            }

            /*
             * StewardshipStore exposes link keys in canonical lexical
             * source-record-key order.
             *
             * keyA|keyB -> true  = MUST_LINK
             * keyA|keyB -> false = CANNOT_LINK
             */
            Map<String, Boolean> overrides = new HashMap<>();

            for (var link : constraints.effective(c)) {
                overrides.put(
                        sourcePairKey(link.keyA(), link.keyB()),
                        link.mustLink());
            }

            /*
             * Baseline and candidate maps must use exactly the same definition
             * of an unordered resident pair.
             *
             * Pair canonicalizes UUID ordering in its constructor, so pairs
             * coming from PostgreSQL and pairs coming from Python cannot
             * accidentally become A|B and B|A respectively.
             */
            Map<String, Pair> baseline = new TreeMap<>();

            try (var s = c.prepareStatement(
                    "select canonical_record_a, canonical_record_b, "
                            + "match_probability, decision "
                            + "from resident_match_pair where run_id = ?")) {

                s.setObject(1, run);

                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        Pair p = new Pair(
                                r.getObject(1, UUID.class),
                                r.getObject(2, UUID.class));

                        p.probability = r.getDouble(3);
                        p.decision = r.getString(4);

                        baseline.put(p.key(), p);
                    }
                }
            }

            Map<String, Pair> scored = new TreeMap<>();

            for (JsonNode node : candidate.path("pairs")) {
                UUID a = UUID.fromString(node.get(0).asText());
                UUID b = UUID.fromString(node.get(1).asText());

                /*
                 * Pair performs the normalization itself. Do not duplicate
                 * UUID ordering logic at call sites.
                 */
                Pair p = new Pair(a, b);

                p.probability = node.get(2).asDouble();

                p.decision = p.probability >= auto
                        ? "AUTO_MATCH"
                        : p.probability >= possible
                                ? "POSSIBLE_MATCH"
                                : "NO_MATCH";

                scored.put(p.key(), p);
            }

            Set<UUID> nodes = new HashSet<>(keys.keySet());

            Map<UUID, UUID> baselineClusters =
                    cluster(nodes, baseline, keys, overrides);

            Map<UUID, UUID> candidateClusters =
                    cluster(nodes, scored, keys, overrides);

            int constraintsApplied = overrides.size();

            // Decisions after stewardship overrides, per pair of either side.
            Set<String> pairKeys = new TreeSet<>(baseline.keySet());
            pairKeys.addAll(scored.keySet());

            int baselineAuto = 0;
            int candidateAuto = 0;
            int baselineQueue = 0;
            int candidateQueue = 0;

            List<MatchingChangedPair> changed = new ArrayList<>();
            List<Pair[]> changedPairs = new ArrayList<>();

            for (String key : pairKeys) {
                Pair before = baseline.get(key);
                Pair after = scored.get(key);

                Pair any = before != null ? before : after;

                String d0 = effective(
                        before,
                        any,
                        keys,
                        overrides);

                String d1 = effective(
                        after,
                        any,
                        keys,
                        overrides);

                baselineAuto += d0.equals("AUTO_MATCH") ? 1 : 0;
                candidateAuto += d1.equals("AUTO_MATCH") ? 1 : 0;

                baselineQueue += d0.equals("POSSIBLE_MATCH") ? 1 : 0;
                candidateQueue += d1.equals("POSSIBLE_MATCH") ? 1 : 0;

                if (!d0.equals(d1)) {
                    changedPairs.add(
                            new Pair[] {before, after, any});
                }
            }

            changedPairs.sort(
                    Comparator.comparingDouble(
                            (Pair[] p) ->
                                    -Math.abs(prob(p[1]) - prob(p[0]))));

            Set<UUID> affected = new HashSet<>();

            int merges = tally(
                    candidateClusters,
                    baselineClusters,
                    affected);

            int splits = tally(
                    baselineClusters,
                    candidateClusters,
                    affected);

            int goldens = goldensOf(c, affected);

            Set<UUID> sampleIds = new HashSet<>();

            changedPairs.stream()
                    .limit(SAMPLE_SIZE)
                    .forEach(p -> {
                        sampleIds.add(p[2].a);
                        sampleIds.add(p[2].b);
                    });

            Map<UUID, String> names = names(c, sampleIds);

            for (Pair[] p : changedPairs.stream()
                    .limit(SAMPLE_SIZE)
                    .toList()) {

                changed.add(
                        new MatchingChangedPair(
                                p[2].a,
                                p[2].b,
                                names.get(p[2].a),
                                names.get(p[2].b),
                                effective(
                                        p[0],
                                        p[2],
                                        keys,
                                        overrides),
                                effective(
                                        p[1],
                                        p[2],
                                        keys,
                                        overrides),
                                p[0] == null
                                        ? null
                                        : p[0].probability,
                                p[1] == null
                                        ? null
                                        : p[1].probability));
            }

            return new MatchingImpact(
                    run,
                    model,
                    profileId,
                    auto,
                    possible,
                    candidate.path("records").asInt(),
                    baselineAuto,
                    candidateAuto,
                    baselineQueue,
                    candidateQueue,
                    merges,
                    splits,
                    goldens,
                    constraintsApplied,
                    changedPairs.size(),
                    changed);

        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Cannot compute the impact preview",
                    e);

        } catch (RuntimeException e) {
            throw e;

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot compute the impact preview",
                    e);
        }
    }

    // ---- model

    /**
     * Represents an unordered pair of canonical resident UUIDs.
     *
     * All pairs are normalized here so every caller uses exactly the same
     * representation. This prevents the same logical pair from appearing as
     * both A|B and B|A when UUID ordering differs between external systems.
     */
    private static final class Pair {
        final UUID a;
        final UUID b;

        double probability;
        String decision = "NO_MATCH";

        Pair(UUID a, UUID b) {
            if (a == null || b == null) {
                throw new IllegalArgumentException(
                        "Matching pair UUIDs must not be null");
            }

            if (compareUuid(a, b) <= 0) {
                this.a = a;
                this.b = b;
            } else {
                this.a = b;
                this.b = a;
            }
        }

        String key() {
            return pairKey(a, b);
        }
    }

    /**
     * Stable unsigned byte-order comparison for UUIDs.
     *
     * UUID.compareTo() compares the UUID's two internal long values as signed
     * numbers. PostgreSQL orders UUID values by their UUID byte representation.
     * Using unsigned comparison here gives us a deterministic canonical order
     * compatible with the textual/byte representation of UUIDs.
     */
    private static int compareUuid(UUID left, UUID right) {
        int most = Long.compareUnsigned(
                left.getMostSignificantBits(),
                right.getMostSignificantBits());

        if (most != 0) {
            return most;
        }

        return Long.compareUnsigned(
                left.getLeastSignificantBits(),
                right.getLeastSignificantBits());
    }

    /**
     * Canonical key for an unordered UUID pair.
     */
    private static String pairKey(UUID a, UUID b) {
        if (compareUuid(a, b) <= 0) {
            return a + "|" + b;
        }

        return b + "|" + a;
    }

    /**
     * Canonical key for an unordered source-record-key pair.
     */
    private static String sourcePairKey(String a, String b) {
        return a.compareTo(b) <= 0
                ? a + "|" + b
                : b + "|" + a;
    }

    private static double prob(Pair p) {
        return p == null
                ? 0.0
                : p.probability;
    }

    /**
     * The pair's decision once a steward's link decision is applied;
     * a missing pair is NO_MATCH.
     */
    private static String effective(
            Pair pair,
            Pair identity,
            Map<UUID, String> keys,
            Map<String, Boolean> overrides) {

        Boolean must = override(
                identity,
                keys,
                overrides);

        if (must != null) {
            return must
                    ? "AUTO_MATCH"
                    : "NO_MATCH";
        }

        return pair == null
                ? "NO_MATCH"
                : pair.decision;
    }

    private static Boolean override(
            Pair pair,
            Map<UUID, String> keys,
            Map<String, Boolean> overrides) {

        if (pair == null) {
            return null;
        }

        String ka = keys.get(pair.a);
        String kb = keys.get(pair.b);

        if (ka == null || kb == null) {
            return null;
        }

        return overrides.get(
                sourcePairKey(ka, kb));
    }

    /**
     * Union-find over AUTO_MATCH pairs plus MUST_LINK pairs; a union is skipped
     * when it would put two records with a CANNOT_LINK decision in one cluster.
     * Returns canonical id -> cluster representative.
     */
    private static Map<UUID, UUID> cluster(
            Set<UUID> nodes,
            Map<String, Pair> pairs,
            Map<UUID, String> keys,
            Map<String, Boolean> overrides) {

        Map<UUID, UUID> parent = new HashMap<>();
        Map<String, UUID> byKey = new HashMap<>();

        for (UUID n : nodes) {
            parent.put(n, n);

            String sourceKey = keys.get(n);

            if (sourceKey != null) {
                byKey.put(sourceKey, n);
            }
        }

        List<UUID[]> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Pair p : pairs.values()) {
            Boolean must = override(
                    p,
                    keys,
                    overrides);

            if (must == null
                    ? p.decision.equals("AUTO_MATCH")
                    : must) {

                edges.add(
                        new UUID[] {p.a, p.b});

                seen.add(p.key());
            }
        }

        overrides.forEach((k, must) -> {
            if (must) {
                String[] ends = k.split("\\|", 2);

                UUID a = byKey.get(ends[0]);
                UUID b = byKey.get(ends[1]);

                if (a != null
                        && b != null
                        && !seen.contains(pairKey(a, b))) {

                    edges.add(
                            new UUID[] {a, b});
                }
            }
        });

        List<UUID[]> cannot = new ArrayList<>();

        overrides.forEach((k, must) -> {
            if (!must) {
                String[] ends = k.split("\\|", 2);

                if (byKey.containsKey(ends[0])
                        && byKey.containsKey(ends[1])) {

                    cannot.add(
                            new UUID[] {
                                    byKey.get(ends[0]),
                                    byKey.get(ends[1])
                            });
                }
            }
        });

        for (UUID[] e : edges) {
            UUID ra = find(parent, e[0]);
            UUID rb = find(parent, e[1]);

            if (ra.equals(rb)
                    || separated(parent, cannot, ra, rb)) {
                continue;
            }

            parent.put(ra, rb);
        }

        Map<UUID, UUID> out = new HashMap<>();

        for (UUID n : nodes) {
            out.put(
                    n,
                    find(parent, n));
        }

        return out;
    }

    private static boolean separated(
            Map<UUID, UUID> parent,
            List<UUID[]> cannot,
            UUID ra,
            UUID rb) {

        for (UUID[] pair : cannot) {
            UUID x = find(parent, pair[0]);
            UUID y = find(parent, pair[1]);

            if ((x.equals(ra) && y.equals(rb))
                    || (x.equals(rb) && y.equals(ra))) {
                return true;
            }
        }

        return false;
    }

    private static UUID find(
            Map<UUID, UUID> parent,
            UUID x) {

        while (!parent.get(x).equals(x)) {
            parent.put(
                    x,
                    parent.get(parent.get(x)));

            x = parent.get(x);
        }

        return x;
    }

    /**
     * For every cluster of {@code outer}, the number of {@code inner} clusters
     * it spans minus one; collects members.
     */
    private static int tally(
            Map<UUID, UUID> outer,
            Map<UUID, UUID> inner,
            Set<UUID> affected) {

        Map<UUID, Set<UUID>> spans = new LinkedHashMap<>();
        Map<UUID, List<UUID>> members = new HashMap<>();

        outer.forEach((id, cluster) -> {
            spans.computeIfAbsent(
                    cluster,
                    k -> new HashSet<>())
                    .add(inner.get(id));

            members.computeIfAbsent(
                    cluster,
                    k -> new ArrayList<>())
                    .add(id);
        });

        int total = 0;

        for (var entry : spans.entrySet()) {
            if (entry.getValue().size() > 1) {
                total += entry.getValue().size() - 1;

                affected.addAll(
                        members.get(entry.getKey()));
            }
        }

        return total;
    }

    // ---- reads

    private static UUID latestFullRun(Connection c)
            throws SQLException {

        try (var s = c.prepareStatement(
                "select run_id from matching_run "
                        + "where status = 'COMPLETED' "
                        + "and mode = 'FULL' "
                        + "order by started_at desc limit 1");
             var r = s.executeQuery()) {

            if (!r.next()) {
                throw new IllegalArgumentException(
                        "No completed FULL matching run to compare with");
            }

            return r.getObject(
                    1,
                    UUID.class);
        }
    }

    private static String text(
            Connection c,
            String sql,
            UUID id)
            throws SQLException {

        try (var s = c.prepareStatement(sql)) {
            s.setObject(1, id);

            try (var r = s.executeQuery()) {
                return r.next()
                        ? r.getString(1)
                        : null;
            }
        }
    }

    private static int goldensOf(
            Connection c,
            Set<UUID> canonicalIds)
            throws SQLException {

        if (canonicalIds.isEmpty()) {
            return 0;
        }

        try (var s = c.prepareStatement(
                "select count(distinct golden_resident_id) "
                        + "from golden_resident_membership "
                        + "where effective_to is null "
                        + "and canonical_resident_id = any(?)")) {

            s.setArray(
                    1,
                    c.createArrayOf(
                            "uuid",
                            canonicalIds.toArray()));

            try (var r = s.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }

    private static Map<UUID, String> names(
            Connection c,
            Set<UUID> ids)
            throws SQLException {

        Map<UUID, String> out = new HashMap<>();

        if (ids.isEmpty()) {
            return out;
        }

        try (var s = c.prepareStatement(
                "select canonical_resident_id, "
                        + "canonical_payload ->> 'name' "
                        + "from canonical_resident "
                        + "where canonical_resident_id = any(?)")) {

            s.setArray(
                    1,
                    c.createArrayOf(
                            "uuid",
                            ids.toArray()));

            try (var r = s.executeQuery()) {
                while (r.next()) {
                    out.put(
                            r.getObject(1, UUID.class),
                            r.getString(2));
                }
            }
        }

        return out;
    }
}