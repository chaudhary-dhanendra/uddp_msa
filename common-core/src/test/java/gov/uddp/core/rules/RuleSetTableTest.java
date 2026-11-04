package gov.uddp.core.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.rules.DecisionTableDmn.DecisionRow;
import gov.uddp.core.rules.RuleSet.OwnerType;
import gov.uddp.core.rules.RuleSet.Type;
import gov.uddp.core.testsupport.TestDatabase;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Tracks A3/A5: Rules Studio tables become DRAFT DMN rule sets, testable before approval, with generated test cases. */
class RuleSetTableTest {
    @Test
    void aTableBecomesADraftThatCanBeTestedWithGeneratedCases() {
        var rules = new RuleSetService(new RuleSetStore(TestDatabase.freshMigrated()));
        RuleSet draft = rules.createFromTable(new RuleSetService.TableDraft(Type.ELIGIBILITY, OwnerType.SCHEME,
                "SCHEME-X", LocalDate.of(2026, 1, 1), null, List.of(
                        new DecisionRow("age >= 60 and gender = \"FEMALE\"", "ELIGIBLE", "GO para 2"),
                        new DecisionRow("age < 60", "NOT_ELIGIBLE", "GO para 3"))), "maker");

        assertEquals(RuleSet.Status.DRAFT, draft.status());
        assertEquals(List.of("age", "gender"), draft.requiredAttributes());
        var fired = rules.evaluateById(draft.id(), Map.of("age", 61, "gender", "FEMALE")).firedRules();
        assertEquals(List.of("GO para 2"), fired.stream().map(RuleSetService.FiredRule::clauseReference).toList());

        Map<String, RuleTestCaseGenerator.TestCase> cases = rules.testCases(draft.id()).stream()
                .collect(Collectors.toMap(RuleTestCaseGenerator.TestCase::name, Function.identity(), (a, b) -> a));
        var below = cases.get("age = 59 (boundary of age >= 60)");
        var at = cases.get("age = 60 (boundary of age >= 60)");
        assertEquals(List.of("NOT_ELIGIBLE"), below.outcomes());
        assertEquals(List.of("ELIGIBLE"), at.outcomes());
        assertEquals(List.of("GO para 2"), at.firedClauses());
        assertTrue(cases.containsKey("gender = \"OTHER\" (not gender = \"FEMALE\")"));
        var missing = cases.get("age missing");
        assertTrue(missing.outcomes().isEmpty(), "missing data fires no rule");
        assertTrue(cases.values().stream().allMatch(c -> c.error() == null));

        assertThrows(IllegalArgumentException.class, () -> rules.createFromTable(new RuleSetService.TableDraft(
                Type.ELIGIBILITY, OwnerType.SCHEME, "SCHEME-X", LocalDate.of(2026, 1, 1), null,
                List.of(new DecisionRow("age >= 60", "ELIGIBLE", " "))), "maker"), "a clause reference is required");
    }
}
