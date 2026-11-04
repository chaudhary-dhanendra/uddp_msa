package gov.uddp.core.validation;

import java.util.List;
import java.util.stream.Collectors;

/** An error-severity State Pack validation rule failed; the canonical record is not persisted. */
public class CanonicalValidationException extends RuntimeException {
    private final List<ValidationIssue> issues;

    public CanonicalValidationException(List<ValidationIssue> issues) {
        super("Validation failed: " + issues.stream().map(ValidationIssue::toString).collect(Collectors.joining("; ")));
        this.issues = List.copyOf(issues);
    }

    public List<ValidationIssue> issues() {
        return issues;
    }
}
