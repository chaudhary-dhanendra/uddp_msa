package gov.uddp.core.held;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.monitoring.MonitoringService.Metric;
import gov.uddp.core.monitoring.PlatformMetricsSource;
import gov.uddp.core.read.ReadNotFoundException;
import gov.uddp.core.rules.ApprovalConflictException;
import gov.uddp.core.rules.MakerChecker;
import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.statepack.StatePackLoader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Steward decisions on held records: rectify (a correction stored as a new version and validated again), release as
 * is (only when every reason is steward-routed, never for a hard error), reject for good, reopen, and the same
 * decisions over a group of one batch and reason. A record outside the caller's state scope is refused. The records
 * themselves are kept by a {@link HeldStore}: canonical-service in the microservice deployment, this database
 * otherwise; this service adds permissions, scope, maker-checker for bulk rejects and fix suggestions.
 */
@Service
public class HeldRecordService implements PlatformMetricsSource {
    /** What a steward may name when rejecting for good. */
    public static final List<String> REJECT_REASONS =
            List.of("SOURCE_ERROR", "DUPLICATE_OF_OTHER_SOURCE", "OUT_OF_SCOPE", "UNREADABLE", "OTHER");

    private static final int SAMPLES = 5;
    private static final int MAX_PAGE_SIZE = 200;

    /** The result of a group action: applied now, or waiting for a second steward. */
    public record GroupActionResult(String outcome, int records, UUID actionId, Integer released, Integer stillHeld) {
    }

    /** A held record with its history and a suggested fix per reason code (no values of the record). */
    public record Detail(HeldRecord record, List<HeldEvent> history, Map<String, String> suggestions) {
    }

    private final HeldStore store;
    private final StatePackLoader packs;
    private final PostgresConnectionFactory connections;
    private final MakerChecker bulk;
    private final int bulkThreshold;

    @Autowired
    public HeldRecordService(HeldStore store, StatePackLoader packs, PostgresConnectionFactory connections,
                             @Value("${uddp.stewardship.bulk-approval-threshold:50}") int bulkThreshold) {
        this.store = store;
        this.packs = packs;
        this.connections = connections;
        this.bulk = new MakerChecker(connections, "held_bulk_action", "action_id", "bulk held-record action");
        this.bulkThreshold = bulkThreshold;
    }

    /** Held records kept in this database (tests and the single-process setup). */
    public HeldRecordService(HeldRecordRepository repository,
                             gov.uddp.core.canonical.CanonicalResidentProcessingService processing,
                             StatePackLoader packs, PostgresConnectionFactory connections, int bulkThreshold) {
        this(new HeldRecordOperations(repository, processing, packs, connections), packs, connections, bulkThreshold);
    }

    /** The held record store this service decides on. */
    public HeldStore store() {
        return store;
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    public HeldStore.PageOf list(UUID batch, String source, String department, String reason, String status, Integer minAgeHours,
                       Integer page, Integer size) {
        if (status != null && !status.isBlank() && !HeldRecordRepository.WAITING_FILTER.equals(status.strip())
                && !List.of("OPEN", "IN_REVIEW", "RECTIFIED", "REJECTED_FINAL", "RELEASED_OVERRIDE")
                        .contains(status.strip())) {
            throw new IllegalArgumentException("status must be OPEN, IN_REVIEW, RECTIFIED, REJECTED_FINAL,"
                    + " RELEASED_OVERRIDE or WAITING");
        }
        if (minAgeHours != null && minAgeHours < 0) {
            throw new IllegalArgumentException("minAgeHours must not be negative");
        }
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 50 : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be 0 or more and size between 1 and " + MAX_PAGE_SIZE);
        }
        var filter = new HeldRecordRepository.Filter(batch, blank(source), blank(department), blank(reason),
                blank(status), minAgeHours, scopeStates());
        return store.list(filter, pageNumber, pageSize);
    }

    public List<HeldStore.Group> groups() {
        return store.groups(scopeStates(), SAMPLES);
    }

    public Detail detail(UUID id) {
        var found = store.find(id).filter(h -> CurrentUser.scope().allows(h.record().stateCode()))
                .orElseThrow(() -> new ReadNotFoundException("Held record not found: " + id));
        return new Detail(found.record(), found.history(), suggestions(found.record()));
    }

    private gov.uddp.core.studio.FixSuggestions fixSuggestions;

