package gov.uddp.core.matching;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.stewardship.StewardshipConflictException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Matching monitoring: the metrics stored after each run are checked against configurable bounds (and against the
 * previous run) to raise alerts, and steward behaviour is turned into threshold-change proposals. A proposal changes
 * nothing until a different user approves it; approval creates a new approved profile version with the new threshold.
 */
@Service
public class MatchingMonitoringService {
    static final int BANDS = 20;
    public static final int MIN_DECISIONS = 20;
    public static final double MIN_RATE = 0.97;
    private static final List<String> METRICS = List.of("match_rate", "review_rate", "auto_pairs", "review_volume");

    public record MatchingRunMetrics(UUID runId, OffsetDateTime startedAt, String modelVersion, int recordsConsidered, int autoPairs,
                             int possiblePairs, int noMatchPairs, int matchedRecords, double matchRate, double reviewRate,
                             List<Integer> scoreHistogram, Map<String, Map<String, Integer>> acceptanceByBand) {
    }

    public record MatchingBound(String metric, Double minValue, Double maxValue, Double maxRelativeChange, String updatedBy) {
    }

    public record MatchingAlert(UUID alertId, UUID runId, String metric, String kind, String message, double observed,
                        String expected, String status, OffsetDateTime createdAt, String acknowledgedBy) {
    }

    public record MatchingProposal(UUID proposalId, String kind, double currentValue, double proposedValue, String rationale,
                           String status, String proposedBy, OffsetDateTime createdAt, String decidedBy,
                           UUID appliedProfileId) {
    }

    private final PostgresConnectionFactory connections;
    private final ObjectMapper json;
    private final MatchingProfileService profiles;

    public MatchingMonitoringService(PostgresConnectionFactory connections, ObjectMapper json,
                                     MatchingProfileService profiles) {
        this.connections = connections;
        this.json = json;
        this.profiles = profiles;
    }

    // ---- metrics and alerts

    /** Stored metrics of the most recent runs, newest first. Runs not yet checked are evaluated first. */
    public List<MatchingRunMetrics> metrics(int limit) {
        evaluate();
        try (var c = connections.open(); var s = c.prepareStatement("select m.run_id, r.started_at, r.model_version,"
                + " m.records_considered, m.auto_pairs, m.possible_pairs, m.no_match_pairs, m.matched_records,"
                + " m.match_rate, m.review_rate, m.score_histogram::text, m.acceptance_by_band::text"
                + " from matching_run_metrics m join matching_run r on r.run_id = m.run_id"
                + " order by r.started_at desc limit ?")) {
            s.setInt(1, limit);
            var r = s.executeQuery();
            List<MatchingRunMetrics> out = new ArrayList<>();
            while (r.next()) {
                out.add(new MatchingRunMetrics(r.getObject(1, UUID.class), r.getObject(2, OffsetDateTime.class), r.getString(3),
                        r.getInt(4), r.getInt(5), r.getInt(6), r.getInt(7), r.getInt(8), r.getDouble(9), r.getDouble(10),
                        json.readValue(r.getString(11), new TypeReference<>() { }),
                        json.readValue(r.getString(12), new TypeReference<>() { })));
            }
            return out;
        } catch (SQLException | java.io.IOException e) {
            throw new IllegalStateException("Cannot read matching metrics", e);
        }
    }

