package gov.uddp.core.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.contracts.EventEnvelope;
import gov.uddp.contracts.Topics;
import gov.uddp.core.canonical.CanonicalProcessingError;
import gov.uddp.core.canonical.CanonicalProcessingSummary;
import gov.uddp.core.canonical.CanonicalResidentRecord;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.golden.GoldenBuildSummary;
import gov.uddp.messaging.Outbox;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The monolith's event port (MSA Phase 2, docs/msa-design.md): writes the catalogue events to {@code outbox_event} on
 * the caller's transaction, from the existing code paths. It only adds outbox rows and never changes what the
 * pipeline does. Off unless {@code uddp.events.enabled=true}; when off every method returns at once.
 *
 * <p>Events derived from immutable rows (raw records, match pairs, a committed Golden build) use deterministic ids,
 * so publishing them again (a rerun, a resumed stage, a second caller) adds nothing.
 */
@Component
public class PlatformEvents {

    private static final String PRODUCER = "common-core";

    private final PostgresConnectionFactory connections;
    private final boolean enabled;

    public PlatformEvents(PostgresConnectionFactory connections,
                          @Value("${uddp.events.enabled:false}") boolean enabled) {
        this.connections = connections;
        this.enabled = enabled;
    }

    private boolean remoteIdentity;

    private boolean remoteIngestion;

    public boolean enabled() {
        return enabled;
    }

    /** With ingestion-service (MSA Phase 3) Raw events are its events; this service must not republish them. */
    @org.springframework.beans.factory.annotation.Autowired
    public void setRemoteIngestion(@Value("${uddp.ingestion.remote:false}") boolean remoteIngestion) {
        this.remoteIngestion = remoteIngestion;
    }

    /** With identity-service (MSA Phase 3) matching runs are its events; this service must not republish them. */
    @org.springframework.beans.factory.annotation.Autowired
    public void setRemoteIdentity(@Value("${uddp.identity.remote:false}") boolean remoteIdentity) {
        this.remoteIdentity = remoteIdentity;
    }

