package gov.uddp.shaping.admin;

import gov.uddp.core.security.AuditService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Decides shaping changes (word lists and plug-in approvals) and applies the approved ones. */
@Service
public class ShapingAdminService {
    private final ShapingChanges changes;
    private final WordListService wordLists;
    private final PluginAdminService plugins;
    private final AuditService audit;

    public ShapingAdminService(ShapingChanges changes, WordListService wordLists, PluginAdminService plugins,
                               AuditService audit) {
        this.changes = changes;
        this.wordLists = wordLists;
        this.plugins = plugins;
        this.audit = audit;
    }

    public List<ShapingChanges.Change> changes(String status, String kind) {
        return changes.list(status, kind);
    }

    /** Approves a change (never by its author) and applies it. */
    public ShapingChanges.Change approve(UUID id, String approver, String reason) {
        ShapingChanges.Change approved = changes.approve(id, approver, reason);
        if (ShapingChanges.WORD_LIST.equals(approved.kind())) {
            wordLists.apply(approved);
            audit.record(AuditService.AuditEvent.of("SHAPING_WORD_LIST_APPLIED", "SHAPING_WORD_LIST", approved.target(),
                    null, AuditService.SUCCESS).by(approver).with(Map.of("proposedBy", approved.createdBy())));
        } else {
            plugins.apply(approved, approver);
        }
        return approved;
    }

    public ShapingChanges.Change reject(UUID id, String approver, String reason) {
        return changes.reject(id, approver, reason);
    }
}
