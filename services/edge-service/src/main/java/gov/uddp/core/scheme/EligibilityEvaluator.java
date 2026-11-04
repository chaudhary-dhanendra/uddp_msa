package gov.uddp.core.scheme;

import gov.uddp.core.scheme.SchemeCatalogue.Eligibility;
import java.time.LocalDate;
import java.time.Period;

/**
 * Evaluates a scheme's declarative eligibility (ADR-0012) for one resident: age in whole years on {@code asOf} and
 * gender. A criterion whose value is unknown makes the outcome {@link Outcome#UNDETERMINED} unless another known
 * criterion already fails.
 */
public final class EligibilityEvaluator {
    public enum Outcome { ELIGIBLE, NOT_ELIGIBLE, UNDETERMINED }

    private EligibilityEvaluator() {
    }

    public static Outcome evaluate(Eligibility rule, LocalDate dateOfBirth, String gender, LocalDate asOf) {
        boolean unknown = false;
        if (rule.minAgeYears() != null || rule.maxAgeYears() != null) {
            if (dateOfBirth == null) {
                unknown = true;
            } else {
                int age = ageYears(dateOfBirth, asOf);
                if ((rule.minAgeYears() != null && age < rule.minAgeYears())
                        || (rule.maxAgeYears() != null && age > rule.maxAgeYears())) {
                    return Outcome.NOT_ELIGIBLE;
                }
            }
        }
        if (rule.genders() != null && !rule.genders().isEmpty()) {
            if (gender == null || gender.isBlank() || "UNKNOWN".equals(gender)) {
                unknown = true;
            } else if (!rule.genders().contains(gender)) {
                return Outcome.NOT_ELIGIBLE;
            }
        }
        return unknown ? Outcome.UNDETERMINED : Outcome.ELIGIBLE;
    }

    public static int ageYears(LocalDate dateOfBirth, LocalDate asOf) {
        return Period.between(dateOfBirth, asOf).getYears();
    }
}