    public List<MatchingBound> bounds() {
        try (var c = connections.open(); var s = c.prepareStatement("select metric, min_value, max_value,"
                + " max_relative_change, updated_by from matching_metric_bound order by metric"); var r = s.executeQuery()) {
            List<MatchingBound> out = new ArrayList<>();
            while (r.next()) {
                out.add(new MatchingBound(r.getString(1), number(r, 2), number(r, 3),
                        number(r, 4), r.getString(5)));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read metric bounds", e);
        }
    }

    private static Double number(java.sql.ResultSet r, int column) throws SQLException {
        BigDecimal value = r.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    public MatchingBound updateBound(String metric, Double min, Double max, Double maxRelativeChange, String user) {
        if (!METRICS.contains(metric)) {
            throw new IllegalArgumentException("metric must be one of " + METRICS);
        }
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException("The lower bound is above the upper bound");
        }
        if (maxRelativeChange != null && maxRelativeChange < 0) {
            throw new IllegalArgumentException("maxRelativeChange cannot be negative");
        }
        execute("update matching_metric_bound set min_value = ?, max_value = ?, max_relative_change = ?, updated_by = ?,"
                + " updated_at = now() where metric = ?", min, max, maxRelativeChange, user, metric);
        return bounds().stream().filter(b -> b.metric().equals(metric)).findFirst().orElseThrow();
    }

    /** Checks every run whose metrics have not been checked yet against the bounds and the run before it. */
    public int evaluate() {
        Map<String, MatchingBound> bounds = new LinkedHashMap<>();
        bounds().forEach(b -> bounds.put(b.metric(), b));
        int raised = 0;
        try (var c = connections.open(); var s = c.prepareStatement("select m.run_id, m.match_rate, m.review_rate,"
                + " m.auto_pairs, m.possible_pairs, (select p.run_id from matching_run_metrics p join matching_run pr"
                + " on pr.run_id = p.run_id where pr.started_at < r.started_at order by pr.started_at desc limit 1)"
                + " from matching_run_metrics m join matching_run r on r.run_id = m.run_id"
                + " where m.evaluated_at is null order by r.started_at")) {
            var rows = s.executeQuery();
            List<Object[]> pending = new ArrayList<>();
            while (rows.next()) {
                pending.add(new Object[] {rows.getObject(1, UUID.class), rows.getDouble(2), rows.getDouble(3),
                        (double) rows.getInt(4), (double) rows.getInt(5), rows.getObject(6, UUID.class)});
            }
            for (Object[] row : pending) {
                UUID run = (UUID) row[0];
                Map<String, Double> now = values(row);
                Map<String, Double> before = row[5] == null ? Map.of() : values(valuesOf(c, (UUID) row[5]));
                for (String metric : METRICS) {
                    raised += check(c, run, metric, now.get(metric), before.get(metric), bounds.get(metric));
                }
                try (var done = c.prepareStatement("update matching_run_metrics set evaluated_at = now() where run_id = ?")) {
                    done.setObject(1, run);
                    done.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot evaluate matching metrics", e);
        }
        return raised;
    }

    private static Map<String, Double> values(Object[] row) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("match_rate", (Double) row[1]);
        out.put("review_rate", (Double) row[2]);
        out.put("auto_pairs", (Double) row[3]);
        out.put("review_volume", (Double) row[4]);
        return out;
    }

    private static Object[] valuesOf(Connection c, UUID run) throws SQLException {
        try (var s = c.prepareStatement("select match_rate, review_rate, auto_pairs, possible_pairs"
                + " from matching_run_metrics where run_id = ?")) {
            s.setObject(1, run);
            var r = s.executeQuery();
            r.next();
            return new Object[] {run, r.getDouble(1), r.getDouble(2), (double) r.getInt(3), (double) r.getInt(4)};
        }
    }

    private int check(Connection c, UUID run, String metric, double value, Double previous, MatchingBound bound) throws SQLException {
        if (bound == null) {
            return 0;
        }
        int raised = 0;
        if (bound.minValue() != null && value < bound.minValue()) {
            alert(c, run, metric, "OUT_OF_BOUNDS", metric + " is " + round(value) + ", below the lower bound "
                    + bound.minValue(), value, ">= " + bound.minValue());
            raised++;
        }
        if (bound.maxValue() != null && value > bound.maxValue()) {
            alert(c, run, metric, "OUT_OF_BOUNDS", metric + " is " + round(value) + ", above the upper bound "
                    + bound.maxValue(), value, "<= " + bound.maxValue());
            raised++;
        }
        if (bound.maxRelativeChange() != null && previous != null && previous > 0) {
            double change = Math.abs(value - previous) / previous;
            if (change > bound.maxRelativeChange()) {
                alert(c, run, metric, "DRIFT", metric + " moved from " + round(previous) + " to " + round(value) + " ("
                        + Math.round(change * 100) + "% change) since the previous run", value,
                        "within " + Math.round(bound.maxRelativeChange() * 100) + "% of the previous run");
                raised++;
            }
        }
        return raised;
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private void alert(Connection c, UUID run, String metric, String kind, String message, double observed, String expected)
            throws SQLException {
        try (var s = c.prepareStatement("insert into matching_alert(alert_id, run_id, metric, kind, message, observed,"
                + " expected) values (?, ?, ?, ?, ?, ?, ?)")) {
            s.setObject(1, UUID.randomUUID());
            s.setObject(2, run);
            s.setString(3, metric);
            s.setString(4, kind);
            s.setString(5, message);
            s.setDouble(6, observed);
            s.setString(7, expected);
            s.executeUpdate();
        }
    }

    public List<MatchingAlert> alerts(boolean openOnly) {
        evaluate();
        try (var c = connections.open(); var s = c.prepareStatement("select alert_id, run_id, metric, kind, message,"
                + " observed, expected, status, created_at, acknowledged_by from matching_alert"
                + (openOnly ? " where status = 'OPEN'" : "") + " order by created_at desc, metric"); var r = s.executeQuery()) {
            List<MatchingAlert> out = new ArrayList<>();
            while (r.next()) {
                out.add(new MatchingAlert(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3), r.getString(4),
                        r.getString(5), r.getDouble(6), r.getString(7), r.getString(8), r.getObject(9, OffsetDateTime.class),
                        r.getString(10)));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read matching alerts", e);
        }
    }

    public void acknowledge(UUID alertId, String user) {
        execute("update matching_alert set status = 'ACKNOWLEDGED', acknowledged_by = ?, acknowledged_at = now()"
                + " where alert_id = ? and status = 'OPEN'", user, alertId);
    }

    // ---- proposals from steward behaviour

    /** One band of steward decisions: accepted and rejected counts for pairs scoring in [low, low + 0.05). */
    record BandDecisions(int accepted, int rejected) {
        int total() {
            return accepted + rejected;
        }
    }

    /** Draft proposal before it is stored. */
    record Draft(String kind, double current, double proposed, String rationale) {
    }

    /**
     * Pure rule: walk down from the AUTO_MATCH threshold while at least {@code minRate} of the decisions in a band were
     * accepted, and up from the review threshold while at least {@code minRate} were rejected. Bands with fewer than
     * {@code minDecisions} decisions stop the walk: there is not enough evidence.
     */
    static List<Draft> draft(Map<Integer, BandDecisions> bands, double auto, double possible, int minDecisions, double minRate) {
        List<Draft> out = new ArrayList<>();
        int lowestAccepted = -1;
        BandDecisions summed = new BandDecisions(0, 0);
        for (int band = (int) Math.ceil(auto * BANDS - 1e-9) - 1; band >= (int) Math.floor(possible * BANDS + 1e-9); band--) {
            BandDecisions d = bands.getOrDefault(band, new BandDecisions(0, 0));
            if (d.total() < minDecisions || d.accepted() < minRate * d.total()) {
                break;
            }
            lowestAccepted = band;
            summed = new BandDecisions(summed.accepted() + d.accepted(), summed.rejected() + d.rejected());
        }
        if (lowestAccepted >= 0) {
            double proposed = lowestAccepted / (double) BANDS;
            out.add(new Draft("AUTO_MATCH", auto, proposed, percent(summed.accepted(), summed.total()) + " of "
                    + range(proposed, auto) + " accepted by stewards (" + summed.total() + " decisions): lower AUTO_MATCH from "
                    + auto + " to " + proposed + "?"));
        }
        int highestRejected = -1;
        summed = new BandDecisions(0, 0);
        for (int band = (int) Math.floor(possible * BANDS + 1e-9); band < (int) Math.ceil(auto * BANDS - 1e-9); band++) {
            BandDecisions d = bands.getOrDefault(band, new BandDecisions(0, 0));
            if (d.total() < minDecisions || d.rejected() < minRate * d.total()) {
                break;
            }
            highestRejected = band;
            summed = new BandDecisions(summed.accepted() + d.accepted(), summed.rejected() + d.rejected());
        }
        if (highestRejected >= 0) {
            double proposed = (highestRejected + 1) / (double) BANDS;
            out.add(new Draft("POSSIBLE_MATCH", possible, proposed, percent(summed.rejected(), summed.total()) + " of "
                    + range(possible, proposed) + " rejected by stewards (" + summed.total()
                    + " decisions): raise the review threshold from " + possible + " to " + proposed + "?"));
        }
        return out;
    }

    private static String percent(int part, int whole) {
        return Math.round(100.0 * part / whole) + "%";
    }

    private static String range(double low, double high) {
        return String.format(java.util.Locale.ROOT, "%.2f–%.2f", low, high);
    }

    /** Recomputes proposals from the steward decisions so far; earlier pending ones are superseded. */
    public List<MatchingProposal> refreshSuggestions(String user, int minDecisions, double minRate) {
        double[] thresholds = currentThresholds();
        List<Draft> drafts = draft(liveAcceptance(), thresholds[0], thresholds[1], minDecisions, minRate);
        execute("update matching_threshold_proposal set status = 'SUPERSEDED' where status = 'PENDING_APPROVAL'");
        for (Draft d : drafts) {
            execute("insert into matching_threshold_proposal(proposal_id, kind, current_value, proposed_value, rationale,"
                    + " proposed_by) values (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), d.kind(), d.current(), d.proposed(),
                    d.rationale(), user);
        }
        return proposals(true);
    }

    public Map<Integer, BandDecisions> liveAcceptance() {
        Map<Integer, int[]> raw = new LinkedHashMap<>();
        try (var c = connections.open(); var s = c.prepareStatement("select least(" + (BANDS - 1)
                + ", floor(t.match_probability * " + BANDS + ")::int), d.action, count(*) from stewardship_decision d"
                + " join stewardship_review_task t on t.review_task_id = d.review_task_id"
                + " where d.action in ('ACCEPT_MATCH','REJECT_MATCH') group by 1, 2"); var r = s.executeQuery()) {
            while (r.next()) {
                raw.computeIfAbsent(r.getInt(1), k -> new int[2])["ACCEPT_MATCH".equals(r.getString(2)) ? 0 : 1] += r.getInt(3);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read steward decisions", e);
        }
        Map<Integer, BandDecisions> out = new LinkedHashMap<>();
        raw.forEach((band, counts) -> out.put(band, new BandDecisions(counts[0], counts[1])));
        return out;
    }

    /** {autoMatch, possibleMatch} in force: the approved profile's, else the latest completed run's. */
    double[] currentThresholds() {
        var approved = profiles.approved();
        if (approved.isPresent() && approved.get().config().get("thresholds") instanceof Map<?, ?> t) {
            return new double[] {((Number) t.get("autoMatch")).doubleValue(), ((Number) t.get("possibleMatch")).doubleValue()};
        }
        try (var c = connections.open(); var s = c.prepareStatement("select threshold, possible_match_threshold"
                + " from matching_run where status = 'COMPLETED' order by started_at desc limit 1"); var r = s.executeQuery()) {
            if (r.next()) {
                return new double[] {r.getDouble(1), r.getDouble(2)};
            }
            return new double[] {0.95, 0.75};
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the current thresholds", e);
        }
    }

    public List<MatchingProposal> proposals(boolean pendingOnly) {
        try (var c = connections.open(); var s = c.prepareStatement("select proposal_id, kind, current_value,"
                + " proposed_value, rationale, status, proposed_by, created_at, decided_by, applied_profile_id"
                + " from matching_threshold_proposal" + (pendingOnly ? " where status = 'PENDING_APPROVAL'" : "")
                + " order by created_at desc, kind"); var r = s.executeQuery()) {
            List<MatchingProposal> out = new ArrayList<>();
            while (r.next()) {
                out.add(new MatchingProposal(r.getObject(1, UUID.class), r.getString(2), r.getDouble(3), r.getDouble(4),
                        r.getString(5), r.getString(6), r.getString(7), r.getObject(8, OffsetDateTime.class),
                        r.getString(9), r.getObject(10, UUID.class)));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read threshold proposals", e);
        }
    }

    /** A different user approves: a new approved profile version carries the changed threshold. */
    public MatchingProposal approve(UUID proposalId, String approver) {
        MatchingProposal proposal = pending(proposalId);
        if (approver == null || approver.isBlank() || approver.equals(proposal.proposedBy())) {
            throw new StewardshipConflictException("A threshold change must be approved by someone other than its proposer");
        }
        var approved = profiles.approved().orElseThrow(() -> new StewardshipConflictException(
                "Thresholds of the built-in model come from the State Packs; approve a matching profile first"));
        double auto = proposal.kind().equals("AUTO_MATCH") ? proposal.proposedValue() : proposed(approved, "autoMatch");
        double possible = proposal.kind().equals("POSSIBLE_MATCH") ? proposal.proposedValue() : proposed(approved, "possibleMatch");
        var created = profiles.approveWithThresholds(approved.profileId(), auto, possible, approver);
        execute("update matching_threshold_proposal set status = 'APPLIED', decided_by = ?, decided_at = now(),"
                + " applied_profile_id = ? where proposal_id = ?", approver, created.profileId(), proposalId);
        return proposals(false).stream().filter(p -> p.proposalId().equals(proposalId)).findFirst().orElseThrow();
    }

    public MatchingProposal reject(UUID proposalId, String reviewer) {
        pending(proposalId);
        execute("update matching_threshold_proposal set status = 'REJECTED', decided_by = ?, decided_at = now()"
                + " where proposal_id = ?", reviewer, proposalId);
        return proposals(false).stream().filter(p -> p.proposalId().equals(proposalId)).findFirst().orElseThrow();
    }

    private static double proposed(MatchingProfileService.MatchingProfile profile, String key) {
        return ((Number) ((Map<?, ?>) profile.config().get("thresholds")).get(key)).doubleValue();
    }

    private MatchingProposal pending(UUID id) {
        MatchingProposal proposal = proposals(false).stream().filter(p -> p.proposalId().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Proposal does not exist: " + id));
        if (!proposal.status().equals("PENDING_APPROVAL")) {
            throw new StewardshipConflictException("The proposal is " + proposal.status());
        }
        return proposal;
    }

    private void execute(String sql, Object... arguments) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                s.setObject(i + 1, arguments[i]);
            }
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Matching monitoring update failed", e);
        }
    }
}
