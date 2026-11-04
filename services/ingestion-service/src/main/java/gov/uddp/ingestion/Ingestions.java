package gov.uddp.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.contracts.EventEnvelope;
import gov.uddp.contracts.EventSchemas;
import gov.uddp.contracts.Topics;
import gov.uddp.messaging.Outbox;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/**
 * Lands a batch with a generic Hop template and announces it (docs/msa-design.md, sections 3.3 and 7): one
 * raw-record-received per landed row and then batch-completed, all written to the outbox in one transaction after Hop
 * finished. Replay re-publishes a batch's immutable rows as raw-record-replayed for a chosen pack version. Event ids
 * are name-based, so a rerun or redelivered command publishes nothing twice. No step looks at what a row means.
 */
@Service
public class Ingestions {

    static final String PRODUCER = "ingestion-service";

    private final HopExecutor hop;
    private final DataSource dataSource;
    private LandingFiles landing;
    private SourceConnections connections;

    public Ingestions(HopExecutor hop, DataSource dataSource) {
        this.hop = hop;
        this.dataSource = dataSource;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setSources(LandingFiles landing, SourceConnections connections) {
        this.landing = landing;
        this.connections = connections;
    }

    /** Runs a {@code cmd.ingestion.ingest-requested} payload; returns the batch's status. */
    public String ingest(JsonNode request, UUID correlationId) {
        var problems = EventSchemas.validate("cmd.ingestion.ingest-requested.v1", request);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Invalid ingest request: " + problems);
        }
        UUID batch = UUID.fromString(request.path("batchId").asText());
        Map<String, String> parameters = new LinkedHashMap<>();
        request.path("parameters").properties().forEach(e -> parameters.put(e.getKey(),
                e.getValue().isNull() ? null : e.getValue().asText()));
        parameters.put("BATCH_ID", batch.toString());
        String template = request.path("pipelineTemplate").asText();
        Map<String, String> secret = new LinkedHashMap<>();
        if (request.hasNonNull("fileRef")) {
            // An uploaded file: its landed copy replaces any path the caller knew.
            String file = landing.resolve(request.path("fileRef").asText()).toString();
            parameters.remove("SOURCE_FILE");
            parameters.remove("FILE");
            parameters.put(template.contains("excel") ? "FILE" : "SOURCE_FILE", file);
        }
        if (template.equals("generic-db-ingest.hpl")) {
            // A database source: its connection and credentials are this service's own configuration.
            String ref = request.path("connectionRef").asText(null);
            String table = request.path("table").asText(null);
            String query = request.path("query").asText(null);
            parameters.put("CSV_HEADER", connections.header(ref, gov.uddp.contracts.SourceSql.select(table, query)));
            secret.putAll(connections.hopSettings(ref));
            secret.put("UDDP_SOURCE_SQL", gov.uddp.contracts.SourceSql.rawLineSelect(table, query));
        }
        String stateCode = parameters.get("STATE_CODE");
        if (!startBatch(request, batch, stateCode, correlationId)) {
            return status(batch);
        }
        HopExecutor.Result result;
        try {
            result = secret.isEmpty() ? hop.run(template, parameters) : hop.run(template, parameters, secret);
        } catch (RuntimeException e) {
            result = new HopExecutor.Result(-1, e.getMessage());
        }
        finish(batch, stateCode, correlationId, result);
        return status(batch);
    }

    /** Runs a {@code cmd.ingestion.replay-requested} payload: re-publishes the batch's rows, never rewriting them. */
    public int replay(JsonNode request, UUID correlationId) {
        UUID batch = UUID.fromString(request.path("batchId").asText());
        UUID replayRun = UUID.fromString(request.path("replayRunId").asText());
        String packVersion = request.path("statePackVersion").asText();
        return inTransaction(c -> publishRows(c, batch, correlationId, replayRun, packVersion));
    }

    /** Creates the batch; false when this batch already exists (a redelivered command), which then changes nothing. */
    private boolean startBatch(JsonNode request, UUID batch, String stateCode, UUID correlationId) {
        return inTransaction(c -> {
            try (var s = c.prepareStatement("insert into ingestion_batch (batch_id, state_code, source_system,"
                    + " source_type, source_schema_version, state_pack_version, pipeline_template, status,"
                    + " correlation_id) values (?, ?, ?, ?, ?, ?, ?, 'STARTED', ?) on conflict do nothing")) {
                s.setObject(1, batch);
                s.setString(2, stateCode);
                s.setString(3, request.path("sourceSystem").asText());
                s.setString(4, request.path("sourceType").asText());
                s.setString(5, request.path("sourceSchemaVersion").asText(null));
                s.setString(6, request.path("statePackVersion").asText());
                s.setString(7, request.path("pipelineTemplate").asText());
                s.setObject(8, correlationId);
                return s.executeUpdate() == 1 ? 1 : 0;
            }
        }) == 1;
    }

