package gov.uddp.core.held;

import gov.uddp.core.validation.ValidationIssue.Severity;

/**
 * One reason a record is held. {@code severity} decides what a steward may do: ERROR reasons can only be cleared by a
 * correction, SEND_TO_STEWARD (and the WARNING-class low-confidence and invalid-identifier reasons) can also be
 * released as they are. {@code fix} is where in the pack the cause is fixed; reasons stored before it existed get the
 * target their code implies.
 */
public record HeldReason(String code, String message, String field, Severity severity, FixTarget fix) {
    public HeldReason {
        if (fix == null) {
            fix = FixTarget.of(code, field);
        }
    }

    public HeldReason(String code, String message, String field, Severity severity) {
        this(code, message, field, severity, null);
    }

    public HeldReason withFix(FixTarget target) {
        return new HeldReason(code, message, field, severity, target);
    }

    /** A hard error: only a correction releases the record. */
    public boolean blocksOverride() {
        return severity == Severity.ERROR;
    }
}
