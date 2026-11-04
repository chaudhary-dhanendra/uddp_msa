package gov.uddp.core.rules;

/** A maker-checker step that does not fit the version's current status, for example approving a DRAFT (HTTP 409). */
public class ApprovalConflictException extends IllegalStateException {
    public ApprovalConflictException(String message) {
        super(message);
    }
}
