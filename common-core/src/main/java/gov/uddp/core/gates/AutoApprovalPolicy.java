package gov.uddp.core.gates;

import gov.uddp.core.security.AuditService;
import gov.uddp.core.security.AuditService.AuditEvent;
import gov.uddp.core.statepack.LoadedStatePack;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * When a change may go ahead without a human approver (Track C3). Routine pack edits that pass every check are
 * auto-approved and audited as AUTO_APPROVED. A new source, a failed check, or a change to matching or eligibility
 * always goes to the existing maker-checker.
 */
@Service
public class AutoApprovalPolicy {
    public static final String AUTO_APPROVED = "AUTO_APPROVED";

    public record Decision(boolean autoApproved, List<String> reasons) {
    }

    private final List<SensitiveChangeDetector> detectors;
    private final AuditService audit;

    @Autowired
    public AutoApprovalPolicy(ObjectProvider<SensitiveChangeDetector> detectors, AuditService audit) {
        this(detectors.orderedStream().toList(), audit);
    }

    public AutoApprovalPolicy(List<SensitiveChangeDetector> detectors, AuditService audit) {
        this.detectors = detectors;
        this.audit = audit;
    }

    /**
     * @param previous the source's currently active pack, empty for a first publication (a new source)
     * @param checksPassed whether every gate on the edit (the publish preview) passed
     */
    public Decision packEdit(Optional<LoadedStatePack> previous, LoadedStatePack next, boolean checksPassed) {
        List<String> reasons = new ArrayList<>();
        if (previous.isEmpty()) {
            reasons.add("new source: first publication");
        }
        if (!checksPassed) {
            reasons.add("a quality gate failed");
        }
        previous.ifPresent(old -> detectors.forEach(d -> d.sensitiveChange(old, next).ifPresent(reasons::add)));
        return new Decision(reasons.isEmpty(), List.copyOf(reasons));
    }

    public void recordAutoApproved(String resourceType, String resourceId, String stateCode, String submittedBy) {
        audit.record(new AuditEvent("SYSTEM", AUTO_APPROVED, resourceType, resourceId, stateCode, AuditService.SUCCESS,
                AuditService.details("submittedBy", submittedBy)));
    }
}