    private void finish(UUID batch, String stateCode, UUID correlationId, HopExecutor.Result result) {
        inTransaction(c -> {
            int count = result.succeeded() ? publishRows(c, batch, correlationId, null, null) : 0;
            try (var s = c.prepareStatement("update ingestion_batch set status = ?, completed_at = now(),"
                    + " record_count = ?, error_message = ? where batch_id = ?")) {
                s.setString(1, result.succeeded() ? "COMPLETED" : "FAILED");
                s.setInt(2, count);
                s.setString(3, result.succeeded() ? null : result.output());
                s.setObject(4, batch);
                s.executeUpdate();
            }
            ObjectNode p = Outbox.json().createObjectNode();
            p.put("batchId", batch.toString());
            p.put("recordCount", count);
            p.put("status", result.succeeded() ? "COMPLETED" : "FAILED");
            p.put("message", result.succeeded() ? null : "Generic Hop exited " + result.exitCode() + ": "
                    + result.output());
            // The batch's own fields, so a consumer that sees this report before any record can record it.
            try (var s = c.prepareStatement("select source_system, source_type, source_schema_version,"
                    + " state_pack_version, pipeline_template from ingestion_batch where batch_id = ?")) {
                s.setObject(1, batch);
                try (var r = s.executeQuery()) {
                    if (r.next()) {
                        p.put("sourceSystem", r.getString(1));
                        p.put("sourceType", r.getString(2));
                        p.put("sourceSchemaVersion", r.getString(3));
                        p.put("statePackVersion", r.getString(4));
                        p.put("pipelineTemplate", r.getString(5));
                    }
                }
            }
            append(c, Topics.BATCH_COMPLETED, "uddp.ingestion.batch-completed", batch.toString(), batch.toString(),
                    stateCode, correlationId, p);
            return count;
        });
    }

    /** One event per row of the batch, in landing order; replayRun non-null publishes raw-record-replayed. */
    private int publishRows(Connection c, UUID batch, UUID correlationId, UUID replayRun, String replayPackVersion)
            throws SQLException {
        int count = 0;
        try (var s = c.prepareStatement("select r.raw_record_id, r.state_code, r.source_system, r.source_line,"
                + " r.pipeline_template, r.raw_payload, r.ingested_at, r.source_record_id, r.source_metadata::text,"
                + " r.state_pack_version, r.source_schema_version, b.source_type from raw_record r"
                + " join ingestion_batch b on b.batch_id = r.batch_id where r.batch_id = ?"
                + " order by r.source_line nulls last, r.ingested_at, r.raw_record_id")) {
            s.setObject(1, batch);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    UUID raw = r.getObject(1, UUID.class);
                    ObjectNode p = Outbox.json().createObjectNode();
                    p.put("rawRecordId", raw.toString());
                    p.put("batchId", batch.toString());
                    p.put("sourceSystem", r.getString(3));
                    p.put("sourceLine", (Integer) r.getObject(4));
                    p.put("payloadFormat", payloadFormat(r.getString(5)));
                    p.put("rawPayload", r.getString(6));
                    p.put("landedAt", r.getObject(7, OffsetDateTime.class).toInstant().toString());
                    p.put("replayRunId", replayRun == null ? null : replayRun.toString());
                    p.put("sourceRecordId", r.getString(8));
                    p.set("sourceMetadata", readTree(r.getString(9)));
                    p.put("statePackVersion", replayPackVersion == null ? r.getString(10) : replayPackVersion);
                    p.put("sourceSchemaVersion", r.getString(11));
                    p.put("pipelineTemplate", r.getString(5));
                    p.put("sourceType", r.getString(12));
                    if (replayRun == null) {
                        append(c, Topics.RAW_RECORD_RECEIVED, "uddp.ingestion.raw-record-received", raw.toString(),
                                r.getString(3) + ":" + raw, r.getString(2), correlationId, p);
                    } else {
                        append(c, Topics.RAW_RECORD_REPLAYED, "uddp.ingestion.raw-record-replayed",
                                replayRun + "|" + raw, r.getString(3) + ":" + raw, r.getString(2), correlationId, p);
                    }
                    count++;
                }
            }
        }
        return count;
    }

    private String status(UUID batch) {
        return inTransaction(c -> {
            try (var s = c.prepareStatement("select status from ingestion_batch where batch_id = ?")) {
                s.setObject(1, batch);
                try (var r = s.executeQuery()) {
                    return r.next() ? r.getString(1) : null;
                }
            }
        });
    }

    private static String payloadFormat(String template) {
        String t = template == null ? "" : template.toLowerCase();
        return t.contains("excel") ? "XLSX_ROW" : t.contains("-db-") ? "DB_ROW" : "CSV_ROW";
    }

    private static void append(Connection c, String topic, String eventType, String idSource, String key,
                               String stateCode, UUID correlationId, ObjectNode payload) throws SQLException {
        Outbox.append(c, topic, new EventEnvelope(Outbox.deterministicId(eventType, idSource), eventType, 1,
                Instant.now(), PRODUCER, key, correlationId, null, stateCode, null, payload));
    }

    private static JsonNode readTree(String text) {
        try {
            return text == null ? null : Outbox.json().readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read stored source metadata", e);
        }
    }

    private interface Work<T> {
        T run(Connection c) throws Exception;
    }

    private <T> T inTransaction(Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Ingestion database work failed", e);
        }
    }
}
