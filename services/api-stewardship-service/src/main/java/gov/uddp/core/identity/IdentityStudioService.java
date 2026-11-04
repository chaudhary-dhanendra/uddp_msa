package gov.uddp.core.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The Studio Identity step (Track E5): identifier coverage per source, a policy recommended from that coverage, and a
 * preview of a policy's impact computed by the real {@link IdentityPolicyEvaluator} over the latest canonical records
 * and the latest completed matching run. Reads only; submitting goes through {@link IdentityPolicyService}.
 */
@Component
public class IdentityStudioService {
    /** A type is recommended for deterministic matching or family anchoring from this share of records. */
    static final double RECOMMEND_FROM_PERCENT = 50.0;
    /** The stricter auto-match probability recommended for pairs without identifiers. */
    static final double NO_IDENTIFIER_AUTO_MATCH = 0.98;

    /** One identifier type in one source: shares of rows present, invalid (rejected at mapping) and duplicated. */
    public record IdentifierCoverage(String type, long present, long invalid, long duplicated, double presentPercent,
                               double invalidPercent, double duplicatedPercent) {
    }

    /** One source ({@code stateCode}/{@code sourceSystem}): its canonical records and identifier coverage by type. */
    public record IdentitySourceCoverage(String stateCode, String sourceSystem, long records, List<IdentifierCoverage> types) {
    }

    public record IdentityCoverage(List<IdentitySourceCoverage> sources, Map<String, Double> overallPresentPercent) {
    }

    public record IdentityRecommendation(IdentityPolicy policy, List<String> reasons, List<String> warnings) {
    }

    /**
     * A policy's impact: deterministic auto-merges (and how many of them matching had not already auto-matched),
     * DOB/gender conflicts sent to review, never-merge pairs (and how many current auto-matches they block),
     * auto-matches the stricter rules send to review, and records only probabilistic matching can place.
     */
    public record IdentityPreview(int records, int autoMerges, int newAutoMerges, int conflicts, int neverMerge,
                          int blockedAutoMatches, int probabilisticReviews, int probabilisticRemainder,
                          int evidencePairs, Map<String, Integer> coverage, UUID matchingRunId, List<String> warnings) {
    }

    private final PostgresConnectionFactory connections;
    private final IdentifierTypeRegistry registry;
    private final ObjectMapper json = new ObjectMapper();

    public IdentityStudioService(PostgresConnectionFactory connections, IdentifierTypeRegistry registry) {
        this.connections = connections;
        this.registry = registry;
    }

