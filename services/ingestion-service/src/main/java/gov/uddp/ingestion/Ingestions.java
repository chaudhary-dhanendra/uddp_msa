package gov.uddp.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.contracts.EventEnvelope;
import gov.uddp.contracts.EventSchemas;
import gov.uddp.contracts.Topics;
import gov.uddp.messaging.Outbox;
import java.nio.file.Path;
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
 * raw-record-received per landed row and then batch-completed. Rows are announced in committed chunks of
 * {@link #CHUNK_ROWS} that walk the landing order with a keyset, so a batch of millions never sits in one transaction
 * or in memory; each chunk records the position it reached, and a batch left PUBLISHING by a crash resumes there
 * ({@link #resumePublishing()}). Replay re-publishes a batch's immutable rows as raw-record-replayed for a chosen pack version. Event ids
 * are name-based, so a rerun or redelivered command publishes nothing twice. No step looks at what a row means.
 */
@Service
public class Ingestions {

    static final String PRODUCER = "ingestion-service";
    /** Rows announced per committed chunk. */
    static final int CHUNK_ROWS = 5_000;
    private static final int NO_LINE = Integer.MAX_VALUE;

    private int chunkRows = CHUNK_ROWS;

    /** For tests: smaller chunks. */
    void setChunkRows(int rows) {
        this.chunkRows = Math.max(1, rows);
    }

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

    private gov.uddp.ingestion.connector.Connectors connectors;
    private gov.uddp.ingestion.connector.LandingFolders folders;

    @org.springframework.beans.factory.annotation.Autowired
    void setDeliveries(gov.uddp.ingestion.connector.Connectors connectors,
                       gov.uddp.ingestion.connector.LandingFolders folders) {
        this.connectors = connectors;
        this.folders = folders;
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
        Runnable delivered = () -> { };
        if ("auto".equals(request.path("delivery").asText(null)) && !template.equals("generic-db-ingest.hpl")) {
            // This service decides where the delivery comes from: the source's connector, an uploaded file, the
            // source's landing folder, or its file path, in that order.
            String state = parameters.get("STATE_CODE");
            String source = request.path("sourceSystem").asText();
            var staged = connectors == null ? java.util.Optional.<gov.uddp.ingestion.connector.Connectors.Staged>empty()
                    : connectors.stage(state, source);
            String location = request.path("location").asText(null);
            Path file = null;
            if (staged.isPresent()) {
                file = staged.get().file();
                delivered = staged.get().completed();
                template = "generic-file-ingest.hpl";
            } else if (request.hasNonNull("fileRef")) {
                file = landing.resolve(request.path("fileRef").asText());
            } else if (folders != null && folders.isLandingFolder(location)) {
                var intake = folders.next(location).orElseThrow(() -> new IllegalArgumentException(
                        "No file is waiting in the landing folder " + location));
                file = intake.file();
                delivered = intake.completed();
                if (file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
                    template = "generic-file-ingest.hpl";
                }
            }
            if (file != null) {
                parameters.remove("SOURCE_FILE");
                parameters.remove("FILE");
                if (template.contains("excel")) {
                    parameters.put("FILE", file.toString());
                } else {
                    parameters.put("SOURCE_FILE", file.toString());
                    parameters.put("CSV_HEADER", firstLine(file));
                }
            } else if (parameters.get("SOURCE_FILE") != null && parameters.get("CSV_HEADER") == null) {
                parameters.put("CSV_HEADER", firstLine(Path.of(parameters.get("SOURCE_FILE"))));
            }
        }
        if (request.hasNonNull("fileRef") && !"auto".equals(request.path("delivery").asText(null))) {
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
            // A redelivered command: a batch a crash left half announced continues where it stopped.
            if ("PUBLISHING".equals(status(batch))) {
                publish(batch);
            }
            return status(batch);
        }
        HopExecutor.Result result;
        try {
            result = secret.isEmpty() ? hop.run(template, parameters) : hop.run(template, parameters, secret);
            if (result.succeeded()) {
                delivered.run();
            }
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
        // Replayed events have name-based ids, so a replay interrupted and run again publishes nothing twice.
        int count = 0;
        Position from = Position.START;
        while (true) {
            Position start = from;
            Chunk chunk = inTransaction(c -> publishChunk(c, batch, start, correlationId, replayRun, packVersion));
            count += chunk.rows();
            if (chunk.rows() < chunkRows) {
                return count;
            }
            from = chunk.reached();
        }
    }

    /** Continues every batch a stopped process left PUBLISHING; returns how many it finished. */
    public int resumePublishing() {
        java.util.List<UUID> open = inTransaction(c -> {
            java.util.List<UUID> ids = new java.util.ArrayList<>();
            try (var s = c.prepareStatement("select batch_id from ingestion_batch where status = 'PUBLISHING'"
                    + " order by started_at"); var r = s.executeQuery()) {
                while (r.next()) {
                    ids.add(r.getObject(1, UUID.class));
                }
            }
            return ids;
        });
        open.forEach(this::publish);
        return open.size();
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
        if (!result.succeeded()) {
            inTransaction(c -> {
                try (var s = c.prepareStatement("update ingestion_batch set status = 'FAILED', completed_at = now(),"
                        + " record_count = 0, error_message = ? where batch_id = ?")) {
                    s.setString(1, result.output());
                    s.setObject(2, batch);
                    s.executeUpdate();
                }
                completed(c, batch, stateCode, correlationId, 0, "FAILED", "Generic Hop exited " + result.exitCode()
                        + ": " + result.output());
                return null;
            });
            return;
        }
        inTransaction(c -> {
            try (var s = c.prepareStatement("update ingestion_batch set status = 'PUBLISHING' where batch_id = ?")) {
                s.setObject(1, batch);
                s.executeUpdate();
            }
            return null;
        });
        publish(batch);
    }

    /** Announces the rest of a PUBLISHING batch chunk by chunk, then completes it with batch-completed. */
    private void publish(UUID batch) {
        while (true) {
            boolean done = inTransaction(c -> {
                String stateCode;
                UUID correlationId;
                Position from;
                int published;
                try (var s = c.prepareStatement("select state_code, correlation_id, published_line,"
                        + " published_record_id, published_count, status from ingestion_batch where batch_id = ?"
                        + " for update")) {
                    s.setObject(1, batch);
                    try (var r = s.executeQuery()) {
                        if (!r.next() || !"PUBLISHING".equals(r.getString(6))) {
                            return true;
                        }
                        stateCode = r.getString(1);
                        correlationId = r.getObject(2, UUID.class);
                        UUID lastId = r.getObject(4, UUID.class);
                        from = lastId == null ? Position.START : new Position(r.getInt(3), lastId);
                        published = r.getInt(5);
                    }
                }
                Chunk chunk = publishChunk(c, batch, from, correlationId, null, null);
                int total = published + chunk.rows();
                if (chunk.rows() == chunkRows) {
                    try (var s = c.prepareStatement("update ingestion_batch set published_line = ?,"
                            + " published_record_id = ?, published_count = ? where batch_id = ?")) {
                        s.setInt(1, chunk.reached().line());
                        s.setObject(2, chunk.reached().recordId());
                        s.setInt(3, total);
                        s.setObject(4, batch);
                        s.executeUpdate();
                    }
                    return false;
                }
                try (var s = c.prepareStatement("update ingestion_batch set status = 'COMPLETED', completed_at = now(),"
                        + " record_count = ?, published_count = ?, error_message = null where batch_id = ?")) {
                    s.setInt(1, total);
                    s.setInt(2, total);
                    s.setObject(3, batch);
                    s.executeUpdate();
                }
                completed(c, batch, stateCode, correlationId, total, "COMPLETED", null);
                return true;
            });
            if (done) {
                return;
            }
        }
    }

    private void completed(Connection c, UUID batch, String stateCode, UUID correlationId, int count, String status,
                           String message) throws SQLException {
        ObjectNode p = Outbox.json().createObjectNode();
        p.put("batchId", batch.toString());
        p.put("recordCount", count);
        p.put("status", status);
        p.put("message", message);
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
    }

    /** A place in the landing order: the last announced row's line (rows without one sort last) and id. */
    private record Position(int line, UUID recordId) {
        static final Position START = new Position(Integer.MIN_VALUE, new UUID(Long.MIN_VALUE, Long.MIN_VALUE));
    }

    private record Chunk(int rows, Position reached) {
    }

    /**
     * Up to one chunk of rows after {@code from}, in landing order, as raw-record-received events (replayRun non-null:
     * raw-record-replayed for that pack version).
     */
    private Chunk publishChunk(Connection c, UUID batch, Position from, UUID correlationId, UUID replayRun,
                               String replayPackVersion) throws SQLException {
        int count = 0;
        Position reached = from;
        String order = "coalesce(r.source_line, " + NO_LINE + ")";
        try (var s = c.prepareStatement("select r.raw_record_id, r.state_code, r.source_system, r.source_line,"
                + " r.pipeline_template, r.raw_payload, r.ingested_at, r.source_record_id, r.source_metadata::text,"
                + " r.state_pack_version, r.source_schema_version, b.source_type, " + order + " from raw_record r"
                + " join ingestion_batch b on b.batch_id = r.batch_id where r.batch_id = ?"
                + " and (" + order + ", r.raw_record_id) > (?, ?)"
                + " order by " + order + ", r.raw_record_id limit ?")) {
            s.setObject(1, batch);
            s.setInt(2, from.line());
            s.setObject(3, from.recordId());
            s.setInt(4, chunkRows);
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
                    reached = new Position(r.getInt(13), raw);
                    count++;
                }
            }
        }
        return new Chunk(count, reached);
    }

    /** The header line of a delivered CSV file, as the generic file template expects it (CSV_HEADER). */
    private static String firstLine(Path file) {
        try (var lines = java.nio.file.Files.lines(file, java.nio.charset.StandardCharsets.UTF_8)) {
            String first = lines.findFirst().orElseThrow(() -> new IllegalArgumentException("Empty file " + file));
            return first.startsWith("\uFEFF") ? first.substring(1) : first;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Cannot read " + file.getFileName(), e);
        }
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