    /** ingestion: one raw-record-received per landed row of the batch, then batch-completed. Idempotent. */
    public void rawBatchLanded(UUID batchId) {
        if (!enabled || remoteIngestion) {
            return;
        }
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                int count = 0;
                String stateCode = null;
                try (var s = c.prepareStatement("select raw_record_id, state_code, source_system, source_line,"
                        + " pipeline_template, raw_payload, ingested_at from raw_record where batch_id = ?"
                        + " order by ingested_at, raw_record_id")) {
                    s.setObject(1, batchId);
                    try (var r = s.executeQuery()) {
                        while (r.next()) {
                            UUID rawId = r.getObject(1, UUID.class);
                            stateCode = r.getString(2);
                            ObjectNode p = object();
                            p.put("rawRecordId", rawId.toString());
                            p.put("batchId", batchId.toString());
                            p.put("sourceSystem", r.getString(3));
                            Integer line = (Integer) r.getObject(4);
                            p.put("sourceLine", line);
                            p.put("payloadFormat", payloadFormat(r.getString(5)));
                            p.put("rawPayload", r.getString(6));
                            p.put("landedAt", r.getObject(7, OffsetDateTime.class).toInstant().toString());
                            p.putNull("replayRunId");
                            append(c, Topics.RAW_RECORD_RECEIVED, "uddp.ingestion.raw-record-received", rawId.toString(),
                                    r.getString(3) + ":" + rawId, stateCode, batchId, p);
                            count++;
                        }
                    }
                }
                ObjectNode done = object();
                done.put("batchId", batchId.toString());
                done.put("recordCount", count);
                done.put("status", "LANDED");
                append(c, Topics.BATCH_COMPLETED, "uddp.ingestion.batch-completed", batchId.toString(),
                        batchId.toString(), stateCode, batchId, done);
                c.commit();
            } finally {
                // No explicit rollback: closing without a commit discards the writes, and inside a bound transaction
                // (PostgresConnectionFactory.inTransaction) the caller's own commit or rollback decides.
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot publish the raw records of batch " + batchId, e);
        }
    }

    /** canonical: a new canonical version of one source record, on the transaction that wrote it. */
    public void residentCreated(Connection c, CanonicalResidentRecord r, String sourceRecordKey, int version,
                                UUID supersedes) throws SQLException {
        if (!enabled) {
            return;
        }
        ObjectNode p = object();
        p.put("canonicalResidentId", r.canonicalResidentId().toString());
        p.put("sourceRecordKey", sourceRecordKey);
        p.put("version", version);
        p.put("supersedesCanonicalId", supersedes == null ? null : supersedes.toString());
        p.put("rawRecordId", r.rawRecordId().toString());
        p.put("batchId", r.batchId().toString());
        p.put("sourceSystem", r.sourceSystem());
        p.put("sourceEntity", r.sourceEntity());
        p.put("statePackVersion", r.statePackVersion());
        p.put("mappedAt", r.mappedAt() == null ? null : r.mappedAt().toInstant().toString());
        p.set("canonicalPayload", readTree(r.canonicalPayload()));
        append(c, Topics.RESIDENT_CREATED, "uddp.canonical.resident-created", r.canonicalResidentId().toString(),
                sourceRecordKey, r.stateCode(), r.batchId(), p);
    }

    /** canonical DLQ: a business-invalid record, on the transaction that recorded the failure. */
    public void recordRejected(Connection c, CanonicalProcessingError e) throws SQLException {
        if (!enabled || e.rawRecordId() == null || e.batchId() == null) {
            return;
        }
        ObjectNode p = object();
        p.put("rawRecordId", e.rawRecordId().toString());
        p.put("batchId", e.batchId().toString());
        p.put("statePackVersion", e.statePackVersion());
        p.put("packId", e.packId());
        p.put("sourceId", e.sourceId());
        ArrayNode errors = p.putArray("errors");
        ObjectNode error = errors.addObject();
        error.put("ruleId", e.fix() == null || e.fix().key() == null ? "processing" : e.fix().key());
        error.putNull("field");
        error.put("reasonCode", e.reasonCode() == null ? "OTHER" : e.reasonCode());
        error.put("message", e.message());
        append(c, Topics.CANONICAL_DLQ, "uddp.canonical.record-rejected", null,
                (e.sourceId() == null ? "source" : e.sourceId()) + ":" + e.rawRecordId(), null, e.batchId(), p);
    }

    /** canonical: the batch has been processed (counts only). */
    public void batchProcessed(CanonicalProcessingSummary summary) {
        if (!enabled) {
            return;
        }
        ObjectNode p = object();
        p.put("batchId", summary.batchId().toString());
        p.put("recordsRead", summary.recordsRead());
        p.put("recordsMapped", summary.recordsMapped());
        p.put("recordsSkipped", summary.recordsSkipped());
        p.put("recordsFailed", summary.recordsFailed());
        p.put("recordsHeld", summary.recordsHeld());
        inOwnTransaction(c -> append(c, Topics.BATCH_PROCESSED, "uddp.canonical.batch-processed", null,
                summary.batchId().toString(), null, summary.batchId(), p));
    }

    /**
     * identity: match-decided for every pair, cluster-assigned for every member and match-run-completed for a
     * completed matching run, all in one transaction. Idempotent, so both the onboarding stage and the Golden build
     * may call it for the same run.
     */
    public void matchRunCompleted(UUID runId) {
        if (!enabled || remoteIdentity) {
            return;
        }
        inOwnTransaction(c -> {
            String status = null;
            String mode = null;
            String model = null;
            try (var s = c.prepareStatement("select status, mode, model_version from matching_run where run_id = ?")) {
                s.setObject(1, runId);
                try (var r = s.executeQuery()) {
                    if (r.next()) {
                        status = r.getString(1);
                        mode = r.getString(2);
                        model = r.getString(3);
                    }
                }
            }
            if (!"COMPLETED".equals(status)) {
                return;
            }
            int pairs = 0;
            try (var s = c.prepareStatement("select p.canonical_record_a, p.canonical_record_b, p.match_probability,"
                    + " p.match_weight, p.decision, p.evidence::text, coalesce(v.source_record_key,"
                    + " p.canonical_record_a::text) from resident_match_pair p left join canonical_resident_version v"
                    + " on v.canonical_resident_id = p.canonical_record_a where p.run_id = ?"
                    + " order by p.canonical_record_a, p.canonical_record_b")) {
                s.setObject(1, runId);
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        ObjectNode p = object();
                        p.put("matchingRunId", runId.toString());
                        p.put("canonicalRecordA", r.getString(1));
                        p.put("canonicalRecordB", r.getString(2));
                        p.put("matchProbability", r.getBigDecimal(3));
                        p.put("matchWeight", r.getBigDecimal(4));
                        p.put("decision", r.getString(5));
                        p.set("evidence", readTree(r.getString(6)));
                        append(c, Topics.MATCH_DECIDED, "uddp.identity.match-decided",
                                runId + "|" + r.getString(1) + "|" + r.getString(2), r.getString(7), null, runId, p);
                        pairs++;
                    }
                }
            }
            int members = 0;
            try (var s = c.prepareStatement("select m.canonical_record_id, m.cluster_id, coalesce(v.source_record_key,"
                    + " m.canonical_record_id::text) from resident_match_cluster_member m left join"
                    + " canonical_resident_version v on v.canonical_resident_id = m.canonical_record_id"
                    + " where m.run_id = ? order by m.canonical_record_id")) {
                s.setObject(1, runId);
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        ObjectNode p = object();
                        p.put("matchingRunId", runId.toString());
                        p.put("canonicalRecordId", r.getString(1));
                        p.put("clusterId", r.getString(2));
                        append(c, Topics.CLUSTER_ASSIGNED, "uddp.identity.cluster-assigned",
                                runId + "|" + r.getString(1), r.getString(3), null, runId, p);
                        members++;
                    }
                }
            }
            ObjectNode p = object();
            p.put("matchingRunId", runId.toString());
            p.put("mode", mode);
            p.put("modelVersion", model);
            p.put("pairCount", pairs);
            p.put("clusterMemberCount", members);
            append(c, Topics.MATCH_RUN_COMPLETED, "uddp.identity.match-run-completed", runId.toString(),
                    runId.toString(), null, runId, p);
        });
    }

    /**
     * registry: golden-resident-changed for every identity the build touched and golden-build-completed, on the build
     * transaction just before it commits.
     */
    public void goldenBuildCommitting(Connection c, UUID buildId, GoldenBuildSummary summary) throws SQLException {
        if (!enabled) {
            return;
        }
        Map<UUID, List<String>> changes = new LinkedHashMap<>();
        try (var s = c.prepareStatement("select golden_resident_id, event_type from golden_resident_event"
                + " where golden_build_id = ? order by event_sequence")) {
            s.setObject(1, buildId);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    var types = changes.computeIfAbsent(r.getObject(1, UUID.class), id -> new ArrayList<>());
                    if (!types.contains(r.getString(2))) {
                        types.add(r.getString(2));
                    }
                }
            }
        }
        for (var entry : changes.entrySet()) {
            UUID goldenId = entry.getKey();
            ObjectNode p = object();
            p.put("goldenResidentId", goldenId.toString());
            p.put("goldenBuildId", buildId.toString());
            p.put("matchingRunId", summary.matchingRunId() == null ? null : summary.matchingRunId().toString());
            p.put("changeType", changeType(entry.getValue()));
            entry.getValue().forEach(p.putArray("changes")::add);
            try (var s = c.prepareStatement("select g.status, g.merged_into_id, g.split_from_id,"
                    + " (select max(v.version_number) from golden_resident_version v"
                    + " where v.golden_resident_id = g.golden_resident_id),"
                    + " (select u.uddp_id from uddp_id u where u.kind = 'RESIDENT' and u.golden_id = g.golden_resident_id)"
                    + " from golden_resident g where g.golden_resident_id = ?")) {
                s.setObject(1, goldenId);
                try (var r = s.executeQuery()) {
                    if (r.next()) {
                        p.put("status", r.getString(1));
                        p.put("mergedIntoId", r.getString(2));
                        p.put("splitFromId", r.getString(3));
                        p.put("versionNo", (Integer) r.getObject(4));
                        p.put("uddpId", r.getString(5));
                    }
                }
            }
            ArrayNode members = p.putArray("members");
            try (var s = c.prepareStatement("select source_record_key, canonical_resident_id from"
                    + " golden_resident_membership where golden_resident_id = ? and effective_to is null"
                    + " order by source_record_key")) {
                s.setObject(1, goldenId);
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        members.addObject().put("sourceRecordKey", r.getString(1))
                                .put("canonicalResidentId", r.getString(2));
                    }
                }
            }
            append(c, Topics.GOLDEN_RESIDENT_CHANGED, "uddp.registry.golden-resident-changed",
                    buildId + "|" + goldenId, goldenId.toString(), null, summary.matchingRunId(), p);
        }
        ObjectNode done = Outbox.json().valueToTree(summary);
        done.put("goldenBuildId", buildId.toString());
        done.put("identitiesChanged", changes.size());
        append(c, Topics.GOLDEN_BUILD_COMPLETED, "uddp.registry.golden-build-completed", buildId.toString(),
                buildId.toString(), null, summary.matchingRunId(), done);
    }

    private static String changeType(List<String> types) {
        for (String type : List.of("MERGED", "SPLIT", "RETIRED", "CREATED")) {
            if (types.contains(type)) {
                return type;
            }
        }
        return "UPDATED";
    }

    private static String payloadFormat(String pipelineTemplate) {
        String template = pipelineTemplate == null ? "" : pipelineTemplate.toLowerCase();
        if (template.contains("excel")) {
            return "XLSX_ROW";
        }
        if (template.contains("-db-")) {
            return "DB_ROW";
        }
        return "CSV_ROW";
    }

    /** Appends one event; {@code idSource} non-null gives a deterministic event id. */
    private void append(Connection c, String topic, String eventType, String idSource, String key, String stateCode,
                        UUID correlationId, ObjectNode payload) throws SQLException {
        UUID id = idSource == null ? UUID.randomUUID() : Outbox.deterministicId(eventType, idSource);
        Outbox.append(c, topic, new EventEnvelope(id, eventType, 1, Instant.now(), PRODUCER, key, correlationId, null,
                stateCode, null, payload));
    }

    private interface Work {
        void run(Connection c) throws Exception;
    }

    private void inOwnTransaction(Work work) {
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                work.run(c);
                c.commit();
            } finally {
                // See rawBatchLanded: no explicit rollback.
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot write platform events to the outbox", e);
        }
    }

    private static ObjectNode object() {
        return Outbox.json().createObjectNode();
    }

    private static JsonNode readTree(String text) {
        try {
            return text == null ? Outbox.json().createObjectNode() : Outbox.json().readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read stored JSON", e);
        }
    }
}
