package gov.uddp.core.events;

import gov.uddp.messaging.RowReplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.messaging.RowReplication.Selection;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything else canonical processing writes for a batch besides Residents and families (published one by one):
 * benefit, application and asset observations with the benefits, applications and assets they touched, DQ results,
 * and the processing errors and warnings behind the rejected-record reports. canonical-service publishes them as one
 * canonical.batch-observed event per batch; the edge applies them row for row, so its benefit, scheme and quality
 * reports read the same rows as in the monolith.
 */
public final class CanonicalBatchRows {

    /** Parents first. */
    public static final List<String> ORDER = List.of("dq_batch_evaluation", "dq_result", "canonical_processing_error",
            "canonical_validation_warning", "canonical_family_processing_error", "benefit",
            "canonical_benefit_observation", "canonical_benefit_disbursement", "canonical_benefit_warning",
            "canonical_benefit_processing_error", "benefit_end_marker", "application", "application_observation",
            "application_stage_event", "application_processing_error", "asset", "asset_observation",
            "asset_processing_error");

    private CanonicalBatchRows() {
    }

    public static ObjectNode read(Connection c, UUID batch) throws SQLException {
        List<Object> b = List.of(batch);
        return RowReplication.read(c, List.of(
                new Selection("dq_batch_evaluation", "batch_id = ?", b),
                new Selection("dq_result", "dq_evaluation_id in (select dq_evaluation_id from dq_batch_evaluation"
                        + " where batch_id = ?)", b),
                new Selection("canonical_processing_error", "batch_id = ?", b),
                new Selection("canonical_validation_warning", "batch_id = ?", b),
                new Selection("canonical_family_processing_error", "batch_id = ?", b),
                new Selection("benefit", "benefit_id in (select benefit_id from canonical_benefit_observation"
                        + " where batch_id = ?)", b),
                new Selection("canonical_benefit_observation", "batch_id = ?", b),
                new Selection("canonical_benefit_disbursement", "canonical_benefit_observation_id in (select"
                        + " canonical_benefit_observation_id from canonical_benefit_observation where batch_id = ?)", b),
                new Selection("canonical_benefit_warning", "batch_id = ?", b),
                new Selection("canonical_benefit_processing_error", "batch_id = ?", b),
                new Selection("benefit_end_marker", "batch_id = ?", b),
                new Selection("application", "application_id in (select application_id from application_observation"
                        + " where batch_id = ?)", b),
                new Selection("application_observation", "batch_id = ?", b),
                new Selection("application_stage_event", "application_observation_id in (select"
                        + " application_observation_id from application_observation where batch_id = ?)", b),
                new Selection("application_processing_error", "batch_id = ?", b),
                new Selection("asset", "asset_id in (select asset_id from asset_observation where batch_id = ?)", b),
                new Selection("asset_observation", "batch_id = ?", b),
                new Selection("asset_processing_error", "batch_id = ?", b)));
    }

    /** At most this many rows per event, so a large batch never exceeds a Kafka message. */
    static final int CHUNK_ROWS = 500;

    /** The rows split into ordered chunks of at most {@link #CHUNK_ROWS} rows (always at least one chunk). */
    public static List<ObjectNode> chunks(ObjectNode rows) {
        return RowReplication.chunks(rows, ORDER, CHUNK_ROWS, java.util.Set.of());
    }

    /** Applies a canonical.batch-observed event; the last chunk records that the batch's rows are all here. */
    public static void apply(Connection c, JsonNode envelope) throws SQLException {
        JsonNode p = envelope.path("payload");
        RowReplication.apply(c, p.path("rows"), ORDER, Map.of());
        if (!p.path("last").asBoolean(true)) {
            return;
        }
        try (var s = c.prepareStatement("insert into remote_canonical_observed (batch_id) values (?)"
                + " on conflict do nothing")) {
            s.setObject(1, UUID.fromString(p.path("batchId").asText()));
            s.executeUpdate();
        }
    }
}
