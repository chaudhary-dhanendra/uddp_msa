package gov.uddp.core.api.v1;

import gov.uddp.core.read.AuditReadService;
import gov.uddp.core.read.AuditReadService.AuditEventView;
import gov.uddp.core.read.Page;
import gov.uddp.core.security.AuditContext;
import gov.uddp.core.security.Audited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit trail for auditors and platform administrators (ADR-0020): search and CSV export. */
@RestController
@RequestMapping("/api/v1/audit-events")
@Tag(name = "Audit")
public class AuditController {
    private final AuditReadService audit;

    public AuditController(AuditReadService audit) {
        this.audit = audit;
    }

    @Operation(operationId = "listAuditEvents", summary = "Audit events, newest first, filterable by user, action, result, state and date")
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_AUDIT_VIEW')")
    public Page<AuditEventView> events(@Parameter(description = "Username contains") @RequestParam(required = false) String actor,
                                       @RequestParam(required = false) String action,
                                       @Parameter(description = "SUCCESS, DENIED or FAILURE") @RequestParam(required = false) String result,
                                       @RequestParam(required = false) String state,
                                       @Parameter(description = "First day, ISO date (UTC)") @RequestParam(required = false) String from,
                                       @Parameter(description = "Last day, ISO date (UTC)") @RequestParam(required = false) String to,
                                       @Parameter(description = ReadApiControllers.PAGE) @RequestParam(required = false) Integer page,
                                       @Parameter(description = ReadApiControllers.SIZE) @RequestParam(required = false) Integer size,
                                       @Parameter(description = ReadApiControllers.SORT + "; occurredAt, action, actor, stateCode")
                                       @RequestParam(required = false) String sort) {
        return audit.events(actor, action, result, state, from, to, page, size, sort);
    }

    @Operation(operationId = "listAuditActions", summary = "The actions recorded so far")
    @GetMapping("/actions")
    @PreAuthorize("hasAuthority('PERM_AUDIT_VIEW')")
    public List<String> actions() {
        return audit.actions();
    }

    @Operation(operationId = "exportAuditEvents", summary = "The filtered audit events as CSV (at most 10,000)")
    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("hasAuthority('PERM_AUDIT_VIEW') and hasAuthority('PERM_REPORT_EXPORT')")
    @Audited(action = "AUDIT_EXPORT", resource = "AUDIT_TRAIL")
    public void export(@RequestParam(required = false) String actor, @RequestParam(required = false) String action,
                       @RequestParam(required = false) String result, @RequestParam(required = false) String state,
                       @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                       HttpServletResponse response) throws IOException {
        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"uddp-audit-events.csv\"");
        PrintWriter out = response.getWriter();
        out.println("occurredAt,actor,action,resourceType,resourceId,stateCode,result,metadata");
        AtomicInteger rows = new AtomicInteger();
        audit.export(actor, action, result, state, from, to, event -> {
            out.println(String.join(",", Csv.cell(String.valueOf(event.occurredAt())), Csv.cell(event.actor()),
                    Csv.cell(event.action()), Csv.cell(event.resourceType()), Csv.cell(event.resourceId()),
                    Csv.cell(event.stateCode()), Csv.cell(event.result()),
                    Csv.cell(event.metadata() == null ? null : event.metadata().toString())));
            rows.incrementAndGet();
        });
        AuditContext.detail("rows", rows.get());
        AuditContext.detail("filter", java.util.Map.of("actor", String.valueOf(actor), "action", String.valueOf(action),
                "state", String.valueOf(state), "from", String.valueOf(from), "to", String.valueOf(to)));
    }
}
