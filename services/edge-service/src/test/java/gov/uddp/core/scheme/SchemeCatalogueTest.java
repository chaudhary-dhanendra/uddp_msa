package gov.uddp.core.scheme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.scheme.EligibilityEvaluator.Outcome;
import gov.uddp.core.scheme.SchemeCatalogue.Eligibility;
import gov.uddp.core.testsupport.SampleData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The platform scheme catalogue (M15, ADR-0011) and its minimal declarative eligibility (ADR-0012). */
class SchemeCatalogueTest {
    @TempDir
    Path work;

    @Test
    void thePlatformCatalogueLoadsWithDepartmentsDefaultsAndGroups() {
        var properties = new StatePackProperties();
        properties.setPlatformDirectory(SampleData.REPOSITORY_ROOT.resolve("platform").toString());
        var catalogue = new SchemeCatalogue(properties);

        assertEquals("1.0.0", catalogue.version());
        assertEquals(List.of("PDS-RATION", "OLD-AGE-PENSION", "DISABILITY-PENSION", "WIDOW-PENSION"),
                catalogue.schemes().stream().map(SchemeCatalogue.Scheme::schemeId).toList());
        var pds = catalogue.scheme("PDS-RATION").orElseThrow();
        assertEquals("FAMILY", pds.beneficiaryLevel());
        assertEquals("KG", pds.unit());
        assertEquals("PDS-RATION", pds.group(), "a scheme without a group is its own group");
        assertEquals("Food and Civil Supplies", catalogue.department(pds.department()).orElseThrow().name());
        var pension = catalogue.scheme("OLD-AGE-PENSION").orElseThrow();
        assertEquals("SOCIAL-PENSION", pension.group());
        assertEquals(catalogue.scheme("DISABILITY-PENSION").orElseThrow().group(), pension.group());
        assertEquals(45, pension.eligibility().minAgeYears());
        assertTrue(catalogue.scheme("UNKNOWN").isEmpty());
    }

    @Test
    void anInvalidCatalogueIsRejectedWithItsReason() throws Exception {
        String department = "departments: [{ code: D, name: Dept }]\n";
        String scheme = "  - { schemeId: S, name: S, department: D, benefitType: ENTITLEMENT, benefitForm: CASH,"
                + " beneficiaryLevel: RESIDENT%s }\n";
        String head = "catalogueVersion: 1\n" + department + "schemes:\n";
        String[][] cases = {
            {department + "schemes:\n" + scheme.formatted(""), "catalogueVersion"},
            {head + scheme.formatted("") + scheme.formatted(""), "duplicate scheme S"},
            {head + scheme.formatted("").replace("department: D", "department: X"), "unknown department X"},
            {head + scheme.formatted("").replace("CASH", "VOUCHER"), "benefitForm"},
            {head + scheme.formatted(", frequency: DAILY"), "frequency"},
            {head + scheme.formatted(", eligibility: { minAgeYears: 60 }").replace("RESIDENT", "FAMILY"),
                "RESIDENT-level schemes only"},
            {head + scheme.formatted(", eligibility: { minAgeYears: 70, maxAgeYears: 60 }"), "age range"},
            {head + scheme.formatted(", eligibility: {}"), "no criterion"},
            {head + scheme.formatted(", eligibility: { incomeBelow: 10000 }"), "incomeBelow"},
        };
        for (String[] c : cases) {
            Path file = Files.writeString(work.resolve("catalogue-" + System.nanoTime() + ".yaml"), c[0]);
            var error = assertThrows(IllegalStateException.class, () -> new SchemeCatalogue(file), c[1]);
            assertTrue(error.getMessage().contains(c[1]), c[1] + " <- " + error.getMessage());
        }
    }

    @Test
    void eligibilityUsesWholeYearsOnTheAsOfDateAndGender() {
        var age = new Eligibility(45, 59, null);
        LocalDate born = LocalDate.of(1980, 3, 12);
        assertEquals(Outcome.NOT_ELIGIBLE, EligibilityEvaluator.evaluate(age, born, "MALE", LocalDate.of(2025, 3, 11)));
        assertEquals(Outcome.ELIGIBLE, EligibilityEvaluator.evaluate(age, born, "MALE", LocalDate.of(2025, 3, 12)));
        assertEquals(Outcome.ELIGIBLE, EligibilityEvaluator.evaluate(age, born, "MALE", LocalDate.of(2040, 3, 11)));
        assertEquals(Outcome.NOT_ELIGIBLE, EligibilityEvaluator.evaluate(age, born, "MALE", LocalDate.of(2040, 3, 12)));
        assertEquals(Outcome.UNDETERMINED, EligibilityEvaluator.evaluate(age, null, "MALE", LocalDate.of(2026, 1, 1)));

        var women = new Eligibility(null, null, List.of("FEMALE"));
        assertEquals(Outcome.ELIGIBLE, EligibilityEvaluator.evaluate(women, null, "FEMALE", LocalDate.of(2026, 1, 1)));
        assertEquals(Outcome.NOT_ELIGIBLE, EligibilityEvaluator.evaluate(women, born, "MALE", LocalDate.of(2026, 1, 1)));
        assertEquals(Outcome.UNDETERMINED, EligibilityEvaluator.evaluate(women, born, "UNKNOWN", LocalDate.of(2026, 1, 1)));
        // A known failing criterion decides even when another value is unknown.
        var both = new Eligibility(60, null, List.of("FEMALE"));
        assertEquals(Outcome.NOT_ELIGIBLE, EligibilityEvaluator.evaluate(both, born, null, LocalDate.of(2026, 1, 1)));
    }
}