    public IdentityCoverage coverage() {
        Map<String, Long> records = new TreeMap<>();
        Map<String, Map<String, long[]>> counts = new TreeMap<>();
        try (var c = connections.open()) {
            try (var s = c.prepareStatement("select state_code, source_system, count(*) from canonical_resident_current"
                    + " group by 1, 2"); var r = s.executeQuery()) {
                while (r.next()) {
                    records.put(r.getString(1) + "|" + r.getString(2), r.getLong(3));
                }
            }
            // present and duplicated (a value held by more than one record of the same source)
            try (var s = c.prepareStatement("select state_code, source_system, type, count(*),"
                    + " count(*) filter (where holders > 1) from (select c.state_code, c.source_system, t.key as type,"
                    + " count(*) over (partition by c.state_code, c.source_system, t.key, t.value->>'value') as holders"
                    + " from canonical_resident_current c,"
                    + " jsonb_each(coalesce(c.canonical_payload->'extensions'->'uddp.identifiers', '{}'::jsonb)) t) x"
                    + " group by 1, 2, 3"); var r = s.executeQuery()) {
                while (r.next()) {
                    long[] cell = cell(counts, r.getString(1) + "|" + r.getString(2), r.getString(3));
                    cell[0] = r.getLong(4);
                    cell[2] = r.getLong(5);
                }
            }
            // invalid: distinct raw records whose mapping rejected a value of the type (the message names the type)
            for (IdentifierType type : registry.types()) {
                try (var s = c.prepareStatement("select r.state_code, r.source_system, count(distinct e.raw_record_id)"
                        + " from canonical_processing_error e join raw_record r on r.raw_record_id = e.raw_record_id"
                        + " where e.error_message like ? and not exists (select 1 from canonical_resident k"
                        + " where k.raw_record_id = e.raw_record_id) group by 1, 2")) {
                    s.setString(1, "Source field %: " + type.code() + " value %");
                    var r = s.executeQuery();
                    while (r.next()) {
                        cell(counts, r.getString(1) + "|" + r.getString(2), type.code())[1] = r.getLong(3);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot profile identifier coverage", e);
        }
        List<IdentitySourceCoverage> sources = new ArrayList<>();
        Map<String, long[]> overall = new TreeMap<>();
        long allRows = 0;
        java.util.Set<String> keys = new java.util.TreeSet<>(records.keySet());
        keys.addAll(counts.keySet());
        for (String key : keys) {
            String[] parts = key.split("\\|", 2);
            long canonical = records.getOrDefault(key, 0L);
            Map<String, long[]> byType = counts.getOrDefault(key, Map.of());
            long invalidRows = byType.values().stream().mapToLong(cell -> cell[1]).max().orElse(0);
            long rows = canonical + invalidRows;
            allRows += rows;
            List<IdentifierCoverage> types = new ArrayList<>();
            for (IdentifierType type : registry.types()) {
                long[] cell = byType.getOrDefault(type.code(), new long[3]);
                types.add(new IdentifierCoverage(type.code(), cell[0], cell[1], cell[2], percent(cell[0], rows),
                        percent(cell[1], rows), percent(cell[2], cell[0])));
                long[] sum = overall.computeIfAbsent(type.code(), x -> new long[1]);
                sum[0] += cell[0];
            }
            sources.add(new IdentitySourceCoverage(parts[0], parts[1], canonical, types));
        }
        Map<String, Double> overallPercent = new LinkedHashMap<>();
        long total = allRows;
        overall.forEach((type, sum) -> overallPercent.put(type, percent(sum[0], total)));
        return new IdentityCoverage(sources, overallPercent);
    }

    /** A policy recommended from the coverage, with the reasons and a warning when nothing can match deterministically. */
    public IdentityRecommendation recommend() {
        IdentityCoverage coverage = coverage();
        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> deterministic = new ArrayList<>();
        List<String> neverMerge = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        String anchor = null;
        double anchorPercent = 0;
        for (IdentifierType type : registry.types()) {
            double present = coverage.overallPresentPercent().getOrDefault(type.code(), 0.0);
            if (present == 0) {
                continue;
            }
            boolean person = type.subject() == IdentifierType.Subject.PERSON;
            if (person && type.trust() == IdentifierType.Trust.STRONG && type.uses(IdentifierType.Usage.DETERMINISTIC_MATCH)
                    && present >= RECOMMEND_FROM_PERCENT) {
                deterministic.add(type.code());
                reasons.add(type.code() + " is on " + present + "% of records: same value auto-matches");
            }
            if (person && type.unique()) {
                neverMerge.add(type.code());
                reasons.add(type.code() + " is unique per person: different values never merge");
            }
            if (person && type.trust() != IdentifierType.Trust.STRONG && type.uses(IdentifierType.Usage.MATCH_EVIDENCE)) {
                evidence.add(type.code());
                reasons.add(type.code() + " feeds probabilistic matching as evidence");
            }
            if (type.subject() == IdentifierType.Subject.HOUSEHOLD && type.uses(IdentifierType.Usage.FAMILY_ANCHOR)
                    && present >= RECOMMEND_FROM_PERCENT && present > anchorPercent) {
                anchor = type.code();
                anchorPercent = present;
            }
            for (IdentitySourceCoverage source : coverage.sources()) {
                source.types().stream().filter(t -> t.type().equals(type.code())).forEach(t -> {
                    if (type.unique() && t.duplicated() > 0) {
                        warnings.add(source.stateCode() + "/" + source.sourceSystem() + ": " + t.duplicated() + " "
                                + type.code() + " values are held by more than one record");
                    }
                    if (t.invalidPercent() > 5) {
                        warnings.add(source.stateCode() + "/" + source.sourceSystem() + ": " + t.invalidPercent() + "% "
                                + type.code() + " values are invalid");
                    }
                });
            }
        }
        if (anchor != null) {
            reasons.add(anchor + " is on " + anchorPercent + "% of records: households sharing it are one family");
        }
        String base = null;
        if (deterministic.isEmpty()) {
            warnings.add("No deterministic identifier has enough coverage: matching stays probabilistic, with a"
                    + " stricter auto-match threshold for records without identifiers");
        } else {
            String first = deterministic.getFirst();
            base = coverage.sources().stream()
                    .max(java.util.Comparator.comparingDouble((IdentitySourceCoverage s) -> s.types().stream()
                            .filter(t -> t.type().equals(first)).mapToDouble(IdentifierCoverage::presentPercent).sum())
                            .thenComparingLong(IdentitySourceCoverage::records))
                    .map(s -> s.stateCode() + ":" + s.sourceSystem()).orElse(null);
            if (base != null) {
                reasons.add(base + " has the best " + first + " coverage: it is the base source of a first load");
            }
        }
        var policy = new IdentityPolicy(deterministic, neverMerge, evidence, anchor, null, null,
                NO_IDENTIFIER_AUTO_MATCH, base).validate(registry);
        return new IdentityRecommendation(policy, reasons, warnings);
    }

    /** The policy's impact on the current data, by the same evaluator the Golden build uses. Changes nothing. */
    public IdentityPreview preview(IdentityPolicy policy) {
        policy.validate(registry);
        List<IdentityPolicyEvaluator.Subject> subjects = new ArrayList<>();
        Map<UUID, String> keyOf = new HashMap<>();
        List<IdentityPolicyEvaluator.Candidate> candidates = new ArrayList<>();
        UUID run = null;
        boolean firstLoad;
        try (var c = connections.open()) {
            try (var s = c.prepareStatement("select canonical_resident_id, source_record_key, state_code, source_system,"
                    + " canonical_payload::text from canonical_resident_current order by source_record_key");
                    var r = s.executeQuery()) {
                while (r.next()) {
                    keyOf.put(r.getObject(1, UUID.class), r.getString(2));
                    subjects.add(IdentityPolicyEvaluator.Subject.of(r.getString(2), r.getString(3), r.getString(4),
                            json.readTree(r.getString(5))));
                }
            }
            try (var s = c.prepareStatement("select run_id from matching_run where status = 'COMPLETED'"
                    + " order by started_at desc, run_id desc limit 1"); var r = s.executeQuery()) {
                run = r.next() ? r.getObject(1, UUID.class) : null;
            }
            if (run != null) {
                try (var s = c.prepareStatement("select canonical_record_a, canonical_record_b, match_probability, decision"
                        + " from resident_match_pair where run_id = ?")) {
                    s.setObject(1, run);
                    var r = s.executeQuery();
                    while (r.next()) {
                        String a = keyOf.get(r.getObject(1, UUID.class));
                        String b = keyOf.get(r.getObject(2, UUID.class));
                        if (a != null && b != null && !"NO_MATCH".equals(r.getString(4))) {
                            candidates.add(new IdentityPolicyEvaluator.Candidate(a, b, r.getDouble(3),
                                    "AUTO_MATCH".equals(r.getString(4))));
                        }
                    }
                }
            }
            try (var s = c.prepareStatement("select not exists (select 1 from golden_resident)"); var r = s.executeQuery()) {
                firstLoad = r.next() && r.getBoolean(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot preview the identity policy", e);
        }
        var evaluation = IdentityPolicyEvaluator.evaluate(policy, subjects, candidates, firstLoad);
        java.util.Set<String> matchedAuto = new java.util.HashSet<>();
        candidates.stream().filter(IdentityPolicyEvaluator.Candidate::autoMatch).forEach(candidate -> {
            var pair = IdentityPolicyEvaluator.Pair.of(candidate.keyA(), candidate.keyB(), null);
            matchedAuto.add(pair.keyA() + "|" + pair.keyB());
        });
        int newMerges = (int) evaluation.autoMatches().stream()
                .filter(p -> !matchedAuto.contains(p.keyA() + "|" + p.keyB())).count();
        int blocked = (int) evaluation.neverMerge().stream()
                .filter(p -> matchedAuto.contains(p.keyA() + "|" + p.keyB())).count();
        List<String> warnings = new ArrayList<>();
        if (policy.deterministicTypes().isEmpty()) {
            warnings.add("The policy has no deterministic identifier: every merge is probabilistic");
        }
        if (run == null) {
            warnings.add("No completed matching run yet: only the deterministic rules are previewed");
        }
        return new IdentityPreview(subjects.size(), evaluation.autoMatches().size(), newMerges, evaluation.conflicts().size(),
                evaluation.neverMerge().size(), blocked, evaluation.probabilisticReviews().size(),
                evaluation.probabilisticOnly(), evaluation.evidencePairs(), evaluation.coverage(), run, warnings);
    }

    private static long[] cell(Map<String, Map<String, long[]>> counts, String source, String type) {
        return counts.computeIfAbsent(source, x -> new TreeMap<>()).computeIfAbsent(type, x -> new long[3]);
    }

    private static double percent(long part, long whole) {
        return whole == 0 ? 0 : Math.round(1000.0 * part / whole) / 10.0;
    }
}
