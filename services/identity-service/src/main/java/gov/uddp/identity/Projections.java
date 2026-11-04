package gov.uddp.identity;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * identity-service's read models of other services' data (docs/msa-design.md, section 6.1), applied by the Kafka
 * loop on the inbox transaction. Both are rebuildable from their topics.
 */
public final class Projections {

    private Projections() {
    }

    /** canonical.resident-created: one candidate version; a redelivered or replayed version changes nothing. */
    public static void residentCreated(Connection c, JsonNode envelope) throws SQLException {
        JsonNode p = envelope.path("payload");
        try (var s = c.prepareStatement("insert into candidate_resident (canonical_resident_id, source_record_key,"
                + " version_number, supersedes_canonical_id, raw_record_id, batch_id, state_code, source_system,"
                + " source_entity, state_pack_version, canonical_payload, mapped_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?) on conflict do nothing")) {
            s.setObject(1, UUID.fromString(p.path("canonicalResidentId").asText()));
            s.setString(2, p.path("sourceRecordKey").asText());
            s.setInt(3, p.path("version").asInt());
            s.setObject(4, uuidOrNull(p.path("supersedesCanonicalId")));
            s.setObject(5, UUID.fromString(p.path("rawRecordId").asText()));
            s.setObject(6, UUID.fromString(p.path("batchId").asText()));
            s.setString(7, textOrNull(envelope.path("stateCode")));
            s.setString(8, p.path("sourceSystem").asText());
            s.setString(9, textOrNull(p.path("sourceEntity")));
            s.setString(10, p.path("statePackVersion").asText());
            s.setString(11, p.path("canonicalPayload").toString());
            String mapped = textOrNull(p.path("mappedAt"));
            s.setTimestamp(12, mapped == null ? null : Timestamp.from(Instant.parse(mapped)));
            s.executeUpdate();
        }
    }

    /**
     * registry.golden-resident-changed: the identity's current members replace its previous ones. A member that
     * moved to another identity keeps its newer owner whichever event arrives first, because rows are removed only
     * while they still point at this identity.
     */
    public static void goldenResidentChanged(Connection c, JsonNode envelope) throws SQLException {
        JsonNode p = envelope.path("payload");
        UUID golden = UUID.fromString(p.path("goldenResidentId").asText());
        try (var s = c.prepareStatement("insert into known_golden_resident (golden_resident_id) values (?)"
                + " on conflict do nothing")) {
            s.setObject(1, golden);
            s.executeUpdate();
        }
        try (var s = c.prepareStatement("delete from current_membership where golden_resident_id = ?")) {
            s.setObject(1, golden);
            s.executeUpdate();
        }
        try (var s = c.prepareStatement("insert into current_membership (source_record_key, golden_resident_id,"
                + " canonical_resident_id) values (?, ?, ?) on conflict (source_record_key) do update set"
                + " golden_resident_id = excluded.golden_resident_id,"
                + " canonical_resident_id = excluded.canonical_resident_id, updated_at = now()")) {
            for (JsonNode member : p.path("members")) {
                s.setString(1, member.path("sourceRecordKey").asText());
                s.setObject(2, golden);
                s.setObject(3, UUID.fromString(member.path("canonicalResidentId").asText()));
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    private static UUID uuidOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : UUID.fromString(node.asText());
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
