package gov.uddp.core.api;

import gov.uddp.core.validation.DqBatchSummary;
import gov.uddp.core.validation.DqSummaryService;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/dq")
public class DqController {
    private final DqSummaryService service;
    public DqController(DqSummaryService service) { this.service=service; }
    @PreAuthorize("hasAuthority('PERM_DATA_QUALITY_VIEW') and @access.batchInScope(#batchId)")
    @GetMapping("/batches/{batchId}") public DqBatchSummary batch(@PathVariable UUID batchId) {
        try{return service.summary(batchId);}catch(NoSuchElementException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"DQ batch evaluation does not exist");}
    }
}
