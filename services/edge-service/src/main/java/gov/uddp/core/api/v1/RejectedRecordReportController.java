package gov.uddp.core.api.v1;

import gov.uddp.core.read.Page;
import gov.uddp.core.read.RejectedRecordReportService;
import gov.uddp.core.read.RejectedRecordReportService.FeedbackRow;
import gov.uddp.core.read.RejectedRecordReportService.RejectedRow;
import gov.uddp.core.read.RejectedRecordReportService.Summary;
import gov.uddp.core.security.AuditContext;
import gov.uddp.core.security.Audited;
import gov.uddp.core.security.CurrentUser;
import gov.uddp.core.security.Permission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Final-rejected held records: the audit report (list, counts and trend, CSV export) and the rejection report of a
 * batch, the feedback file for the source department. Identifiers and values are masked; a caller with
 * RESIDENT_VIEW_FULL may ask for the identifiers unmasked. Every view and export is audited.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Audit")
public class RejectedRecordReportController {
    private static final String AUDIT_VIEW = "hasAuthority('PERM_AUDIT_VIEW')";
    private final RejectedRecordReportService reports;

    public RejectedRecordReportController(RejectedRecordReportService reports) {
        this.reports = reports;
    }

    @Operation(operationId = "listFinalRejectedRecords",
            summary = "Audit report: records rejected for good, newest decision first (identifiers masked)")
    @GetMapping("/audit-reports/final-rejected")
    @PreAuthorize(AUDIT_VIEW)
    @Audited(action = "FINAL_REJECTED_REPORT_VIEW", resource = "AUDIT_REPORT")
    public Page<RejectedRow> list(@RequestParam(required = false) UUID batch,
                                  @RequestParam(required = false) String source,
                                  @RequestParam(required = false) String department,
                                  @RequestParam(required = false) String state,
                                  @Parameter(description = "Reject reason code, for example SOURCE_ERROR")
                                  @RequestParam(required = false) String reason,
                                  @RequestParam(required = false) String decidedBy,
                                  @Parameter(description = "First day of the decision, ISO date (UTC)")
                                  @RequestParam(required = false) String from,
                                  @Parameter(description = "Last day of the decision, ISO date (UTC)")
                                  @RequestParam(required = false) String to,
                                  @Parameter(description = "Show identifiers unmasked; needs RESIDENT_VIEW_FULL")
                                  @RequestParam(defaultValue = "false") boolean unmasked,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size) {
        requireUnmaskAllowed(unmasked);
        auditFilter(batch, source, department, state, reason, decidedBy, from, to, unmasked);
        return reports.rejected(batch, source, department, state, reason, decidedBy, from, to, unmasked, page, size);
    }

    @Operation(operationId = "getFinalRejectedSummary",
            summary = "Counts of final-rejected records and the rejections per source per week (last twelve weeks)")
    @GetMapping("/audit-reports/final-rejected/summary")
    @PreAuthorize(AUDIT_VIEW)
    @Audited(action = "FINAL_REJECTED_REPORT_VIEW", resource = "AUDIT_REPORT")
    public Summary summary() {
        AuditContext.detail("view", "summary");
        return reports.summary();
    }

