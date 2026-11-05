package gov.uddp.core.geography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.contracts.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Replicates the LGD geography master between services (MSA). The edge, where LGD snapshots are uploaded and synced,
 * publishes every snapshot that changed its master as {@code geography.snapshot-published} events, in ordered chunks
 * (a full LGD snapshot is far larger than one Kafka message). canonical-service and statepack-service keep the chunks
 * until all have arrived and then import the whole snapshot into their own master with the same
 * {@link LgdImportService}, so the three masters hold the same entities, versions and relationships. Re-importing a
 * snapshot changes nothing, so redelivery is harmless.
 */
public final class GeographyReplication {

    public static final String EVENT_TYPE = "uddp.geography.snapshot-published";
    /** Rows per event, well under the broker's message limit. */
    public static final int CHUNK_ROWS = 2000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private GeographyReplication() {
    }

    /** The events of one snapshot, in order; their ids follow from the snapshot and chunk, so a re-publish is a no-op. */
    public static List<EventEnvelope> envelopes(UUID snapshotId, LgdSnapshotData data, String producer, int chunkRows) {
        List<LgdRow> rows = data.rows();
        int chunks = Math.max(1, (rows.size() + chunkRows - 1) / chunkRows);
        List<EventEnvelope> out = new ArrayList<>();
        for (int chunk = 0; chunk < chunks; chunk++) {
            ObjectNode payload = JSON.createObjectNode();
            payload.put("snapshotId", snapshotId.toString());
            payload.put("sourceLabel", data.sourceLabel());
            payload.put("asOf", data.asOf().toString());
            payload.put("chunk", chunk);
            payload.put("chunks", chunks);
            ArrayNode array = payload.putArray("rows");
            for (LgdRow row : rows.subList(chunk * chunkRows, Math.min(rows.size(), (chunk + 1) * chunkRows))) {
                ObjectNode r = array.addObject();
                r.put("lineNo", row.lineNo());
                r.put("entityType", row.entityType());
                r.put("code", row.code());
                r.put("name", row.name());
                r.put("status", row.status());
                r.put("parentType", row.parentType());
                r.put("parentCode", row.parentCode());
                r.put("effectiveFrom", row.effectiveFrom() == null ? null : row.effectiveFrom().toString());
            }
            UUID eventId = UUID.nameUUIDFromBytes((EVENT_TYPE + "|" + snapshotId + "|" + chunk)
                    .getBytes(StandardCharsets.UTF_8));
            out.add(new EventEnvelope(eventId, EVENT_TYPE, 1, Instant.now(), producer, snapshotId.toString(), snapshotId,
                    null, null, null, payload));
        }
        return out;
    }

    /**
     * Keeps one chunk; once every chunk of its snapshot is here, imports the snapshot and forgets the chunks. Returns
     * the import's status, or null while chunks are still missing.
     */
    public static String apply(Connection c, JsonNode envelope, LgdImportService imports) throws SQLException {
        JsonNode p = envelope.path("payload");
        UUID snapshot = UUID.fromString(p.path("snapshotId").asText());
        int chunks = p.path("chunks").asInt();
        try (var s = c.prepareStatement("insert into lgd_snapshot_chunk (snapshot_id, chunk, chunks, source_label, as_of,"
                + " rows) values (?, ?, ?, ?, ?, cast(? as jsonb)) on conflict do nothing")) {
            s.setObject(1, snapshot);
            s.setInt(2, p.path("chunk").asInt());
            s.setInt(3, chunks);
            s.setString(4, p.path("sourceLabel").asText());
            s.setObject(5, LocalDate.parse(p.path("asOf").asText()));
            s.setString(6, p.path("rows").toString());
            s.executeUpdate();
        }
        List<LgdRow> rows = new ArrayList<>();
        int received = 0;
        try (var s = c.prepareStatement("select rows::text from lgd_snapshot_chunk where snapshot_id = ? order by chunk")) {
            s.setObject(1, snapshot);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    received++;
                    for (JsonNode row : readTree(r.getString(1))) {
                        rows.add(new LgdRow(row.path("lineNo").asInt(), text(row, "entityType"), text(row, "code"),
                                text(row, "name"), text(row, "status"), text(row, "parentType"),
                                text(row, "parentCode"), row.hasNonNull("effectiveFrom")
                                        ? LocalDate.parse(row.get("effectiveFrom").asText()) : null));
                    }
                }
            }
        }
        if (received < chunks) {
            return null;
        }
        var result = imports.importSnapshot(new LgdSnapshotData(p.path("sourceLabel").asText(),
                LocalDate.parse(p.path("asOf").asText()), rows), "lgd-replication");
        if ("FAILED".equals(result.status())) {
            throw new IllegalStateException("The replicated LGD snapshot " + snapshot + " did not import: "
                    + result.errors());
        }
        try (var s = c.prepareStatement("delete from lgd_snapshot_chunk where snapshot_id = ?")) {
            s.setObject(1, snapshot);
            s.executeUpdate();
        }
        return result.status();
    }

    /** The chunk table each consuming service adds to its schema. */
    public static final String DDL = """
            CREATE TABLE IF NOT EXISTS lgd_snapshot_chunk (
              snapshot_id uuid NOT NULL,
              chunk integer NOT NULL,
              chunks integer NOT NULL,
              source_label text NOT NULL,
              as_of date NOT NULL,
              rows jsonb NOT NULL,
              received_at timestamptz NOT NULL DEFAULT now(),
              PRIMARY KEY (snapshot_id, chunk)
            );
            """;

    private static JsonNode readTree(String text) {
        try {
            return JSON.readTree(text);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(JsonNode row, String field) {
        return row.hasNonNull(field) ? row.get(field).asText() : null;
    }
}
