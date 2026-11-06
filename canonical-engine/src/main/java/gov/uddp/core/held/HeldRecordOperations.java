package gov.uddp.core.held;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.core.canonical.CanonicalResidentProcessingService;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.read.ReadNotFoundException;
import gov.uddp.core.rules.ApprovalConflictException;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.validation.ReasonCodes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Held records in the database they live in: reads, steward decisions on one record or a group, a group reprocess in
 * the background with its progress kept in {@code held_reprocess_job}, and a CSV export of a group. Used in-process
 * where the records are local and by canonical-service, which owns them in the microservice deployment. Callers check
 * permissions and scope first.
 */
public class HeldRecordOperations implements HeldStore {

    private static final Pattern PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");
    /** Progress of a reprocess is saved every this many records. */
    static final int PROGRESS_EVERY = 100;

    private final HeldRecordRepository repository;
    private final CanonicalResidentProcessingService processing;
    private final StatePackLoader packs;
    private final PostgresConnectionFactory connections;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService reprocessor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "held-reprocess");
        thread.setDaemon(true);
        return thread;
    });

    public HeldRecordOperations(HeldRecordRepository repository, CanonicalResidentProcessingService processing,
                                StatePackLoader packs, PostgresConnectionFactory connections) {
        this.repository = repository;
        this.processing = processing;
        this.packs = packs;
        this.connections = connections;
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    @Override
    public PageOf list(HeldRecordRepository.Filter filter, int page, int size) {
        return new PageOf(repository.list(filter, size, page * size), page, size, repository.count(filter));
    }

    @Override
    public List<Group> groups(List<String> stateCodes, int samples) {
        var groups = new ArrayList<Group>();
        for (var row : repository.groups(stateCodes)) {
            groups.add(new Group(row.batchId(), row.reasonCode(), row.stateCode(), row.count(),
                    row.batchRows() == 0 ? 0 : 100.0 * row.count() / row.batchRows(),
                    samples(row.batchId(), row.reasonCode(), true, stateCodes, samples)));
        }
        return groups;
    }

    @Override
    public List<HeldRecord> samples(UUID batch, String reason, boolean waitingOnly, List<String> stateCodes,
                                    int limit) {
        return repository.list(new HeldRecordRepository.Filter(batch, null, null, reason,
                waitingOnly ? HeldRecordRepository.WAITING_FILTER : null, null, stateCodes), limit, 0);
    }

    @Override
    public Optional<Record> find(UUID heldId) {
        return repository.find(heldId).map(held -> new Record(held, repository.events(heldId)));
    }

    @Override
    public int waitingCount(UUID batch, String reason) {
        return repository.waitingIds(batch, reason).size();
    }

    @Override
    public Optional<String> batchState(UUID batch) {
        try (var c = connections.open();
                var s = c.prepareStatement("select state_code from held_record where batch_id = ? limit 1")) {
            s.setObject(1, batch);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the batch's held records", e);
        }
    }

    @Override
    public Metrics metrics() {
        return new Metrics(repository.countsByStatus(), repository.oldestWaitingSeconds(),
                repository.meanSecondsToDecide());
    }

    @Override
    public HeldRecordRepository.BatchShare share(UUID batch) {
        return repository.share(batch);
    }

    // ---- single-record decisions -----------------------------------------------------------------------------------

    /**
     * Stores {@code fields} (canonical path to value) as the next correction and validates the record again. When it
     * passes it is released to canonical as source-corrected (provenance {@code steward-corrected}) and becomes
     * RECTIFIED; when it still fails it stays OPEN with the reasons found now.
     */
    @Override
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
        stamp(corrected, "steward-corrected", held, actor, version, fields.keySet());
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
    @Override
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
        stamp(payload, "steward-released", held, actor, held.correctionVersion(), List.of());
        UUID canonical = connections.inTransaction(() -> {
            UUID released = processing.release(held, payload);
            decide(held, "RELEASED_OVERRIDE", actor, "OVERRIDE", justification.strip(), "RELEASE_OVERRIDE", null,
                    Map.of("canonicalResidentId", released));
            return released;
        });
        return new RectifyResult(id, "RELEASED_OVERRIDE", held.correctionVersion(), List.of(), canonical);
    }

    @Override
    public HeldRecord reject(UUID id, String reasonCode, String note, String actor) {
        var held = decidable(id);
        decide(held, "REJECTED_FINAL", actor, reasonCode, blank(note), "REJECT", null, Map.of("reasonCode", reasonCode));
        return repository.find(id).orElseThrow();
    }

    /** REJECTED_FINAL back to OPEN. */
    @Override
    public HeldRecord reopen(UUID id, String reason, String actor) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reopening needs a reason");
        }
        var held = repository.find(id).orElseThrow(() -> new ReadNotFoundException("Held record not found: " + id));
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

    @Override
    public int rejectWaiting(UUID batch, String reason, String rejectReasonCode, String note, String actor) {
        int rejected = 0;
        for (UUID id : repository.waitingIds(batch, reason)) {
            var held = repository.find(id).orElseThrow();
            if (held.waiting() && repository.transition(id, HeldRecordRepository.WAITING, "REJECTED_FINAL", actor,
                    rejectReasonCode, blank(note), "BULK_REJECT", null, Map.of("reasonCode", rejectReasonCode))) {
                rejected++;
            }
        }
        return rejected;
    }

    /**
     * Starts running the group's waiting records through mapping and validation again (after a pack or rule fix), in
     * the background. Each record commits on its own: one that now passes is released to canonical (RECTIFIED, reason
     * REPROCESSED), the others stay held with their new reasons. Progress is saved as it goes.
     */
    @Override
    public ReprocessJob startReprocess(UUID batch, String reason, String actor) {
        UUID job = UUID.randomUUID();
        int total = repository.waitingIds(batch, reason).size();
        if (total == 0) {
            throw new ReadNotFoundException("No waiting held records for batch " + batch + " and reason " + reason);
        }
        try (var c = connections.open();
                var s = c.prepareStatement("insert into held_reprocess_job(job_id,batch_id,reason_code,total,"
                        + "requested_by) values(?,?,?,?,?)")) {
            s.setObject(1, job);
            s.setObject(2, batch);
            s.setString(3, reason);
            s.setInt(4, total);
            s.setString(5, actor);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot start the reprocess", e);
        }
        reprocessor.execute(() -> runReprocess(job));
        return reprocessJob(job).orElseThrow();
    }

    /** Continues reprocess jobs a stopped process left RUNNING (records already released are no longer waiting). */
    public int resumeReprocessJobs() {
        List<UUID> running = new ArrayList<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select job_id from held_reprocess_job where status = 'RUNNING'");
                var r = s.executeQuery()) {
            while (r.next()) {
                running.add(r.getObject(1, UUID.class));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the reprocess jobs", e);
        }
        running.forEach(job -> reprocessor.execute(() -> runReprocess(job)));
        return running.size();
    }

    private void runReprocess(UUID job) {
        ReprocessJob current = reprocessJob(job).orElseThrow();
        int processed = current.processed();
        int released = current.released();
        int still = current.stillHeld();
        try {
            List<UUID> ids = repository.waitingIds(current.batchId(), current.reasonCode());
            for (UUID id : ids) {
                var held = repository.find(id).orElseThrow();
                if (!held.waiting()) {
                    continue;
                }
                var outcome = processing.reassess(held);
                if (outcome.releasable()) {
                    connections.inTransaction(() -> {
                        decide(held, "RECTIFIED", current.requestedBy(), "REPROCESSED", "Released after reprocessing",
                                "REPROCESS_RELEASE", null, Map.of("canonicalResidentId", outcome.canonicalId()));
                        return null;
                    });
                    released++;
                } else {
                    repository.saveReasons(id, outcome.reasons());
                    repository.note(id, current.requestedBy(), "REPROCESS_STILL_HELD", held.reasons(),
                            outcome.reasons(), null);
                    still++;
                }
                processed++;
                if (processed % PROGRESS_EVERY == 0) {
                    progress(job, "RUNNING", processed, released, still, null);
                }
            }
            progress(job, "COMPLETED", processed, released, still, null);
        } catch (RuntimeException e) {
            progress(job, "FAILED", processed, released, still, String.valueOf(e.getMessage()));
        }
    }

    private void progress(UUID job, String status, int processed, int released, int still, String error) {
        try (var c = connections.open();
                var s = c.prepareStatement("update held_reprocess_job set status = ?, processed = ?, released = ?,"
                        + " still_held = ?, error = ?, finished_at = case when ? = 'RUNNING' then null else now() end"
                        + " where job_id = ?")) {
            s.setString(1, status);
            s.setInt(2, processed);
            s.setInt(3, released);
            s.setInt(4, still);
            s.setString(5, error);
            s.setString(6, status);
            s.setObject(7, job);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot save the reprocess progress", e);
        }
    }

    @Override
    public Optional<ReprocessJob> reprocessJob(UUID jobId) {
        try (var c = connections.open();
                var s = c.prepareStatement("select job_id, batch_id, reason_code, status, total, processed, released,"
                        + " still_held, requested_by, started_at, finished_at, error from held_reprocess_job"
                        + " where job_id = ?")) {
            s.setObject(1, jobId);
            try (var r = s.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ReprocessJob(r.getObject(1, UUID.class), r.getObject(2, UUID.class),
                        r.getString(3), r.getString(4), r.getInt(5), r.getInt(6), r.getInt(7), r.getInt(8),
                        r.getString(9), r.getObject(10, OffsetDateTime.class), r.getObject(11, OffsetDateTime.class),
                        r.getString(12)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read the reprocess job", e);
        }
    }

    // ---- export ----------------------------------------------------------------------------------------------------

    /**
     * The group's records as CSV, streamed: what the source department needs to correct its own data, namely the
     * source record id and line, the reasons, and the row exactly as the source delivered it.
     */
    @Override
    public void exportCsv(UUID batch, String reason, boolean waitingOnly, Writer out) {
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try (var s = c.prepareStatement("select h.held_id, h.source_system, r.source_record_id, r.source_line,"
                    + " h.status, h.reason_codes::text, r.raw_payload, h.created_at from held_record h"
                    + " join raw_record r on r.raw_record_id = h.raw_record_id where h.batch_id = ?"
                    + (waitingOnly ? " and h.status = any (?)" : "")
                    + " and exists (select 1 from jsonb_array_elements(h.reason_codes) e where e->>'code' = ?)"
                    + " order by r.source_line nulls last, h.held_id")) {
                s.setFetchSize(1000);
                int i = 1;
                s.setObject(i++, batch);
                if (waitingOnly) {
                    s.setArray(i++, c.createArrayOf("text", HeldRecordRepository.WAITING.toArray()));
                }
                s.setString(i, reason);
                out.write("held_id,source,source_record_id,source_line,status,reason_codes,messages,fields,"
                        + "held_at,source_row\n");
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        List<String> codes = new ArrayList<>();
                        List<String> messages = new ArrayList<>();
                        List<String> fields = new ArrayList<>();
                        for (JsonNode e : json.readTree(r.getString(6))) {
                            codes.add(e.path("code").asText());
                            messages.add(e.path("message").asText());
                            if (e.hasNonNull("field")) {
                                fields.add(e.path("field").asText());
                            }
                        }
                        String rawLine = rawLine(r.getString(7));
                        out.write(String.join(",", csv(r.getString(1)), csv(r.getString(2)), csv(r.getString(3)),
                                csv(r.getString(4)), csv(r.getString(5)), csv(String.join("; ", codes)),
                                csv(String.join("; ", messages)), csv(String.join("; ", fields)),
                                csv(String.valueOf(r.getObject(8, OffsetDateTime.class))), csv(rawLine)));
                        out.write('\n');
                    }
                }
            } finally {
                c.rollback();
            }
            out.flush();
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot export the held records", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String rawLine(String payload) {
        if (payload == null) {
            return "";
        }
        try {
            JsonNode node = json.readTree(payload);
            return node.hasNonNull("rawLine") ? node.get("rawLine").asText() : payload;
        } catch (IOException e) {
            return payload;
        }
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        // A cell that a spreadsheet would run as a formula is quoted with a leading apostrophe.
        String text = !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")
                ? "\"" + text.replace("\"", "\"\"") + "\"" : text;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private HeldRecord decidable(UUID id) {
        var held = repository.find(id).orElseThrow(() -> new ReadNotFoundException("Held record not found: " + id));
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

    private static boolean touches(java.util.Set<String> corrected, String field) {
        if (field == null) {
            return false;
        }
        return corrected.stream().anyMatch(p -> p.equals(field) || field.startsWith(p + ".") || p.startsWith(field + "."));
    }

    private void set(ObjectNode root, String path, Object value) {
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
            node.set(leaf, json.valueToTree(value));
        }
    }

    /** Records who changed what under {@code extensions.stewardDecision}; survivorship gives it no extra trust. */
    private static void stamp(ObjectNode payload, String provenance, HeldRecord held, String actor, int version,
                              java.util.Collection<String> fields) {
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
