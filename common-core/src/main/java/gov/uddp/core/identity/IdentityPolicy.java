package gov.uddp.core.identity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * The per-deployment Identity Policy (Track E3), an ordered ladder over registry identifier types:
 * <ol>
 *   <li>the same value of a {@code deterministicTypes} type auto-matches, unless the date of birth differs by more
 *       than {@code dobToleranceDays} or (with {@code genderMustAgree}) the genders differ, which sends it to review;</li>
 *   <li>different values of a {@code neverMergeTypes} type never merge (a CANNOT_LINK constraint);</li>
 *   <li>{@code evidenceTypes} feed probabilistic matching as exact-comparison evidence;</li>
 *   <li>{@code familyAnchorType} (a HOUSEHOLD type) anchors Golden Families: households sharing its value are one
 *       family, instead of one family per source household;</li>
 *   <li>a pair where neither record has a policy identifier is probabilistic only, and auto-matches only at
 *       {@code noIdentifierAutoMatch} or above.</li>
 * </ol>
 * {@code baseSource} ({@code source} or {@code STATE:source}) is the trusted register of a first load: on the first
 * matching run, a probabilistic auto-match that involves no base-source record goes to review instead.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IdentityPolicy(List<String> deterministicTypes, List<String> neverMergeTypes, List<String> evidenceTypes,
                             String familyAnchorType, Integer dobToleranceDays, Boolean genderMustAgree,
                             Double noIdentifierAutoMatch, String baseSource) {
    public static final int DEFAULT_DOB_TOLERANCE_DAYS = 366;

    public IdentityPolicy {
        deterministicTypes = upper(deterministicTypes);
        neverMergeTypes = upper(neverMergeTypes);
        evidenceTypes = upper(evidenceTypes);
        familyAnchorType = familyAnchorType == null || familyAnchorType.isBlank() ? null
                : familyAnchorType.strip().toUpperCase(java.util.Locale.ROOT);
        dobToleranceDays = dobToleranceDays == null ? DEFAULT_DOB_TOLERANCE_DAYS : dobToleranceDays;
        genderMustAgree = genderMustAgree == null || genderMustAgree;
        baseSource = baseSource == null || baseSource.isBlank() ? null : baseSource.strip();
        if (dobToleranceDays < 0) {
            throw new IllegalArgumentException("dobToleranceDays cannot be negative");
        }
        if (noIdentifierAutoMatch != null && (noIdentifierAutoMatch <= 0 || noIdentifierAutoMatch > 1)) {
            throw new IllegalArgumentException("noIdentifierAutoMatch must be in (0, 1]");
        }
    }

    /** Every identifier type the policy names for persons, in ladder order, without repeats. */
    public List<String> personTypes() {
        List<String> out = new ArrayList<>();
        for (List<String> list : List.of(deterministicTypes, neverMergeTypes, evidenceTypes)) {
            list.stream().filter(code -> !out.contains(code)).forEach(out::add);
        }
        return out;
    }

    /** Checks every named type against the registry: subject, trust and usage flags must fit its rung. */
    public IdentityPolicy validate(IdentifierTypeRegistry registry) {
        for (String code : deterministicTypes) {
            var type = registry.require(code);
            if (type.subject() != IdentifierType.Subject.PERSON || type.trust() != IdentifierType.Trust.STRONG
                    || !type.uses(IdentifierType.Usage.DETERMINISTIC_MATCH)) {
                throw new IllegalArgumentException(code + " cannot match deterministically: it must be a STRONG PERSON"
                        + " identifier flagged DETERMINISTIC_MATCH");
            }
        }
        for (String code : neverMergeTypes) {
            var type = registry.require(code);
            if (type.subject() != IdentifierType.Subject.PERSON || !type.unique()) {
                throw new IllegalArgumentException(code + " cannot block merges: it must be a unique PERSON identifier");
            }
        }
        for (String code : evidenceTypes) {
            var type = registry.require(code);
            if (type.subject() != IdentifierType.Subject.PERSON || !type.uses(IdentifierType.Usage.MATCH_EVIDENCE)) {
                throw new IllegalArgumentException(code + " cannot be match evidence: it must be a PERSON identifier"
                        + " flagged MATCH_EVIDENCE");
            }
        }
        if (familyAnchorType != null) {
            var type = registry.require(familyAnchorType);
            if (type.subject() != IdentifierType.Subject.HOUSEHOLD || !type.uses(IdentifierType.Usage.FAMILY_ANCHOR)) {
                throw new IllegalArgumentException(familyAnchorType + " cannot anchor families: it must be a HOUSEHOLD"
                        + " identifier flagged FAMILY_ANCHOR");
            }
        }
        return this;
    }

    /** True when a record of {@code stateCode}/{@code source} is from the base source. */
    public boolean isBaseSource(String stateCode, String source) {
        if (baseSource == null) {
            return false;
        }
        int colon = baseSource.indexOf(':');
        return colon < 0 ? baseSource.equals(source)
                : baseSource.substring(0, colon).equals(stateCode) && baseSource.substring(colon + 1).equals(source);
    }

    private static List<String> upper(List<String> codes) {
        return codes == null ? List.of() : codes.stream().filter(code -> code != null && !code.isBlank())
                .map(code -> code.strip().toUpperCase(java.util.Locale.ROOT)).distinct().toList();
    }
}