    @Autowired(required = false)
    public void setFixSuggestions(gov.uddp.core.studio.FixSuggestions fixSuggestions) {
        this.fixSuggestions = fixSuggestions;
    }

    /** The same suggestions the Studio preview gives, built from each reason's fix target and the held payload. */
    private Map<String, String> suggestions(HeldRecord held) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (fixSuggestions == null || held.currentPayload() == null) {
            return out;
        }
        var pack = packs.find(held.stateCode()).orElse(null);
        Map<String, String> row = new java.util.LinkedHashMap<>();
        held.currentPayload().fields().forEachRemaining(e -> {
            if (e.getValue().isValueNode()) {
                row.put(e.getKey(), e.getValue().asText());
            }
        });
        for (HeldReason reason : held.reasons()) {
            Map<String, String> context = new java.util.LinkedHashMap<>();
            String field = reason.field();
            if (field != null) {
                JsonNode value = held.currentPayload().at("/" + field.replace(".", "/"));
                if (value.isValueNode()) {
                    context.put("value", value.asText());
                }
                if (field.startsWith("identifiers.")) {
                    context.put("identifierType", field.substring("identifiers.".length()).split("\\.")[0]);
                }
            }
            if (reason.fix() != null && reason.fix().key() != null) {
                context.put(FixTarget.CROSSWALK.equals(reason.fix().kind()) ? "crosswalk" : "sourceField",
                        reason.fix().key());
            }
            String text = fixSuggestions.suggest(reason.code(), context, pack, row);
            if (text != null) {
                out.putIfAbsent(reason.code(), text);
            }
        }
        return out;
    }

    // ---- single-record decisions ------------------------------------------------------------------------------------

    /**
     * Stores {@code fields} (canonical path to value) as the next correction and validates the record again. When it
     * passes it is released to canonical as source-corrected (provenance {@code steward-corrected}) and becomes
     * RECTIFIED; when it still fails it stays OPEN with the reasons found now.
     */
    public HeldStore.RectifyResult rectify(UUID id, Map<String, Object> fields, String note, String actor) {
        inScope(id);
        return store.rectify(id, fields, note, actor);
    }

    /** Releases the record as it is; only when every reason is steward-routed, never for a hard error. */
    public HeldStore.RectifyResult releaseOverride(UUID id, String justification, String actor) {
        inScope(id);
        return store.releaseOverride(id, justification, actor);
    }

    public HeldRecord reject(UUID id, String reasonCode, String note, String actor) {
        String code = rejectCode(reasonCode, note);
        inScope(id);
        return store.reject(id, code, note, actor);
    }

    /** REJECTED_FINAL back to OPEN; the caller must be a platform administrator (checked by the endpoint). */
    public HeldRecord reopen(UUID id, String reason, String actor) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reopening needs a reason");
        }
        inScope(id);
        return store.reopen(id, reason, actor);
    }

    // ---- group decisions -------------------------------------------------------------------------------------------

    /**
     * Rejects every waiting record of the group. A group larger than the bulk-approval threshold waits for a second
     * steward ({@link #approveBulk}); a smaller one is rejected now.
     */
    public GroupActionResult rejectGroup(UUID batch, String reason, String rejectReasonCode, String note, String actor) {
        String code = rejectCode(rejectReasonCode, note);
        requireBatchInScope(batch);
        int waiting = store.waitingCount(batch, reason);
        if (waiting == 0) {
            throw new ReadNotFoundException("No waiting held records for batch " + batch + " and reason " + reason);
        }
        if (waiting <= bulkThreshold) {
            return new GroupActionResult("APPLIED", store.rejectWaiting(batch, reason, code, note, actor), null, null,
                    null);
        }
        UUID action = UUID.randomUUID();
        try (var c = connections.open();
                var s = c.prepareStatement("insert into held_bulk_action(action_id,batch_id,reason_code,"
                        + "reject_reason_code,note,record_count,created_by) values(?,?,?,?,?,?,?)")) {
            s.setObject(1, action);
            s.setObject(2, batch);
            s.setString(3, reason);
            s.setString(4, code);
            s.setString(5, blank(note));
            s.setInt(6, waiting);
            s.setString(7, actor);
            s.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot store the bulk action", e);
        }
        bulk.submit(action, actor);
        return new GroupActionResult("PENDING_APPROVAL", waiting, action, null, null);
    }

    /** A second steward approves a pending bulk reject; it is applied to the records still waiting in the group. */
    public GroupActionResult approveBulk(UUID action, String reason, String approver) {
        var pending = pendingAction(action);
        requireOtherSteward(action, approver);
        bulk.approve(action, approver, reason);
        int rejected = store.rejectWaiting(pending.batch(), pending.reason(), pending.rejectCode(), pending.note(),
                approver);
        return new GroupActionResult("APPLIED", rejected, action, null, null);
    }

    /** A second steward rejects a pending bulk reject (a reason is required); the records stay held. */
    public void declineBulk(UUID action, String reason, String approver) {
        pendingAction(action);
        requireOtherSteward(action, approver);
        bulk.reject(action, approver, reason);
    }

    /** A bulk reject waiting for, or decided by, a second steward, with up to five (masked) records of its group. */
    public record BulkAction(UUID actionId, UUID batchId, String reasonCode, String stateCode, String sourceSystem,
                             String rejectReasonCode, String note, int recordCount, String status, String requestedBy,
                             java.time.Instant requestedAt, String decidedBy, java.time.Instant decidedAt,
                             String decisionReason, boolean ownRequest, List<HeldRecord> samples) {
    }

    private static final List<String> BULK_STATUSES = List.of("PENDING_APPROVAL", "APPROVED", "REJECTED");

    /** Bulk rejects in the caller's state scope, newest first; {@code status} is PENDING_APPROVAL, APPROVED or REJECTED. */
    public List<BulkAction> bulkActions(String status, String department) {
        String wanted = blank(status);
        if (wanted != null && !BULK_STATUSES.contains(wanted)) {
            throw new IllegalArgumentException("status must be one of " + BULK_STATUSES);
        }
        List<String> states = scopeStates();
        String sql = "select a.action_id, a.batch_id, a.reason_code, b.state_code, b.source_system,"
                + " a.reject_reason_code, a.note, a.record_count, a.status, a.created_by, a.created_at, a.decided_by,"
                + " a.decided_at, a.decision_reason from held_bulk_action a"
                + " join ingestion_batch b on b.batch_id = a.batch_id where a.status = any (?)"
                + (states == null ? "" : " and b.state_code = any (?)")
                + " order by a.created_at desc";
        String me = CurrentUser.username();
        List<BulkAction> out = new ArrayList<>();
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            int i = 1;
            s.setArray(i++, c.createArrayOf("text", (wanted == null ? BULK_STATUSES : List.of(wanted)).toArray()));
            if (states != null) {
                s.setArray(i++, c.createArrayOf("text", states.toArray()));
            }
            var r = s.executeQuery();
            while (r.next()) {
                UUID batch = r.getObject(2, UUID.class);
                String reason = r.getString(3);
                String state = r.getString(9);
                if (blank(department) != null && store.list(new HeldRecordRepository.Filter(batch, null,
                        department.strip(), null, null, null, states), 0, 1).totalItems() == 0) {
                    continue;
                }
                var samples = store.samples(batch, reason, "PENDING_APPROVAL".equals(state), states, SAMPLES);
                out.add(new BulkAction(r.getObject(1, UUID.class), batch, reason, r.getString(4), r.getString(5),
                        r.getString(6), r.getString(7), r.getInt(8), state, r.getString(10),
                        r.getTimestamp(11).toInstant(), r.getString(12),
                        r.getTimestamp(13) == null ? null : r.getTimestamp(13).toInstant(), r.getString(14),
                        r.getString(10).equals(me), samples));
            }
            return out;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot read the bulk actions", e);
        }
    }

    /** The requester of a bulk reject never decides it: a second steward must (409, not a permission failure). */
    private void requireOtherSteward(UUID action, String actor) {
        var state = bulk.state(action);
        if (actor != null && (actor.equals(state.createdBy()) || actor.equals(state.submittedBy()))) {
            throw new ApprovalConflictException("You requested this bulk reject; another steward must decide");
        }
    }

    /**
     * Runs the group's waiting records through mapping and validation again (after a pack or rule fix), in the
     * background where the records are kept; progress is read with {@link #reprocessJob}. Records that now pass are
     * released to canonical (RECTIFIED, reason REPROCESSED); the others stay held with their new reasons.
     */
    public HeldStore.ReprocessJob startReprocess(UUID batch, String reason, String actor) {
        requireBatchInScope(batch);
        return store.startReprocess(batch, reason, actor);
    }

    public HeldStore.ReprocessJob reprocessJob(UUID jobId) {
        var job = store.reprocessJob(jobId)
                .orElseThrow(() -> new ReadNotFoundException("Reprocess job not found: " + jobId));
        requireBatchInScope(job.batchId());
        return job;
    }

    /** As {@link #startReprocess}, waiting until the job has finished (small groups). */
    public GroupActionResult reprocessGroup(UUID batch, String reason, String actor) {
        var job = startReprocess(batch, reason, actor);
        while ("RUNNING".equals(job.status())) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while reprocessing", e);
            }
            job = store.reprocessJob(job.jobId()).orElseThrow();
        }
        if ("FAILED".equals(job.status())) {
            throw new IllegalStateException("Reprocessing stopped: " + job.error());
        }
        return new GroupActionResult("APPLIED", job.total(), null, job.released(), job.stillHeld());
    }

    /** The group's records as CSV for the source department (waiting ones only unless {@code waitingOnly} is false). */
    public void exportGroup(UUID batch, String reason, boolean waitingOnly, java.io.Writer out) {
        requireBatchInScope(batch);
        store.exportCsv(batch, reason, waitingOnly, out);
    }

    // ---- metrics ---------------------------------------------------------------------------------------------------

    @Override
    public Map<Metric, Double> platformMetrics() {
        var metrics = store.metrics();
        var counts = metrics.countsByStatus();
        var values = new EnumMap<Metric, Double>(Metric.class);
        values.put(Metric.HELD_OPEN, (double) (counts.getOrDefault("OPEN", 0) + counts.getOrDefault("IN_REVIEW", 0)));
        values.put(Metric.HELD_OLDEST_HOURS, metrics.oldestWaitingSeconds() / 3600.0);
        values.put(Metric.HELD_RECTIFIED, (double) counts.getOrDefault("RECTIFIED", 0));
        values.put(Metric.HELD_REJECTED, (double) counts.getOrDefault("REJECTED_FINAL", 0));
        values.put(Metric.HELD_HOURS_TO_DECIDE, metrics.meanSecondsToDecide() / 3600.0);
        return values;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** The record must exist and be in the caller's state scope. */
    private void inScope(UUID id) {
        var found = store.find(id).orElseThrow(() -> new ReadNotFoundException("Held record not found: " + id));
        requireInScope(found.record());
    }

    private static String rejectCode(String code, String note) {
        if (code == null || !REJECT_REASONS.contains(code.strip())) {
            throw new IllegalArgumentException("reasonCode must be one of " + REJECT_REASONS);
        }
        if ("OTHER".equals(code.strip()) && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("A note is required when the reason is OTHER");
        }
        return code.strip();
    }

    private record PendingAction(UUID batch, String reason, String rejectCode, String note) {
    }

    private PendingAction pendingAction(UUID action) {
        try (var c = connections.open();
                var s = c.prepareStatement("select batch_id,reason_code,reject_reason_code,note,status"
                        + " from held_bulk_action where action_id = ?")) {
            s.setObject(1, action);
            var r = s.executeQuery();
            if (!r.next()) {
                throw new ReadNotFoundException("Bulk action not found: " + action);
            }
            var pending = new PendingAction(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4));
            requireBatchInScope(pending.batch());
            return pending;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot read the bulk action", e);
        }
    }

    private void requireBatchInScope(UUID batch) {
        try (var c = connections.open();
                var s = c.prepareStatement("select state_code from ingestion_batch where batch_id = ?")) {
            s.setObject(1, batch);
            var r = s.executeQuery();
            // The edge records every batch it starts; one it did not start is known by its held records.
            String state = r.next() ? r.getString(1) : store.batchState(batch)
                    .orElseThrow(() -> new ReadNotFoundException("Batch not found: " + batch));
            if (!CurrentUser.scope().allows(state)) {
                throw new AccessDeniedException("The batch is outside your assigned scope");
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot read the batch", e);
        }
    }

    private static void requireInScope(HeldRecord held) {
        if (!CurrentUser.scope().allows(held.stateCode())) {
            throw new AccessDeniedException("The held record is outside your assigned scope");
        }
    }

    private static List<String> scopeStates() {
        var scope = CurrentUser.scope();
        return scope.all() ? null : List.copyOf(scope.states());
    }

    private static String blank(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
