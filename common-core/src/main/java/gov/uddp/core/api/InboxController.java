package gov.uddp.core.api;

import gov.uddp.core.inbox.ExceptionInboxService;
import gov.uddp.core.inbox.ExceptionInboxService.Entry;
import gov.uddp.core.inbox.ExceptionInboxService.Filter;
import gov.uddp.core.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The exception inbox (Track C4): everything that needs a person, within the caller's department scope. */
@RestController
@RequestMapping("/api/inbox")
@Tag(name = "Inbox")
public class InboxController {
    private final ExceptionInboxService inbox;

    public InboxController(ExceptionInboxService inbox) {
        this.inbox = inbox;
    }

    @GetMapping("/items")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
    public List<Entry> items(@RequestParam(required = false) String department,
                             @RequestParam(required = false) String type,
                             @RequestParam(required = false) String priority,
                             @RequestParam(required = false) Integer minAgeDays) {
        return inbox.list(new Filter(department, type, priority, minAgeDays), state -> CurrentUser.scope().allows(state));
    }

    /** Count per type for the Overview, for the same scope. */
    @GetMapping("/counts")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
    public Map<String, Integer> counts(@RequestParam(required = false) String department) {
        return inbox.counts(new Filter(department, null, null, null), state -> CurrentUser.scope().allows(state));
    }
}
