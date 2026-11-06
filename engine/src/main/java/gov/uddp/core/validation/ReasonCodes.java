package gov.uddp.core.validation;

/**
 * Why a delivered row was rejected or held. A code is stable and machine-readable; the message beside it is for people.
 * A failed validation rule is {@code RULE_FAILED:<ruleId>}.
 */
public final class ReasonCodes {
    public static final String UNREADABLE_DATE = "UNREADABLE_DATE";
    public static final String MISSING_NAME = "MISSING_NAME";
    public static final String ID_FORMAT = "ID_FORMAT";
    public static final String UNMAPPED_CODE = "UNMAPPED_CODE";
    public static final String DUPLICATE_SOURCE_KEY = "DUPLICATE_SOURCE_KEY";
    public static final String LOW_CONFIDENCE = "LOW_CONFIDENCE";
    /** The delivered row cannot be read as a record of its source (wrong number of columns, broken quoting). */
    public static final String UNREADABLE_ROW = "UNREADABLE_ROW";
    public static final String OTHER = "OTHER";
    public static final String RULE_FAILED_PREFIX = "RULE_FAILED:";

    private ReasonCodes() {
    }

    public static String ruleFailed(String ruleId) {
        return RULE_FAILED_PREFIX + (ruleId == null || ruleId.isBlank() ? "unnamed" : ruleId);
    }

    /** The code a failure carries: the one its raise point named, else OTHER. */
    public static String of(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ReasonedException reasoned) {
                return reasoned.code();
            }
        }
        return OTHER;
    }

    /** The reason code and raise-point context ({@link ReasonedException#context()}) of a failure, if it has them. */
    public static java.util.Map<String, String> contextOf(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ReasonedException reasoned) {
                return reasoned.context();
            }
        }
        return java.util.Map.of();
    }

    /**
     * An {@link IllegalArgumentException} that names its reason code where it is raised. Its context names where the
     * fix belongs ({@code sourceField}, {@code target}, {@code crosswalk}) and, for an unmapped code or an unreadable
     * date, the offending {@code value}, used server-side to suggest a fix and never returned as is.
     */
    public static final class ReasonedException extends IllegalArgumentException {
        private final String code;
        private final java.util.Map<String, String> context = new java.util.LinkedHashMap<>();

        public ReasonedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public ReasonedException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }

        /** Adds a context entry (a null value is left out); returns this exception for chaining at the raise point. */
        public ReasonedException with(String key, String value) {
            if (value != null) {
                context.put(key, value);
            }
            return this;
        }

        public java.util.Map<String, String> context() {
            return java.util.Collections.unmodifiableMap(context);
        }
    }
}
