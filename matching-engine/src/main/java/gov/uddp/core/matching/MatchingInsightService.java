package gov.uddp.core.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Read-only Matching Studio insight: data readiness per source and the "why matched" explanation of a pair. */
@Service
public class MatchingInsightService {
    /** profile field -> evidence key written by the matching run, and the SQL for "this field has a value". */
    private static final Map<String, String[]> FIELDS = new LinkedHashMap<>();

    static {
        FIELDS.put("name", new String[] {"name", "nullif(btrim(canonical_payload ->> 'name'), '') is not null"});
        FIELDS.put("date_of_birth", new String[] {"dateOfBirth", "nullif(btrim(canonical_payload ->> 'dateOfBirth'), '') is not null"});
        FIELDS.put("gender", new String[] {"gender", "nullif(btrim(canonical_payload ->> 'gender'), '') is not null"});
        FIELDS.put("mobile", new String[] {"mobile", "nullif(btrim(canonical_payload ->> 'mobile'), '') is not null"});
        FIELDS.put("district_lgd", new String[] {"districtLGD",
                "nullif(btrim(canonical_payload -> 'address' ->> 'districtLGD'), '') is not null"});
        FIELDS.put("relation_name", new String[] {"relationName", "(nullif(btrim(canonical_payload ->> 'relationName'), '') is not null"
                + " or jsonb_path_exists(canonical_payload, '$.relations[*].name ? (@ != \"\")'))"});
        FIELDS.put("national_id", new String[] {"nationalId",
                "(coalesce(canonical_payload -> 'extensions' -> 'uddp.identifiers', '{}'::jsonb) <> '{}'::jsonb"
                        + " or coalesce(canonical_payload -> 'extensions' -> 'uddp.nationalIds', '{}'::jsonb) <> '{}'::jsonb)"});
    }

    public record MatchingSourceReadiness(String stateCode, String sourceSystem, int records, Map<String, Double> completeness) {
    }

    public record MatchingExplanationStep(String label, String field, String valueA, String valueB, double weight, String plainLanguage) {
    }

    public record MatchingExplanation(UUID runId, UUID recordA, UUID recordB, String modelVersion, String decision,
                              double matchProbability, double matchWeight, double autoMatchThreshold,
                              double possibleMatchThreshold, boolean hasBreakdown, List<MatchingExplanationStep> steps) {
    }

    private final PostgresConnectionFactory connections;
    private final ObjectMapper json;

    public MatchingInsightService(PostgresConnectionFactory connections, ObjectMapper json) {
        this.connections = connections;
        this.json = json;
    }

    /** Share of latest canonical Residents with a value, per matching field and source. */
    public List<MatchingSourceReadiness> readiness() {
        StringBuilder sql = new StringBuilder("select state_code, source_system, count(*)");
        FIELDS.values().forEach(f -> sql.append(", count(*) filter (where ").append(f[1]).append(")"));
        sql.append(" from canonical_resident_current group by state_code, source_system order by state_code, source_system");
        try (var c = connections.open(); var s = c.prepareStatement(sql.toString()); var r = s.executeQuery()) {
            List<MatchingSourceReadiness> out = new ArrayList<>();
            while (r.next()) {
                int total = r.getInt(3);
                Map<String, Double> completeness = new LinkedHashMap<>();
                int column = 4;
                for (String field : FIELDS.keySet()) {
                    completeness.put(field, total == 0 ? 0.0 : r.getInt(column++) / (double) total);
                }
                out.add(new MatchingSourceReadiness(r.getString(1), r.getString(2), total, completeness));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot compute data readiness", e);
        }
    }

    /**
     * The waterfall for one scored pair: the starting weight, then what each compared field added or took away, ending
     * at the pair's total match weight. Runs of the built-in model carry no per-field weights, so only the total shows.
     */
    public MatchingExplanation explain(UUID runId, UUID a, UUID b) {
        UUID first = UuidOrder.first(a, b);
        UUID second = UuidOrder.second(a, b);
        try (var c = connections.open(); var s = c.prepareStatement("select r.model_version, r.threshold,"
                + " r.possible_match_threshold, p.decision, p.match_probability, p.match_weight, p.evidence::text"
                + " from resident_match_pair p join matching_run r on r.run_id = p.run_id"
                + " where p.run_id = ? and p.canonical_record_a = ? and p.canonical_record_b = ?")) {
            s.setObject(1, runId);
            s.setObject(2, first);
            s.setObject(3, second);
            var r = s.executeQuery();
            if (!r.next()) {
                throw new IllegalArgumentException("That pair was not scored in the matching run");
            }
            JsonNode evidence = json.readTree(r.getString(7));
            JsonNode weights = evidence.path("comparisonWeights");
            double total = r.getDouble(6);
            List<MatchingExplanationStep> steps = new ArrayList<>();
            if (weights.isObject()) {
                steps.add(new MatchingExplanationStep("Starting point", null, null, null, evidence.path("priorWeight").asDouble(),
                        "Before looking at any field, how likely two records are to be the same person"));
                for (String field : FIELDS.keySet()) {
                    if (weights.has(field)) {
                        JsonNode values = evidence.path(FIELDS.get(field)[0]);
                        double weight = weights.get(field).asDouble();
                        steps.add(new MatchingExplanationStep(label(field), FIELDS.get(field)[0], text(values.path(0)), text(values.path(1)), weight,
                                sentence(field, weight)));
                    }
                }
            }
            return new MatchingExplanation(runId, first, second, r.getString(1), r.getString(4), r.getDouble(5), total,
                    r.getDouble(2), r.getDouble(3), weights.isObject(), steps);
        } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException("Cannot explain the pair", e);
        }
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static String label(String field) {
        String spaced = field.replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String sentence(String field, double weight) {
        String f = field.replace('_', ' ');
        if (Math.abs(weight) < 0.5) {
            return "The " + f + " made little difference";
        }
        String strength = Math.abs(weight) >= 6 ? "strongly" : Math.abs(weight) >= 2 ? "clearly" : "slightly";
        return "The " + f + (weight > 0 ? " " + strength + " supports the match" : " " + strength + " argues against the match");
    }
}
