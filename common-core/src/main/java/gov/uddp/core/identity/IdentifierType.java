package gov.uddp.core.identity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One identifier type of the registry (Track E1): what it identifies, how far its values are unique, how much a match
 * on it is trusted, how a value is validated, normalised and stored, and what the platform may use it for. Every
 * identifier behaviour comes from these fields; no code names a particular type. {@code partialOf} names the full
 * type whose fragment (last four digits, a masked number) this type holds, so full and partial sources can compare.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IdentifierType(String code, String label, Subject subject, Scope scope, Trust trust,
                             Validation validation, List<Normalisation> normalisation, Storage storage,
                             Set<Usage> usage, Boolean unique, Boolean displayLast4, Boolean sensitivePattern,
                             String detection, String partialOf) {
    public enum Subject { PERSON, HOUSEHOLD }

    /** NATIONAL values are unique everywhere; ISSUER values only within their issuer, which joins the stored key. */
    public enum Scope { NATIONAL, ISSUER }

    public enum Trust { STRONG, MEDIUM, WEAK }

    public enum Storage { KEYED_HASH, VAULT_REF, PLAIN }

    public enum Usage { DETERMINISTIC_MATCH, MATCH_EVIDENCE, FAMILY_ANCHOR, DISPLAY_ONLY }

    public enum Normalisation { TRIM, REMOVE_SEPARATORS, UPPERCASE, LOWERCASE, STRIP_LEADING_ZEROS }

    /** A normalised value must match {@code pattern} (whole value), fit the lengths and pass the named checksum. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Validation(String pattern, Integer minLength, Integer maxLength, String checksum) {
    }

    public IdentifierType(String code, String label, Subject subject, Scope scope, Trust trust, Validation validation,
                          List<Normalisation> normalisation, Storage storage, Set<Usage> usage, Boolean unique,
                          Boolean displayLast4, Boolean sensitivePattern, String detection) {
        this(code, label, subject, scope, trust, validation, normalisation, storage, usage, unique, displayLast4,
                sensitivePattern, detection, null);
    }

    public IdentifierType {
        if (code == null || !code.strip().matches("[A-Z][A-Z0-9_]{1,39}")) {
            throw new IllegalArgumentException("An identifier type code is 2-40 capital letters, digits or _");
        }
        code = code.strip();
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Identifier type " + code + " needs a label");
        }
        if (subject == null || scope == null || trust == null || storage == null) {
            throw new IllegalArgumentException("Identifier type " + code + " needs subject, scope, trust and storage");
        }
        validation = validation == null ? new Validation(null, null, null, null) : validation;
        normalisation = normalisation == null ? List.of() : List.copyOf(normalisation);
        usage = usage == null ? Set.of() : Set.copyOf(usage);
        unique = Boolean.TRUE.equals(unique);
        displayLast4 = Boolean.TRUE.equals(displayLast4);
        sensitivePattern = Boolean.TRUE.equals(sensitivePattern);
        if (sensitivePattern && (detection == null || detection.isBlank())) {
            throw new IllegalArgumentException("Identifier type " + code + " is sensitivePattern but has no detection");
        }
        partialOf = partialOf == null || partialOf.isBlank() ? null : partialOf.strip();
        if (partialOf != null && partialOf.equals(code)) {
            throw new IllegalArgumentException("Identifier type " + code + " cannot be a partial of itself");
        }
        if (storage == Storage.PLAIN && sensitivePattern) {
            throw new IllegalArgumentException("Identifier type " + code + " is sensitive and cannot be stored PLAIN");
        }
    }

    public boolean uses(Usage flag) {
        return usage.contains(flag);
    }

    /** Applies the configured normalisation steps in order. */
    public String normalise(String value) {
        String out = value == null ? "" : value;
        for (Normalisation step : normalisation) {
            out = switch (step) {
                case TRIM -> out.strip();
                case REMOVE_SEPARATORS -> out.replaceAll("[\\s\\-./_]", "");
                case UPPERCASE -> out.toUpperCase(Locale.ROOT);
                case LOWERCASE -> out.toLowerCase(Locale.ROOT);
                case STRIP_LEADING_ZEROS -> out.replaceFirst("^0+(?=.)", "");
            };
        }
        return out;
    }
}
