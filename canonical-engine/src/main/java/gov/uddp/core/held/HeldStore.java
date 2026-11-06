package gov.uddp.core.held;

import java.io.Writer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Held records where they are kept: canonical-service in the microservice deployment (reached over HTTP by the edge),
 * or the same database as the caller ({@link HeldRecordOperations}). No method checks who is calling: the edge checks
 * permissions, state scope and maker-checker before it calls.
 */
public interface HeldStore {

    record PageOf(List<HeldRecord> items, int page, int size, long totalItems) {
    }

    record Group(UUID batchId, String reasonCode, String stateCode, int count, double shareOfBatchPercent,
                 List<HeldRecord> samples) {
    }

    /** A held record with its append-only history. */
    record Record(HeldRecord record, List<HeldEvent> history) {
    }

    /** The result of a rectify attempt or a release. */
    record RectifyResult(UUID heldId, String status, int correctionVersion, List<HeldReason> reasons,
                         UUID canonicalResidentId) {
    }

    /** A group reprocess running in the background: how far it got and what it found. */
    record ReprocessJob(UUID jobId, UUID batchId, String reasonCode, String status, int total, int processed,
                        int released, int stillHeld, String requestedBy, OffsetDateTime startedAt,
                        OffsetDateTime finishedAt, String error) {
    }

    /** Status counts and timings of held records, for the platform metrics. */
    record Metrics(Map<String, Integer> countsByStatus, double oldestWaitingSeconds, double meanSecondsToDecide) {
    }

    PageOf list(HeldRecordRepository.Filter filter, int page, int size);

    List<Group> groups(List<String> stateCodes, int samples);

    /** Up to {@code limit} records of a batch and reason (only waiting ones when {@code waitingOnly}). */
    List<HeldRecord> samples(UUID batch, String reason, boolean waitingOnly, List<String> stateCodes, int limit);

    Optional<Record> find(UUID heldId);

    RectifyResult rectify(UUID heldId, Map<String, Object> fields, String note, String actor);

    RectifyResult releaseOverride(UUID heldId, String justification, String actor);

    HeldRecord reject(UUID heldId, String reasonCode, String note, String actor);

    HeldRecord reopen(UUID heldId, String reason, String actor);

    /** Rejects for good every waiting record of the group; returns how many. */
    int rejectWaiting(UUID batch, String reason, String rejectReasonCode, String note, String actor);

    int waitingCount(UUID batch, String reason);

    /** The state of a batch that has held records (empty when it has none). */
    Optional<String> batchState(UUID batch);

    /** Starts re-running the group's waiting records through mapping and validation in the background. */
    ReprocessJob startReprocess(UUID batch, String reason, String actor);

    Optional<ReprocessJob> reprocessJob(UUID jobId);

    /** Writes the group's records as CSV (one row per record, its reasons and its current values). */
    void exportCsv(UUID batch, String reason, boolean waitingOnly, Writer out);

    Metrics metrics();

    /** Held and processing-rejected rows of a delivery against its rows (the release gate's held share). */
    HeldRecordRepository.BatchShare share(UUID batch);
}
