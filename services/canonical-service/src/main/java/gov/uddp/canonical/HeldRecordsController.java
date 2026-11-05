package gov.uddp.canonical;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.held.HeldRecord;
import gov.uddp.core.held.HeldRecordOperations;
import gov.uddp.core.held.HeldRecordRepository;
import gov.uddp.core.held.HeldStore;
import gov.uddp.core.read.ReadNotFoundException;
import gov.uddp.core.rules.ApprovalConflictException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Held records for the edge (service token only): the edge checks the user's permission, state scope and
 * maker-checker, then calls here; {@code actor} is the deciding user's name as the edge authenticated it.
 */
@RestController
@RequestMapping("/api/canonical/held-records")
public class HeldRecordsController {

    private final HeldRecordOperations held;

    public HeldRecordsController(HeldRecordOperations held) {
        this.held = held;
    }

    /** A group reprocess a stopped process left running continues where it was (after the schema is migrated). */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void resumeReprocessJobs() {
        held.resumeReprocessJobs();
    }

    @GetMapping
    public HeldStore.PageOf list(@RequestParam(required = false) UUID batch,
                                 @RequestParam(required = false) String source,
                                 @RequestParam(required = false) String department,
                                 @RequestParam(required = false) String reason,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) Integer minAgeHours,
                                 @RequestParam(required = false) List<String> states,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "50") int size) {
        return held.list(new HeldRecordRepository.Filter(batch, source, department, reason, status, minAgeHours,
                states), page, size);
    }

    @GetMapping("/groups")
    public List<HeldStore.Group> groups(@RequestParam(required = false) List<String> states,
                                        @RequestParam(defaultValue = "5") int samples) {
        return held.groups(states, samples);
    }

    @GetMapping("/samples")
    public List<HeldRecord> samples(@RequestParam UUID batch, @RequestParam String reason,
                                    @RequestParam(defaultValue = "true") boolean waitingOnly,
                                    @RequestParam(required = false) List<String> states,
                                    @RequestParam(defaultValue = "5") int limit) {
        return held.samples(batch, reason, waitingOnly, states, limit);
    }

    @GetMapping("/{id}")
    public HeldStore.Record find(@PathVariable UUID id) {
        return held.find(id).orElseThrow(() -> new ReadNotFoundException("Held record not found: " + id));
    }

    @PostMapping("/{id}/rectify")
    public HeldStore.RectifyResult rectify(@PathVariable UUID id, @RequestBody JsonNode body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = new com.fasterxml.jackson.databind.ObjectMapper()
                .convertValue(body.path("fields"), Map.class);
        return held.rectify(id, fields, text(body, "note"), text(body, "actor"));
    }

    @PostMapping("/{id}/release-override")
    public HeldStore.RectifyResult releaseOverride(@PathVariable UUID id, @RequestBody JsonNode body) {
        return held.releaseOverride(id, text(body, "justification"), text(body, "actor"));
    }

    @PostMapping("/{id}/reject")
    public HeldRecord reject(@PathVariable UUID id, @RequestBody JsonNode body) {
        return held.reject(id, text(body, "reasonCode"), text(body, "note"), text(body, "actor"));
    }

    @PostMapping("/{id}/reopen")
    public HeldRecord reopen(@PathVariable UUID id, @RequestBody JsonNode body) {
        return held.reopen(id, text(body, "reason"), text(body, "actor"));
    }

    @GetMapping("/groups/{batch}/{reason}/waiting")
    public Map<String, Object> waiting(@PathVariable UUID batch, @PathVariable String reason) {
        return Map.of("count", held.waitingCount(batch, reason),
                "stateCode", held.batchState(batch).orElse(""));
    }

    @PostMapping("/groups/{batch}/{reason}/reject")
    public Map<String, Object> rejectGroup(@PathVariable UUID batch, @PathVariable String reason,
                                           @RequestBody JsonNode body) {
        return Map.of("rejected", held.rejectWaiting(batch, reason, text(body, "reasonCode"), text(body, "note"),
                text(body, "actor")));
    }

    @PostMapping("/groups/{batch}/{reason}/reprocess")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
    public HeldStore.ReprocessJob reprocess(@PathVariable UUID batch, @PathVariable String reason,
                                            @RequestBody JsonNode body) {
        return held.startReprocess(batch, reason, text(body, "actor"));
    }

    @GetMapping("/reprocess-jobs/{jobId}")
    public HeldStore.ReprocessJob job(@PathVariable UUID jobId) {
        return held.reprocessJob(jobId).orElseThrow(() -> new ReadNotFoundException("Reprocess job not found: "
                + jobId));
    }

    @GetMapping(value = "/groups/{batch}/{reason}/export", produces = "text/csv")
    public void export(@PathVariable UUID batch, @PathVariable String reason,
                       @RequestParam(defaultValue = "true") boolean waitingOnly,
                       HttpServletResponse response) throws IOException {
        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        held.exportCsv(batch, reason, waitingOnly, response.getWriter());
    }

    @GetMapping("/metrics")
    public HeldStore.Metrics metrics() {
        return held.metrics();
    }

    @ExceptionHandler(ReadNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(ReadNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(ApprovalConflictException.class)
    ResponseEntity<Map<String, String>> conflict(ApprovalConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", String.valueOf(e.getMessage())));
    }

    private static String text(JsonNode body, String field) {
        return body.hasNonNull(field) ? body.get(field).asText() : null;
    }
}
