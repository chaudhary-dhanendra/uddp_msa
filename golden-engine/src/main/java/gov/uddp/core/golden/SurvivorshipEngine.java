package gov.uddp.core.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * State-neutral core-field survivorship. Extensions remain on canonical members, except declared Golden state fields:
 * {@code stateFields.<stateCode>.<key>} is survived from the members of that state code by the same rules.
 * Blank values are ignored; agreement wins; otherwise the source the policy ranks highest for that field wins.
 * Unranked sources, or top-ranked sources that disagree, fall back to stable canonical-ID order.
 */
public class SurvivorshipEngine {
    static final List<String> PATHS = List.of("name", "dateOfBirth", "gender", "mobile", "address.districtLGD");
    static final String ALL_VALUES_AGREE = "ALL_VALUES_AGREE";
    static final String CONFIGURED_SOURCE_PRECEDENCE = "CONFIGURED_SOURCE_PRECEDENCE";
    static final String DETERMINISTIC_CANONICAL_ID_FALLBACK = "DETERMINISTIC_CANONICAL_ID_FALLBACK";
    static final String CONFIDENCE_THRESHOLD = "CONFIDENCE_THRESHOLD";
    static final String STRUCTURED_OVER_PARSED = "STRUCTURED_OVER_PARSED";
    /** A parsed value below this provenance confidence never beats one at or above it. */
    static final double MIN_CONFIDENCE = 0.8;
    private static final String PROVENANCE = "uddp.fieldProvenance";
    private static final int UNRANKED = Integer.MAX_VALUE;
    /** Golden path prefix of a declared state field: {@code stateFields.<stateCode>.<key>}. */
    public static final String STATE_FIELDS = "stateFields";

    public Map<String, GoldenField> survive(List<CanonicalObservation> input, SurvivorshipPolicy policy) {
        return survive(input, policy, null);
    }

    /** Applies an effective DMN decision only when it selects one of the existing contributors. */
    public Map<String, GoldenField> survive(List<CanonicalObservation> input, SurvivorshipPolicy policy,
                                             DmnSurvivorshipRules dmnRules) {
        return survive(input, policy, dmnRules, Map.of());
    }

    /**
     * Also survives the declared Golden state fields: {@code goldenStateFields} maps a state code to the keys its
     * packs carry to Golden; only members of that state code contribute.
     */
    public Map<String, GoldenField> survive(List<CanonicalObservation> input, SurvivorshipPolicy policy,
                                             DmnSurvivorshipRules dmnRules, Map<String, List<String>> goldenStateFields) {
        if (input.isEmpty()) {
            throw new IllegalArgumentException("A golden Resident requires a member");
        }
        var members = input.stream().sorted(Comparator.comparing(CanonicalObservation::id)).toList();
        List<String> paths = new ArrayList<>(PATHS);
        new TreeMap<>(goldenStateFields).forEach((stateCode, keys) -> keys.stream().sorted().distinct()
                .forEach(key -> paths.add(STATE_FIELDS + "." + stateCode + "." + key)));
        Map<String, GoldenField> result = new LinkedHashMap<>();
        for (String path : paths) {
            boolean stateField = path.startsWith(STATE_FIELDS + ".");
            var values = new ArrayList<CanonicalObservation>();
            for (var member : members) {
                JsonNode value = observed(member, path);
                if (value != null && !value.isNull() && !value.asText().trim().isEmpty()) {
                    values.add(member);
                }
            }
            if (values.isEmpty()) {
                continue;
            }
            var distinct = values.stream().map(x -> normalised(x, path)).distinct().toList();
            CanonicalObservation winner;
            String rule;
            if (distinct.size() == 1) {
                winner = values.getFirst();
                rule = ALL_VALUES_AGREE;
            } else {
                List<CanonicalObservation> candidates = values;
                var trusted = values.stream().filter(x -> confidence(x, path) >= MIN_CONFIDENCE).toList();
                boolean lowDropped = !trusted.isEmpty() && trusted.size() < values.size();
                if (lowDropped) {
                    candidates = trusted;
                }
                int bestRank = candidates.stream().mapToInt(x -> rank(policy, path, x)).min().orElseThrow();
                var best = candidates.stream().filter(x -> rank(policy, path, x) == bestRank).toList();
                var structured = best.stream().filter(x -> parsedConfidence(x, path) == null).toList();
                boolean structuredPreferred = !structured.isEmpty() && structured.size() < best.size();
                if (structuredPreferred) {
                    best = structured;
                }
                // values is already in canonical-ID order, so the first top-ranked observation is the stable choice.
                winner = best.getFirst();
                boolean bestAgree = best.stream().map(x -> normalised(x, path)).distinct().count() == 1;
                rule = bestRank != UNRANKED && bestAgree ? CONFIGURED_SOURCE_PRECEDENCE : DETERMINISTIC_CANONICAL_ID_FALLBACK;
                if (lowDropped) {
                    rule = CONFIDENCE_THRESHOLD;
                } else if (structuredPreferred) {
                    rule = STRUCTURED_OVER_PARSED;
                }
            }
            if (dmnRules != null && !stateField) {
                var dmn = dmnRules.choose(path, values);
                if (dmn.isPresent()) {
                    winner = dmn.get().candidate();
                    rule = "DMN:" + dmn.get().clauseReference();
                }
            }
            var contributors = values.stream().map(CanonicalObservation::id).toList();
            result.put(path, new GoldenField(observed(winner, path), winner.id(), rule, contributors));
        }
        return result;
    }

