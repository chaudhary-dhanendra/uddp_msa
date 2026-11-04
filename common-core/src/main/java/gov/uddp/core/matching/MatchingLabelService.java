package gov.uddp.core.matching;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Steward labels on scored pairs (SAME / DIFFERENT / UNSURE) are the ground truth for threshold suggestion. Pairs are
 * sampled across ten score bands so the labels cover the whole range, not just the obvious ends.
 */
@Service
public class MatchingLabelService {
    static final Set<String> LABELS = Set.of("SAME", "DIFFERENT", "UNSURE");
    static final int BANDS = 10;

    public record MatchingSamplePair(UUID recordA, UUID recordB, double matchProbability, int band, String decision,
                             Map<String, Object> evidence) {
    }

    public record MatchingLabel(UUID recordA, UUID recordB, String label, UUID runId, double matchProbability, int band,
                        String reviewer, OffsetDateTime labelledAt) {
    }

    public record MatchingLabelSummary(int total, Map<String, Integer> byLabel, Map<Integer, Map<String, Integer>> byBand) {
    }

    private final PostgresConnectionFactory connections;
    private final ObjectMapper json;
    private final PythonModules.Runner python;

    @org.springframework.beans.factory.annotation.Autowired
    public MatchingLabelService(PostgresConnectionFactory connections, ObjectMapper json, PythonModules modules) {
        this(connections, json, modules::run);
    }

    public MatchingLabelService(PostgresConnectionFactory connections, ObjectMapper json, PythonModules.Runner python) {
        this.connections = connections;
        this.json = json;
        this.python = python;
    }

    /** Up to {@code perBand} unlabelled pairs from each score band of the run, highest band first. */
    public List<MatchingSamplePair> sample(UUID runId, int perBand) {
        if (perBand < 1 || perBand > 200) {
            throw new IllegalArgumentException("perBand must be between 1 and 200");
        }
        UUID run = runId != null ? runId : latestRun();
        String sql = "select a, b, p, band, decision, evidence::text from (select p.canonical_record_a a,"
                + " p.canonical_record_b b, p.match_probability p, p.decision, p.evidence,"
                + " least(" + (BANDS - 1) + ", floor(p.match_probability * " + BANDS + ")::int) band,"
                + " row_number() over (partition by least(" + (BANDS - 1) + ", floor(p.match_probability * " + BANDS
                + ")::int) order by random()) rn from resident_match_pair p where p.run_id = ? and not exists"
                + " (select 1 from matching_label l where l.canonical_record_a = p.canonical_record_a"
                + " and l.canonical_record_b = p.canonical_record_b)) x where rn <= ? order by band desc, p desc";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, run);
            s.setInt(2, perBand);
            var r = s.executeQuery();
            List<MatchingSamplePair> out = new ArrayList<>();
            while (r.next()) {
                out.add(new MatchingSamplePair(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getDouble(3),
                        r.getInt(4), r.getString(5), json.readValue(r.getString(6), new TypeReference<>() { })));
            }
            return out;
        } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException("Cannot sample pairs to label", e);
        }
    }

    /** Stores (or replaces) the current label of a pair, with who labelled it and when. */
    public MatchingLabel label(UUID a, UUID b, String label, UUID runId, String reviewer) {
        if (!LABELS.contains(label)) {
            throw new IllegalArgumentException("label must be SAME, DIFFERENT or UNSURE");
        }
        UUID first = UuidOrder.first(a, b);
        UUID second = UuidOrder.second(a, b);
        UUID run = runId != null ? runId : latestRun();
        try (var c = connections.open()) {
            double probability;
            try (var s = c.prepareStatement("select match_probability from resident_match_pair where run_id = ?"
                    + " and canonical_record_a = ? and canonical_record_b = ?")) {
                s.setObject(1, run);
                s.setObject(2, first);
                s.setObject(3, second);
                var r = s.executeQuery();
                if (!r.next()) {
                    throw new IllegalArgumentException("That pair was not scored in the matching run");
                }
                probability = r.getDouble(1);
            }
            int band = Math.min(BANDS - 1, (int) Math.floor(probability * BANDS));
            try (var s = c.prepareStatement("insert into matching_label(canonical_record_a, canonical_record_b, label,"
                    + " run_id, match_probability, score_band, reviewer) values (?, ?, ?, ?, ?, ?, ?)"
                    + " on conflict (canonical_record_a, canonical_record_b) do update set label = excluded.label,"
                    + " run_id = excluded.run_id, match_probability = excluded.match_probability,"
                    + " score_band = excluded.score_band, reviewer = excluded.reviewer, labelled_at = now()")) {
                s.setObject(1, first);
                s.setObject(2, second);
                s.setString(3, label);
                s.setObject(4, run);
                s.setDouble(5, probability);
                s.setInt(6, band);
                s.setString(7, reviewer);
                s.executeUpdate();
            }
            return new MatchingLabel(first, second, label, run, probability, band, reviewer, OffsetDateTime.now());
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot store the label", e);
        }
    }

    public MatchingLabelSummary summary() {
        try (var c = connections.open(); var s = c.prepareStatement(
                "select score_band, label, count(*) from matching_label group by score_band, label");
             var r = s.executeQuery()) {
            Map<String, Integer> byLabel = new java.util.TreeMap<>();
            Map<Integer, Map<String, Integer>> byBand = new java.util.TreeMap<>();
            int total = 0;
            while (r.next()) {
                int n = r.getInt(3);
                total += n;
                byLabel.merge(r.getString(2), n, Integer::sum);
                byBand.computeIfAbsent(r.getInt(1), k -> new java.util.TreeMap<>()).merge(r.getString(2), n, Integer::sum);
            }
            return new MatchingLabelSummary(total, byLabel, byBand);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot summarise labels", e);
        }
    }

    /** Thresholds that reach {@code targetPrecision}, estimated from the labels (see matching/thresholds.py). */
    public JsonNode suggestThresholds(UUID runId, double targetPrecision) {
        if (!(targetPrecision > 0 && targetPrecision < 1)) {
            throw new IllegalArgumentException("targetPrecision must be between 0 and 1");
        }
        UUID run = runId != null ? runId : latestRun();
        return python.run("thresholds", List.of("--run-id", run.toString(), "--target", Double.toString(targetPrecision)));
    }

    private UUID latestRun() {
        try (var c = connections.open(); var s = c.prepareStatement("select run_id from matching_run"
                + " where status = 'COMPLETED' order by started_at desc limit 1"); var r = s.executeQuery()) {
            if (!r.next()) {
                throw new IllegalArgumentException("There is no completed matching run yet");
            }
            return r.getObject(1, UUID.class);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot find the latest matching run", e);
        }
    }
}
