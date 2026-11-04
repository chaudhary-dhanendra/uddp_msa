package gov.uddp.identity;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.contracts.EventEnvelope;
import gov.uddp.contracts.Topics;
import gov.uddp.messaging.Outbox;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/**
 * Runs matching and announces the result (docs/msa-design.md, section 3.3): match-decided for every pair,
 * cluster-assigned for every cluster member and match-run-completed with the counts a consumer needs to know it has
 * the whole run. The events are written to the outbox in one transaction after the run completed; their ids are
 * name-based, so publishing a run twice adds nothing.
 */
@Service
public class MatchingRuns {

    static final String PRODUCER = "identity-service";

    private final MatchingEngine engine;
    private final DataSource dataSource;

    public MatchingRuns(MatchingEngine engine, DataSource dataSource) {
        this.engine = engine;
        this.dataSource = dataSource;
    }

    /** Runs a match for {@code correlationId} (an onboarding run or command; may be null) and publishes it. */
    public UUID run(boolean incremental, UUID correlationId) {
        UUID runId = engine.run(incremental);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var s = c.prepareStatement("update matching_run set correlation_id = ? where run_id = ?")) {
                    s.setObject(1, correlationId);
                    s.setObject(2, runId);
                    s.executeUpdate();
                }
                publish(c, runId, correlationId);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot publish matching run " + runId, e);
        }
        return runId;
    }

    /** Writes the run's events to the outbox; nothing for a run that has not completed. */
    static void publish(Connection c, UUID runId, UUID correlationId) throws SQLException {
        String status = null;
        String mode = null;
        String model = null;
        java.math.BigDecimal auto = null;
        java.math.BigDecimal possible = null;
        int considered = 0;
        try (var s = c.prepareStatement("select status, mode, model_version, threshold, possible_match_threshold,"
                + " records_considered from matching_run where run_id = ?")) {
            s.setObject(1, runId);
            try (var r = s.executeQuery()) {
                if (r.next()) {
                    status = r.getString(1);
                    mode = r.getString(2);
                    model = r.getString(3);
                    auto = r.getBigDecimal(4);
                    possible = r.getBigDecimal(5);
                    considered = r.getInt(6);
                }
            }
        }
        if (!"COMPLETED".equals(status)) {
            return;
        }
        int pairs = 0;
        try (var s = c.prepareStatement("select p.canonical_record_a, p.canonical_record_b, p.match_probability,"
                + " p.match_weight, p.decision, p.evidence::text,"
                + " coalesce(a.source_record_key, p.canonical_record_a::text)"
                + " from resident_match_pair p left join candidate_resident a"
                + " on a.canonical_resident_id = p.canonical_record_a where p.run_id = ?"
                + " order by p.canonical_record_a, p.canonical_record_b")) {
            s.setObject(1, runId);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    ObjectNode p = Outbox.json().createObjectNode();
                    p.put("matchingRunId", runId.toString());
                    p.put("canonicalRecordA", r.getString(1));
                    p.put("canonicalRecordB", r.getString(2));
                    p.put("matchProbability", r.getBigDecimal(3));
                    p.put("matchWeight", r.getBigDecimal(4));
                    p.put("decision", r.getString(5));
                    p.set("evidence", readTree(r.getString(6)));
                    append(c, Topics.MATCH_DECIDED, "uddp.identity.match-decided",
                            runId + "|" + r.getString(1) + "|" + r.getString(2), r.getString(7), correlationId, p);
                    pairs++;
                }
            }
        }
        int members = 0;
        try (var s = c.prepareStatement("select m.canonical_record_id, m.cluster_id,"
                + " coalesce(k.source_record_key, m.canonical_record_id::text)"
                + " from resident_match_cluster_member m left join candidate_resident k"
                + " on k.canonical_resident_id = m.canonical_record_id where m.run_id = ?"
                + " order by m.canonical_record_id")) {
            s.setObject(1, runId);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    ObjectNode p = Outbox.json().createObjectNode();
                    p.put("matchingRunId", runId.toString());
                    p.put("canonicalRecordId", r.getString(1));
                    p.put("clusterId", r.getString(2));
                    append(c, Topics.CLUSTER_ASSIGNED, "uddp.identity.cluster-assigned", runId + "|" + r.getString(1),
                            r.getString(3), correlationId, p);
                    members++;
                }
            }
        }
        ObjectNode p = Outbox.json().createObjectNode();
        p.put("matchingRunId", runId.toString());
        p.put("mode", mode);
        p.put("modelVersion", model);
        p.put("autoMatchThreshold", auto);
        p.put("possibleMatchThreshold", possible);
        p.put("recordsConsidered", considered);
        p.put("pairCount", pairs);
        p.put("clusterMemberCount", members);
        append(c, Topics.MATCH_RUN_COMPLETED, "uddp.identity.match-run-completed", runId.toString(), runId.toString(),
                correlationId, p);
    }

    private static void append(Connection c, String topic, String eventType, String idSource, String key,
                               UUID correlationId, ObjectNode payload) throws SQLException {
        Outbox.append(c, topic, new EventEnvelope(Outbox.deterministicId(eventType, idSource), eventType, 1,
                Instant.now(), PRODUCER, key, correlationId, null, null, null, payload));
    }

    private static com.fasterxml.jackson.databind.JsonNode readTree(String text) {
        try {
            return Outbox.json().readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read stored match evidence", e);
        }
    }
}
