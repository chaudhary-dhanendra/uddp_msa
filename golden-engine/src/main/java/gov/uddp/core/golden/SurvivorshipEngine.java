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

/**
 * State-neutral core-field survivorship; extensions remain on canonical members.
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

    public Map<String, GoldenField> survive(List<CanonicalObservation> input, SurvivorshipPolicy policy) {
        return survive(input, policy, null);
    }

    /** Applies an effective DMN decision only when it selects one of the existing contributors. */
    public Map<String, GoldenField> survive(List<CanonicalObservation> input, SurvivorshipPolicy policy,
                                             DmnSurvivorshipRules dmnRules) {
        if (input.isEmpty()) {
            throw new IllegalArgumentException("A golden Resident requires a member");
        }
        var members = input.stream().sorted(Comparator.comparing(CanonicalObservation::id)).toList();
        Map<String, GoldenField> result = new LinkedHashMap<>();
        for (String path : PATHS) {
            var values = new ArrayList<CanonicalObservation>();
            for (var member : members) {
                JsonNode value = value(member.payload(), path);
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
            if (dmnRules != null) {
                var dmn = dmnRules.choose(path, values);
                if (dmn.isPresent()) {
                    winner = dmn.get().candidate();
                    rule = "DMN:" + dmn.get().clauseReference();
                }
            }
            var contributors = values.stream().map(CanonicalObservation::id).toList();
            result.put(path, new GoldenField(value(winner.payload(), path), winner.id(), rule, contributors));
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
        JsonNode confidence = observation.payload().path("extensions").path(PROVENANCE).path(path).path("confidence");
        return confidence.isNumber() ? confidence.asDouble() : null;
    }

    /** Structured values count as fully trusted. */
    private static double confidence(CanonicalObservation observation, String path) {
        Double parsed = parsedConfidence(observation, path);
        return parsed == null ? 1.0 : parsed;
    }

    private static String normalised(CanonicalObservation observation, String path) {
        return value(observation.payload(), path).asText().trim().toLowerCase();
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
        if (parts.length == 1) {
            node.set(parts[0], value);
        } else {
            ObjectNode child = node.withObject(parts[0]);
            child.set(parts[1], value);
        }
    }
}
