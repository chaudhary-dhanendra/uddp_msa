package gov.uddp.core.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Projects canonical-service's events into this database's canonical tables with the same ids (MSA Phase 3), so the
 * Golden build and read APIs read them unchanged; registry-service uses the same projection. canonical.batch-processed
 * is recorded so the MAPPED stage knows a batch is done. Each handler runs on the inbox transaction.
 */
public final class RemoteCanonicalProjection {

    private RemoteCanonicalProjection() {
    }

    /**
     * Where the canonical Resident references a projected raw record (the edge), resident-created waits until that
     * raw record is projected: it arrives on ingestion's topic and lags behind in a large delivery.
     */
    public static void residentCreatedAfterRaw(Connection c, JsonNode envelope) throws SQLException {
        String raw = envelope.path("payload").path("rawRecordId").asText();
        try (var s = c.prepareStatement("select 1 from raw_record where raw_record_id = ?")) {
            s.setObject(1, UUID.fromString(raw));
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    throw new gov.uddp.messaging.Inbox.NotYetApplicable("raw record " + raw + " not projected yet");
                }
            }
        }
        residentCreated(c, envelope);
    }

    public static void residentCreated(Connection c, JsonNode envelope) throws SQLException {
        JsonNode p = envelope.path("payload");
        UUID canonical = UUID.fromString(p.path("canonicalResidentId").asText());
        try (var s = c.prepareStatement("insert into canonical_resident (canonical_resident_id, raw_record_id, batch_id,"
                + " state_code, source_system, source_entity, state_pack_version, canonical_payload, mapped_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?) on conflict do nothing")) {
            s.setObject(1, canonical);
            s.setObject(2, UUID.fromString(p.path("rawRecordId").asText()));
            s.setObject(3, UUID.fromString(p.path("batchId").asText()));
            s.setString(4, envelope.path("stateCode").asText());
            s.setString(5, p.path("sourceSystem").asText());
            s.setString(6, text(p.path("sourceEntity")));
            s.setString(7, p.path("statePackVersion").asText());
            s.setString(8, p.path("canonicalPayload").toString());
            String mapped = text(p.path("mappedAt"));
            s.setTimestamp(9, mapped == null ? Timestamp.from(Instant.now()) : Timestamp.from(Instant.parse(mapped)));
            s.executeUpdate();
        }
        try (var s = c.prepareStatement("insert into canonical_resident_version (canonical_resident_id,"
                + " source_record_key, version_number, supersedes_canonical_id) values (?, ?, ?, ?)"
                + " on conflict do nothing")) {
            s.setObject(1, canonical);
            s.setString(2, p.path("sourceRecordKey").asText());
            s.setInt(3, p.path("version").asInt());
            String supersedes = text(p.path("supersedesCanonicalId"));
            s.setObject(4, supersedes == null ? null : UUID.fromString(supersedes));
            s.executeUpdate();
        }
    }

    public static void familyObserved(Connection c, JsonNode envelope) throws SQLException {
        JsonNode p = envelope.path("payload");
        UUID observation = UUID.fromString(p.path("observationId").asText());
        try (var s = c.prepareStatement("select 1 from canonical_resident where canonical_resident_id = ?")) {
            s.setObject(1, UUID.fromString(p.path("canonicalResidentId").asText()));
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    // resident-created is on another topic and may come later; the family waits for it.
                    throw new gov.uddp.messaging.Inbox.NotYetApplicable("canonical Resident "
                            + p.path("canonicalResidentId").asText() + " not projected yet");
                }
            }
        }
        try (var s = c.prepareStatement("insert into canonical_family_observation (canonical_family_observation_id,"
                + " raw_record_id, batch_id, state_code, source_system, source_entity, state_pack_version,"
                + " mapping_version, resident_record_key, canonical_resident_id, observed_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict do nothing")) {
            s.setObject(1, observation);
            s.setObject(2, UUID.fromString(p.path("rawRecordId").asText()));
            s.setObject(3, UUID.fromString(p.path("batchId").asText()));
            s.setString(4, envelope.path("stateCode").asText());
            s.setString(5, p.path("sourceSystem").asText());
            s.setString(6, p.path("sourceEntity").asText());
            s.setString(7, p.path("statePackVersion").asText());
            s.setString(8, p.path("mappingVersion").asText());
            s.setString(9, p.path("residentRecordKey").asText());
            s.setObject(10, UUID.fromString(p.path("canonicalResidentId").asText()));
            s.setTimestamp(11, Timestamp.from(Instant.parse(p.path("observedAt").asText())));
            if (s.executeUpdate() == 0) {
                return;
            }
        }
        try (var s = c.prepareStatement("insert into canonical_family_membership (canonical_family_membership_id,"
                + " canonical_family_observation_id, family_record_key, source_family_id, relationship_type,"
                + " family_payload) values (?, ?, ?, ?, ?, cast(? as jsonb)) on conflict do nothing")) {
            for (JsonNode m : p.path("memberships")) {
                s.setObject(1, UUID.fromString(m.path("membershipId").asText()));
                s.setObject(2, observation);
                s.setString(3, m.path("familyRecordKey").asText());
                s.setString(4, m.path("sourceFamilyId").asText());
                s.setString(5, m.path("relationshipType").asText("UNKNOWN"));
                s.setString(6, m.path("familyPayload").isObject() ? m.path("familyPayload").toString() : "{}");
                s.addBatch();
            }
            s.executeBatch();
        }
    }

    public static void batchProcessed(Connection c, JsonNode envelope) throws SQLException {
        JsonNode p = envelope.path("payload");
        try (var s = c.prepareStatement("insert into remote_canonical_batch (batch_id, summary) values (?,"
                + " cast(? as jsonb)) on conflict do nothing")) {
            s.setObject(1, UUID.fromString(p.path("batchId").asText()));
            s.setString(2, p.toString());
            s.executeUpdate();
        }
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
