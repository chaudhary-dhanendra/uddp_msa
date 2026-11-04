package gov.uddp.core.held;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.core.canonical.CanonicalResidentProcessingService;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.monitoring.MonitoringService.Metric;
import gov.uddp.core.monitoring.PlatformMetricsSource;
import gov.uddp.core.read.ReadNotFoundException;
import gov.uddp.core.rules.ApprovalConflictException;
import gov.uddp.core.rules.MakerChecker;
import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.validation.ReasonCodes;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Steward decisions on held records: rectify (a correction stored as a new version and validated again), release as
 * is (only when every reason is steward-routed, never for a hard error), reject for good, reopen, and the same
 * decisions over a group of one batch and reason. A record outside the caller's state scope is refused.
 */
@Service
public class HeldRecordService implements PlatformMetricsSource {
    /** What a steward may name when rejecting for good. */
    public static final List<String> REJECT_REASONS =
            List.of("SOURCE_ERROR", "DUPLICATE_OF_OTHER_SOURCE", "OUT_OF_SCOPE", "UNREADABLE", "OTHER");

    private static final Pattern PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final int SAMPLES = 5;
    private static final int MAX_PAGE_SIZE = 200;

    /** The result of a rectify attempt. */
    public record RectifyResult(UUID heldId, String status, int correctionVersion, List<HeldReason> reasons,
                                UUID canonicalResidentId) {
    }

    /** The result of a group action: applied now, or waiting for a second steward. */
    public record GroupActionResult(String outcome, int records, UUID actionId, Integer released, Integer stillHeld) {
    }

    public record Group(UUID batchId, String reasonCode, String stateCode, int count, double shareOfBatchPercent,
                        List<HeldRecord> samples) {
    }

    /** A held record with its history and a suggested fix per reason code (no values of the record). */
    public record Detail(HeldRecord record, List<HeldEvent> history, Map<String, String> suggestions) {
    }

    public record PageOf(List<HeldRecord> items, int page, int size, long totalItems) {
    }

    private final HeldRecordRepository repository;
    private final CanonicalResidentProcessingService processing;
    private final StatePackLoader packs;
    private final PostgresConnectionFactory connections;
    private final MakerChecker bulk;
    private final int bulkThreshold;