    @Operation(operationId = "exportFinalRejectedRecords",
            summary = "The filtered final-rejected records as CSV (at most 10,000; masked unless unmasked=true)")
    @GetMapping(value = "/audit-reports/final-rejected/export", produces = "text/csv")
    @PreAuthorize(AUDIT_VIEW + " and hasAuthority('PERM_REPORT_EXPORT')")
    @Audited(action = "FINAL_REJECTED_REPORT_EXPORT", resource = "AUDIT_REPORT")
    public void export(@RequestParam(required = false) UUID batch, @RequestParam(required = false) String source,
                       @RequestParam(required = false) String department, @RequestParam(required = false) String state,
                       @RequestParam(required = false) String reason, @RequestParam(required = false) String decidedBy,
                       @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                       @Parameter(description = "Identifiers unmasked; needs RESIDENT_VIEW_FULL")
                       @RequestParam(defaultValue = "false") boolean unmasked, HttpServletResponse response)
            throws IOException {
        requireUnmaskAllowed(unmasked);
        auditFilter(batch, source, department, state, reason, decidedBy, from, to, unmasked);
        // Validate the filter before the first byte is written, so a bad date is a 400 and not a half-sent file.
        reports.rejected(batch, source, department, state, reason, decidedBy, from, to, unmasked, 0, 1);
        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"uddp-final-rejected-records.csv\"");
        PrintWriter out = response.getWriter();
        out.println("reference,source,batch,state,department,reasonCode,note,originalFailureReasons,decidedBy,decidedAt,"
                + "reopened,currentStatus,payloadPurged");
        AtomicInteger rows = new AtomicInteger();
        reports.export(batch, source, department, state, reason, decidedBy, from, to, unmasked, row -> {
            out.println(String.join(",", Csv.cell(row.reference()), Csv.cell(row.sourceSystem()),
                    Csv.cell(String.valueOf(row.batchId())), Csv.cell(row.stateCode()), Csv.cell(row.department()),
                    Csv.cell(row.reasonCode()), Csv.cell(row.note()), Csv.cell(reasonSummary(row)),
                    Csv.cell(row.decidedBy()), Csv.cell(String.valueOf(row.decidedAt())),
                    row.reopened() ? "yes" : "no", Csv.cell(row.currentStatus()), row.payloadPurged() ? "yes" : "no"));
            rows.incrementAndGet();
        });
        AuditContext.detail("rows", rows.get());
    }

    @Operation(operationId = "getBatchRejectionReport",
            summary = "The batch's held and rejected rows as CSV for the source department (values masked)")
    @GetMapping(value = "/batches/{batchId}/rejection-report", produces = "text/csv")
    @PreAuthorize("hasAuthority('PERM_BATCH_VIEW')")
    @Audited(action = "BATCH_REJECTION_REPORT_EXPORT", resource = "BATCH", idVariable = "batchId")
    public void rejectionReport(@PathVariable UUID batchId, HttpServletResponse response) throws IOException {
        List<FeedbackRow> rows = reports.batchRejectionReport(batchId);
        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"rejection-report-" + batchId + ".csv\"");
        PrintWriter out = response.getWriter();
        out.println("rowNumber,reasonCode,reasonText,field,maskedValue,status");
        for (FeedbackRow row : rows) {
            out.println(String.join(",", String.valueOf(row.rowNumber()), Csv.cell(row.reasonCode()),
                    Csv.cell(row.reasonText()), Csv.cell(row.field()), Csv.cell(row.maskedValue()),
                    Csv.cell(row.status())));
        }
        AuditContext.detail("rows", rows.size());
    }

    private static String reasonSummary(RejectedRow row) {
        return row.originalReasons().stream()
                .map(reason -> reason.field() == null ? reason.code() : reason.code() + " (" + reason.field() + ")")
                .collect(Collectors.joining("; "));
    }

    private static void requireUnmaskAllowed(boolean unmasked) {
        if (unmasked && !CurrentUser.has(Permission.RESIDENT_VIEW_FULL)) {
            throw new AccessDeniedException("Unmasked identifiers need the permission to view personal data in full");
        }
    }

    private static void auditFilter(UUID batch, String source, String department, String state, String reason,
                                    String decidedBy, String from, String to, boolean unmasked) {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("batch", String.valueOf(batch));
        filter.put("source", String.valueOf(source));
        filter.put("department", String.valueOf(department));
        filter.put("state", String.valueOf(state));
        filter.put("reason", String.valueOf(reason));
        filter.put("decidedBy", String.valueOf(decidedBy));
        filter.put("from", String.valueOf(from));
        filter.put("to", String.valueOf(to));
        AuditContext.detail("filter", filter);
        AuditContext.detail("unmasked", unmasked);
    }
}
