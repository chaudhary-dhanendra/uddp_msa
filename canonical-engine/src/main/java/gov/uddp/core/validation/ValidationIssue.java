package gov.uddp.core.validation;

/** One failed State Pack validation rule on a canonical field. */
public record ValidationIssue(String field, String rule, Severity severity, String message) {
    /**
     * ERROR rejects a row that cannot be corrected, or holds one a steward can; WARNING is stored against the
     * canonical record; SEND_TO_STEWARD holds the row for a steward who may correct it or release it as it is.
     */
    public enum Severity { ERROR, WARNING, SEND_TO_STEWARD }

    @Override
    public String toString() {
        return field + " " + rule + " (" + severity.name().toLowerCase() + "): " + message;
    }
}