    @Autowired
    public HeldRecordService(HeldRecordRepository repository, CanonicalResidentProcessingService processing,
                             StatePackLoader packs, PostgresConnectionFactory connections,
                             @Value("${uddp.stewardship.bulk-approval-threshold:50}") int bulkThreshold) {
        this.repository = repository;
        this.processing = processing;
        this.packs = packs;
        this.connections = connections;
        this.bulk = new MakerChecker(connections, "held_bulk_action", "action_id", "bulk held-record action");
        this.bulkThreshold = bulkThreshold;
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    public PageOf list(UUID batch, String source, String department, String reason, String status, Integer minAgeHours,
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
        return new PageOf(repository.list(filter, pageSize, pageNumber * pageSize), pageNumber, pageSize,
                repository.count(filter));
    }

    public List<Group> groups() {
        var groups = new ArrayList<Group>();
        for (var row : repository.groups(scopeStates())) {
            var samples = repository.list(new HeldRecordRepository.Filter(row.batchId(), null, null, row.reasonCode(),
                    HeldRecordRepository.WAITING_FILTER, null, scopeStates()), SAMPLES, 0);
            groups.add(new Group(row.batchId(), row.reasonCode(), row.stateCode(), row.count(),
                    row.batchRows() == 0 ? 0 : 100.0 * row.count() / row.batchRows(), samples));
        }
        return groups;
    }

    public Detail detail(UUID id) {
        var held = repository.find(id).filter(h -> CurrentUser.scope().allows(h.stateCode()))
                .orElseThrow(() -> new ReadNotFoundException("Held record not found: " + id));
        return new Detail(held, repository.events(id), suggestions(held));
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
    public RectifyResult rectify(UUID id, Map<String, Object> fields, String note, String actor) {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("fields must name at least one canonical path to correct");
        }
        for (String path : fields.keySet()) {
            if (path == null || !PATH.matcher(path).matches()) {
                throw new IllegalArgumentException("Not a canonical field path: " + path);
            }
        }
        var held = decidable(id);
        JsonNode before = held.currentPayload();
        ObjectNode corrected = before.deepCopy();
        fields.forEach((path, value) -> set(corrected, path, value));
        var pack = packs.find(held.stateCode()).orElseThrow(() -> new IllegalStateException(
                "No State Pack for " + held.stateCode()));
        var reasons = new ArrayList<HeldReason>();
        for (var issue : processing.blockingIssues(corrected, pack)) {
            reasons.add(new HeldReason(ReasonCodes.ruleFailed(issue.rule()), issue.message(), issue.field(),
                    issue.severity()));
        }
        // Reasons that are not a rule finding (an invalid identifier, a low-confidence field) stay until their field is corrected.
        for (var reason : held.reasons()) {
            if (!reason.code().startsWith(ReasonCodes.RULE_FAILED_PREFIX) && !touches(fields.keySet(), reason.field())) {
                reasons.add(reason);
            }
        }
        int version = held.correctionVersion() + 1;
        if (!reasons.isEmpty()) {
            repository.saveCorrection(id, corrected, reasons, version, actor, before, note);
            return new RectifyResult(id, held.status(), version, reasons, null);
        }
        stamp(corrected, "steward-corrected", held, actor, version, fields.keySet(), note);
        UUID canonical = connections.inTransaction(() -> {
            repository.saveCorrection(id, corrected, List.of(), version, actor, before, note);
            UUID released = processing.release(held, corrected);
            decide(held, "RECTIFIED", actor, "RECTIFIED", note, "RECTIFY", before,
                    Map.of("canonicalResidentId", released, "correctionVersion", version));
            return released;
        });
        return new RectifyResult(id, "RECTIFIED", version, List.of(), canonical);
    }

    /** Releases the record as it is; only when every reason is steward-routed, never for a hard error. */
    public RectifyResult releaseOverride(UUID id, String justification, String actor) {
        if (justification == null || justification.isBlank()) {
            throw new IllegalArgumentException("A release needs a justification");
        }
        var held = decidable(id);
        if (!held.overridable()) {
            throw new ApprovalConflictException("A record held for a hard error cannot be released without a correction;"
                    + " rectify it or reject it");
        }
        ObjectNode payload = held.currentPayload().deepCopy();
        stamp(payload, "steward-released", held, actor, held.correctionVersion(), List.of(), justification);
        UUID canonical = connections.inTransaction(() -> {
            UUID released = processing.release(held, payload);
            decide(held, "RELEASED_OVERRIDE", actor, "OVERRIDE", justification.strip(), "RELEASE_OVERRIDE", null,
                    Map.of("canonicalResidentId", released));
            return released;
        });
        return new RectifyResult(id, "RELEASED_OVERRIDE", held.correctionVersion(), List.of(), canonical);
    }

    public HeldRecord reject(UUID id, String reasonCode, String note, String actor) {
        String code = rejectCode(reasonCode, note);
        var held = decidable(id);
        decide(held, "REJECTED_FINAL", actor, code, blank(note), "REJECT", null, Map.of("reasonCode", code));
        return repository.find(id).orElseThrow();
    }

    /** REJECTED_FINAL back to OPEN; the caller must be a platform administrator (checked by the endpoint). */
    public HeldRecord reopen(UUID id, String reason, String actor) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reopening needs a reason");
        }
        var held = repository.find(id).orElseThrow(() -> new ReadNotFoundException("Held record not found: " + id));
        requireInScope(held);
        if (held.currentPayload().has("uddp.purged")) {
            throw new ApprovalConflictException("The payload of this record was purged by the retention policy;"
                    + " it cannot be reopened");
        }
        if (!repository.transition(id, List.of("REJECTED_FINAL"), "OPEN", actor, null, reason.strip(), "REOPEN",
                Map.of("status", held.status()), Map.of("status", "OPEN"))) {
            throw new ApprovalConflictException("Only a rejected record can be reopened, not " + held.status());
        }
        return repository.find(id).orElseThrow();
    }

    // ---- group decisions -------------------------------------------------------------------------------------------

    /**
     * Rejects every waiting record of the group. A group larger than the bulk-approval threshold waits for a second
     * steward ({@link #approveBulk}); a smaller one is rejected now.
     */
    public GroupActionResult rejectGroup(UUID batch, String reason, String rejectReasonCode, String note, String actor) {
        String code = rejectCode(rejectReasonCode, note);
        requireBatchInScope(batch);
        var ids = repository.waitingIds(batch, reason);
        if (ids.isEmpty()) {
            throw new ReadNotFoundException("No waiting held records for batch " + batch + " and reason " + reason);
        }
        if (ids.size() <= bulkThreshold) {
            rejectAll(ids, code, note, actor, "BULK_REJECT");
            return new GroupActionResult("APPLIED", ids.size(), null, null, null);
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
            s.setInt(6, ids.size());
            s.setString(7, actor);
            s.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot store the bulk action", e);
        }
        bulk.submit(action, actor);
        return new GroupActionResult("PENDING_APPROVAL", ids.size(), action, null, null);
    }

    /** A second steward approves a pending bulk reject; it is applied to the records still waiting in the group. */
    public GroupActionResult approveBulk(UUID action, String reason, String approver) {
        var pending = pendingAction(action);
        requireOtherSteward(action, approver);
        bulk.approve(action, approver, reason);
        var ids = repository.waitingIds(pending.batch(), pending.reason());
        rejectAll(ids, pending.rejectCode(), pending.note(), approver, "BULK_REJECT");
        return new GroupActionResult("APPLIED", ids.size(), action, null, null);
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
                + (blank(department) == null ? "" : " and exists (select 1 from held_record h where h.batch_id ="
                        + " a.batch_id and lower(h.department) = lower(?))")
                + " order by a.created_at desc";
        String me = CurrentUser.username();
        List<BulkAction> out = new ArrayList<>();
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            int i = 1;
            s.setArray(i++, c.createArrayOf("text", (wanted == null ? BULK_STATUSES : List.of(wanted)).toArray()));
            if (states != null) {
                s.setArray(i++, c.createArrayOf("text", states.toArray()));
            }
            if (blank(department) != null) {
                s.setString(i, department.strip());
            }
            var r = s.executeQuery();
            while (r.next()) {
                UUID batch = r.getObject(2, UUID.class);
                String reason = r.getString(3);
                String state = r.getString(9);
                var samples = repository.list(new HeldRecordRepository.Filter(batch, null, null, reason,
                        "PENDING_APPROVAL".equals(state) ? HeldRecordRepository.WAITING_FILTER : null, null, states),
                        SAMPLES, 0);
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
     * Runs the group's waiting records through mapping and validation again (after a pack or rule fix). Records that
     * now pass are released to canonical (RECTIFIED, reason REPROCESSED); the others stay held with their new reasons.
     */
    public GroupActionResult reprocessGroup(UUID batch, String reason, String actor) {
        requireBatchInScope(batch);
        var ids = repository.waitingIds(batch, reason);
        int released = 0;
        int still = 0;
        for (UUID id : ids) {
            var held = repository.find(id).orElseThrow();
            var outcome = processing.reassess(held);
            if (outcome.releasable()) {
                connections.inTransaction(() -> {
                    decide(held, "RECTIFIED", actor, "REPROCESSED", "Released after reprocessing", "REPROCESS_RELEASE",
                            null, Map.of("canonicalResidentId", outcome.canonicalId()));
                    return null;
                });
                released++;
            } else {
                repository.saveReasons(id, outcome.reasons());
                repository.note(id, actor, "REPROCESS_STILL_HELD", held.reasons(), outcome.reasons(), null);
                still++;
            }
        }
        return new GroupActionResult("APPLIED", ids.size(), null, released, still);
    }

    // ---- metrics ---------------------------------------------------------------------------------------------------

    @Override
    public Map<Metric, Double> platformMetrics() {
        var counts = repository.countsByStatus();
        var values = new EnumMap<Metric, Double>(Metric.class);
        values.put(Metric.HELD_OPEN, (double) (counts.getOrDefault("OPEN", 0) + counts.getOrDefault("IN_REVIEW", 0)));
        values.put(Metric.HELD_OLDEST_HOURS, repository.oldestWaitingSeconds() / 3600.0);
        values.put(Metric.HELD_RECTIFIED, (double) counts.getOrDefault("RECTIFIED", 0));
        values.put(Metric.HELD_REJECTED, (double) counts.getOrDefault("REJECTED_FINAL", 0));
        values.put(Metric.HELD_HOURS_TO_DECIDE, repository.meanSecondsToDecide() / 3600.0);
        return values;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private HeldRecord decidable(UUID id) {
        var held = repository.find(id).orElseThrow(() -> new ReadNotFoundException("Held record not found: " + id));
        requireInScope(held);
        if (!held.waiting()) {
            throw new ApprovalConflictException("The held record is already " + held.status());
        }
        return held;
    }

    private void decide(HeldRecord held, String status, String actor, String reasonCode, String note, String action,
                        Object before, Object after) {
        if (!repository.transition(held.heldId(), HeldRecordRepository.WAITING, status, actor, reasonCode, note, action,
                before, after)) {
            throw new ApprovalConflictException("The held record changed meanwhile: " + held.heldId());
        }
    }

    private void rejectAll(List<UUID> ids, String code, String note, String actor, String action) {
        for (UUID id : ids) {
            var held = repository.find(id).orElseThrow();
            if (held.waiting()) {
                repository.transition(id, HeldRecordRepository.WAITING, "REJECTED_FINAL", actor, code, blank(note),
                        action, null, Map.of("reasonCode", code));
            }
        }
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
            if (!r.next()) {
                throw new ReadNotFoundException("Batch not found: " + batch);
            }
            if (!CurrentUser.scope().allows(r.getString(1))) {
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

    private static boolean touches(java.util.Set<String> corrected, String field) {
        if (field == null) {
            return false;
        }
        return corrected.stream().anyMatch(p -> p.equals(field) || field.startsWith(p + ".") || p.startsWith(field + "."));
    }

    private static void set(ObjectNode root, String path, Object value) {
        String[] parts = path.split("\\.");
        ObjectNode node = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode child = node.get(parts[i]);
            node = child instanceof ObjectNode object ? object : node.putObject(parts[i]);
        }
        String leaf = parts[parts.length - 1];
        if (value == null) {
            node.putNull(leaf);
        } else {
            node.set(leaf, new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(value));
        }
    }

    /** Records who changed what under {@code extensions.stewardDecision}; survivorship gives it no extra trust. */
    private static void stamp(ObjectNode payload, String provenance, HeldRecord held, String actor, int version,
                              java.util.Collection<String> fields, String note) {
        JsonNode extensions = payload.get("extensions");
        ObjectNode target = extensions instanceof ObjectNode object ? object : payload.putObject("extensions");
        ObjectNode stamp = target.putObject("stewardDecision");
        stamp.put("provenance", provenance);
        stamp.put("heldId", held.heldId().toString());
        stamp.put("correctionVersion", version);
        stamp.put("decidedBy", actor);
        var list = stamp.putArray("correctedFields");
        fields.forEach(list::add);
    }

    private static String blank(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
