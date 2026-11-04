package gov.uddp.core.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Projects ingestion-service's Raw records into this database's raw_record (MSA Phase 3) with the same ids, so
 * canonical processing, held records and lineage read them unchanged. A record may arrive before its batch row
 * exists (another path than onboarding), so the first one creates it from the record's own batch fields. Replayed
 * records are the same immutable rows and add nothing. Each handler runs on the inbox transaction.
 */
public final class RemoteRawProjection {

    private RemoteRawProjection() {
    }

    public static void rawRecordReceived(Connection c, JsonNode envelope) throws SQLException {
        JsonNode p = envelope.path("payload");
        UUID batch = UUID.fromString(p.path("batchId").asText());
        String stateCode = envelope.path("stateCode").asText();
        try (var s = c.prepareStatement("insert into ingestion_batch (batch_id, state_code, source_system, source_type,"
                + " source_schema_version, state_pack_version, pipeline_template, status)"
                + " values (?, ?, ?, ?, ?, ?, ?, 'STARTED') on conflict do nothing")) {
            s.setObject(1, batch);
            s.setString(2, stateCode);
            s.setString(3, p.path("sourceSystem").asText());
            s.setString(4, p.path("sourceType").asText("FILE"));
            s.setString(5, text(p.path("sourceSchemaVersion")));
            s.setString(6, p.path("statePackVersion").asText());
            s.setString(7, p.path("pipelineTemplate").asText());
            s.executeUpdate();
        }
        try (var s = c.prepareStatement("insert into raw_record (raw_record_id, batch_id, state_code, source_system,"
                + " source_record_id, ingested_at, raw_payload, source_metadata, state_pack_version,"
                + " source_schema_version, pipeline_template, source_line)"
                + " values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?) on conflict do nothing")) {
            s.setObject(1, UUID.fromString(p.path("rawRecordId").asText()));
            s.setObject(2, batch);
            s.setString(3, stateCode);
            s.setString(4, p.path("sourceSystem").asText());
            s.setString(5, text(p.path("sourceRecordId")));
            s.setTimestamp(6, Timestamp.from(Instant.parse(p.path("landedAt").asText())));
            s.setString(7, p.path("rawPayload").asText());
            s.setString(8, p.path("sourceMetadata").isObject() ? p.path("sourceMetadata").toString() : "{}");
            s.setString(9, p.path("statePackVersion").asText());
            s.setString(10, text(p.path("sourceSchemaVersion")));
            s.setString(11, p.path("pipelineTemplate").asText());
            s.setObject(12, p.path("sourceLine").isInt() ? p.path("sourceLine").asInt() : null, java.sql.Types.INTEGER);
            s.executeUpdate();
        }
    }

    public static void batchCompleted(Connection c, JsonNode envelope) throws SQLException {
        JsonNode p = envelope.path("payload");
        if ("LANDED".equals(p.path("status").asText())) {
            return; // this service's own Phase 2 announcement, not ingestion-service's
        }
        try (var s = c.prepareStatement("update ingestion_batch set remote_status = ?, remote_record_count = ?,"
                + " remote_message = ? where batch_id = ?")) {
            s.setString(1, p.path("status").asText());
            s.setInt(2, p.path("recordCount").asInt());
            s.setString(3, text(p.path("message")));
            s.setObject(4, UUID.fromString(p.path("batchId").asText()));
            s.executeUpdate();
        }
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }
}
