package gov.uddp.registry;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.events.RemoteMatchingProjection;
import gov.uddp.messaging.Inbox;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * registry-service's projections (docs/msa-design.md, section 6.1), written with the monolith's table names so the
 * unchanged Golden build reads them: canonical Residents and their versions, family observations and memberships,
 * and identity's matching runs (through the monolith's own RemoteMatchingProjection). Every applied event is also
 * recorded in lineage.lineage_event.
 */
public final class RegistryProjections {

    private RegistryProjections() {
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
        lineage(c, envelope, p.path("sourceRecordKey").asText());
    }

    public static void familyObserved(Connection c, JsonNode envelope) throws SQLException {
        JsonNode p = envelope.path("payload");
        UUID observation = UUID.fromString(p.path("observationId").asText());
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
        lineage(c, envelope, p.path("residentRecordKey").asText());
    }

    /** identity's run events, into the same matching tables and RECEIVING-until-complete rule as the monolith. */
    public static Inbox.Handler matching(Inbox.Handler projection, String subjectField) {
        return (c, envelope) -> {
            projection.apply(c, envelope);
            lineage(c, envelope, envelope.path("payload").path(subjectField).asText());
        };
    }

    static final Inbox.Handler MATCH_DECIDED = matching(RemoteMatchingProjection::matchDecided, "matchingRunId");
    static final Inbox.Handler CLUSTER_ASSIGNED = matching(RemoteMatchingProjection::clusterAssigned, "matchingRunId");
    static final Inbox.Handler MATCH_RUN_COMPLETED = matching(RemoteMatchingProjection::matchRunCompleted,
            "matchingRunId");

    private static void lineage(Connection c, JsonNode envelope, String subject) throws SQLException {
        try (var s = c.prepareStatement("insert into lineage.lineage_event (event_id, event_type, subject_key,"
                + " correlation_id, producer) values (?, ?, ?, ?, ?) on conflict do nothing")) {
            s.setObject(1, UUID.fromString(envelope.path("eventId").asText()));
            s.setString(2, envelope.path("eventType").asText());
            s.setString(3, subject);
            String correlation = text(envelope.path("correlationId"));
            s.setObject(4, correlation == null || correlation.isBlank() ? null : UUID.fromString(correlation));
            s.setString(5, envelope.path("producer").asText());
            s.executeUpdate();
        }
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