    public ObjectNode payload(List<CanonicalObservation> members, SurvivorshipPolicy policy, ObjectMapper json) {
        return payload(survive(members, policy), json);
    }

    /** Core fields plus every member's relations: relations have no winner, each observation is kept with its source. */
    public ObjectNode payload(Map<String, GoldenField> fields, List<CanonicalObservation> members, ObjectMapper json) {
        ObjectNode out = payload(fields, json);
        ArrayNode relations = json.createArrayNode();
        members.stream().sorted(Comparator.comparing(CanonicalObservation::id)).forEach(member -> {
            for (JsonNode relation : member.payload().path("relations")) {
                ObjectNode observed = relations.addObject().setAll((ObjectNode) relation.deepCopy());
                observed.put("canonicalResidentId", member.id().toString());
                observed.put("source", SurvivorshipPolicy.sourceKey(member.stateCode(), member.source()));
            }
        });
        if (!relations.isEmpty()) {
            out.set("relations", relations);
        }
        return out;
    }

    public ObjectNode payload(Map<String, GoldenField> fields, ObjectMapper json) {
        var out = json.createObjectNode();
        for (var entry : fields.entrySet()) {
            set(out, entry.getKey(), entry.getValue().value());
        }
        return out;
    }

    private static int rank(SurvivorshipPolicy policy, String path, CanonicalObservation observation) {
        return policy.rank(path, observation).orElse(UNRANKED);
    }

    /** The parse confidence recorded for a field of a member, or null for a structured (unparsed) value. */
    private static Double parsedConfidence(CanonicalObservation observation, String path) {
        String recorded = path.startsWith(STATE_FIELDS + ".") ? "extensions." + stateFieldKey(path) : path;
        JsonNode confidence = observation.payload().path("extensions").path(PROVENANCE).path(recorded).path("confidence");
        return confidence.isNumber() ? confidence.asDouble() : null;
    }

    /** Structured values count as fully trusted. */
    private static double confidence(CanonicalObservation observation, String path) {
        Double parsed = parsedConfidence(observation, path);
        return parsed == null ? 1.0 : parsed;
    }

    private static String normalised(CanonicalObservation observation, String path) {
        return observed(observation, path).asText().trim().toLowerCase();
    }

    /** A member's value for a path; a state field is read from the extensions of members of its state code only. */
    private static JsonNode observed(CanonicalObservation observation, String path) {
        if (!path.startsWith(STATE_FIELDS + ".")) {
            return value(observation.payload(), path);
        }
        String[] parts = path.split("\\.", 3);
        if (!parts[1].equals(observation.stateCode())) {
            return null;
        }
        JsonNode value = observation.payload().path("extensions").get(parts[2]);
        return value == null || value.isMissingNode() ? null : value;
    }

    private static String stateFieldKey(String path) {
        return path.split("\\.", 3)[2];
    }

    private static JsonNode value(JsonNode node, String path) {
        for (String part : path.split("\\.")) {
            node = node.path(part);
            if (node.isMissingNode()) {
                return null;
            }
        }
        return node;
    }

    private static void set(ObjectNode node, String path, JsonNode value) {
        String[] parts = path.split("\\.");
        ObjectNode parent = node;
        for (int i = 0; i < parts.length - 1; i++) {
            parent = parent.withObject(parts[i]);
        }
        parent.set(parts[parts.length - 1], value);
    }
}
