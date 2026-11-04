package gov.uddp.core.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Projects identity-service's matching runs into this database's matching tables (MSA Phase 3), so the Golden build,
 * stewardship and the read APIs keep reading them unchanged. Events of one run arrive on three topics in any order: the
 * first one creates the run as RECEIVING, and the run becomes COMPLETED only when every pair and cluster member that
 * match-run-completed announced is present. Each handler runs on the inbox transaction, so redelivery is harmless.
 */
public final class RemoteMatchingProjection {

    private RemoteMatchingProjection() {
    }

    public static void matchDecided(Connection c, JsonNode envelope) throws SQLException {
        JsonNode p = envelope.path("payload");
        UUID run = UUID.fromString(p.path("matchingRunId").asText());
        ensureRun(c, run, envelope);
        try (var s = c.prepareStatement("insert into resident_match_pair (run_id, canonical_record_a, canonical_record_b,"
                + " match_probability, match_weight, decision, evidence) values (?, ?, ?, ?, ?, ?, cast(? as jsonb))"
                + " on conflict do nothing")) {
            s.setObject(1, run);
            s.setObject(2, UUID.fromString(p.path("canonicalRecordA").asText()));
            s.setObject(3, UUID.fromString(p.path("canonicalRecordB").asText()));
            s.setBigDecimal(4, p.path("matchProbability").decimalValue());
            s.setBigDecimal(5, p.path("matchWeight").decimalValue());
            s.setString(6, p.path("decision").asText());
            s.setString(7, p.path("evidence").isMissingNode() ? "{}" : p.path("evidence").toString());
            s.executeUpdate();
        }
        tryComplete(c, run);
    }

    public static void clusterAssigned(Connection c, JsonNode envelope) throws SQLException {
        JsonNode p = envelope.path("payload");
        UUID run = UUID.fromString(p.path("matchingRunId").asText());
        ensureRun(c, run, envelope);
        try (var s = c.prepareStatement("insert into resident_match_cluster_member (run_id, cluster_id,"
                + " canonical_record_id) values (?, ?, ?) on conflict do nothing")) {
            s.setObject(1, run);
            s.setString(2, p.path("clusterId").asText());
            s.setObject(3, UUID.fromString(p.path("canonicalRecordId").asText()));
            s.executeUpdate();
        }
        tryComplete(c, run);
    }

    public static void matchRunCompleted(Connection c, JsonNode envelope) throws SQLException {
        JsonNode p = envelope.path("payload");
        UUID run = UUID.fromString(p.path("matchingRunId").asText());
        ensureRun(c, run, envelope);
        try (var s = c.prepareStatement("update matching_run set model_version = ?, mode = ?, threshold = ?,"
                + " possible_match_threshold = ?, records_considered = ?, expected_pair_count = ?,"
                + " expected_cluster_member_count = ?, correlation_id = coalesce(?, correlation_id)"
                + " where run_id = ? and status = 'RECEIVING'")) {
            s.setString(1, p.path("modelVersion").asText("identity-service"));
            s.setString(2, p.path("mode").asText("FULL"));
            s.setBigDecimal(3, decimal(p.path("autoMatchThreshold")));
            s.setBigDecimal(4, p.path("possibleMatchThreshold").isNumber()
                    ? p.path("possibleMatchThreshold").decimalValue() : null);
            s.setInt(5, p.path("recordsConsidered").asInt(0));
            s.setInt(6, p.path("pairCount").asInt());
            s.setInt(7, p.path("clusterMemberCount").asInt());
            s.setObject(8, correlation(envelope));
            s.setObject(9, run);
            s.executeUpdate();
        }
        tryComplete(c, run);
    }

    private static void ensureRun(Connection c, UUID run, JsonNode envelope) throws SQLException {
        try (var s = c.prepareStatement("insert into matching_run (run_id, model_version, status, threshold, origin,"
                + " correlation_id) values (?, 'identity-service', 'RECEIVING', 0, 'IDENTITY_SERVICE', ?)"
                + " on conflict (run_id) do nothing")) {
            s.setObject(1, run);
            s.setObject(2, correlation(envelope));
            s.executeUpdate();
        }
    }

    private static void tryComplete(Connection c, UUID run) throws SQLException {
        try (var s = c.prepareStatement("update matching_run r set status = 'COMPLETED', completed_at = now()"
                + " where r.run_id = ? and r.status = 'RECEIVING' and r.expected_pair_count is not null"
                + " and r.expected_pair_count = (select count(*) from resident_match_pair p where p.run_id = r.run_id)"
                + " and r.expected_cluster_member_count = (select count(*) from resident_match_cluster_member m"
                + " where m.run_id = r.run_id)")) {
            s.setObject(1, run);
            s.executeUpdate();
        }
    }

    private static UUID correlation(JsonNode envelope) {
        String value = envelope.path("correlationId").asText(null);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static BigDecimal decimal(JsonNode node) {
        return node.isNumber() ? node.decimalValue() : BigDecimal.ZERO;
    }
}
