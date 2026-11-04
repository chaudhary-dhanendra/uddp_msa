package gov.uddp.core.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gov.uddp.core.rules.RuleSet.OwnerType;
import gov.uddp.core.rules.RuleSet.Status;
import gov.uddp.core.rules.RuleSet.Type;
import gov.uddp.core.testsupport.TestDatabase;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuleSetServiceTest {
    private RuleSetStore store;
    private RuleSetService service;

    @BeforeEach
    void setUp() {
        store = new RuleSetStore(TestDatabase.freshMigrated());
        service = new RuleSetService(store);
    }

    @Test
    void evaluatesTheEffectiveVersionAndExplainsWhichClauseFired() {
        store.save(ruleSet(1, LocalDate.of(2026, 1, 1), null, "GO-1", true));
        store.save(ruleSet(2, LocalDate.of(2026, 6, 1), null, "GO-2", false));

        var first = service.evaluate(Type.ELIGIBILITY, OwnerType.SCHEME, "pension", LocalDate.of(2026, 5, 31), Map.of("age", 64));
        var second = service.evaluate(Type.ELIGIBILITY, OwnerType.SCHEME, "pension", LocalDate.of(2026, 6, 1), Map.of("age", 64));

        assertEquals(1, first.ruleSet().version());
        assertEquals("GO-1", first.firedRules().getFirst().clauseReference());
        assertEquals(true, first.firedRules().getFirst().outputs().get("eligible"));
        assertEquals(2, second.ruleSet().version());
        assertEquals("GO-2", second.firedRules().getFirst().clauseReference());
        assertEquals(false, second.firedRules().getFirst().outputs().get("eligible"));
    }

    @Test
    void retrievesRequiredAttributesAndEvaluatesTheRequestedEligibilityVersion() {
        RuleSet saved = new RuleSet(UUID.randomUUID(), Type.ELIGIBILITY, OwnerType.SCHEME, "pension", 1, Status.APPROVED,
                LocalDate.of(2026, 1, 1), null, dmn("GO-3", true), List.of("age", "annualIncome"));
        store.save(saved);
        assertEquals(List.of("age", "annualIncome"), service.getById(saved.id()).requiredAttributes());
        assertEquals("GO-3", service.evaluateById(saved.id(), Map.of("age", 65)).firedRules().getFirst().clauseReference());
    }

    @Test
    void filtersSelectorMetadataByRuleTypeWithoutReturningDefinitions() {
        RuleSet eligibility = new RuleSet(UUID.randomUUID(), Type.ELIGIBILITY, OwnerType.SCHEME, "pension", 1,
                Status.DRAFT, LocalDate.of(2026, 1, 1), null, dmn("GO-4", true), List.of("annualIncome"));
        store.save(eligibility);
        store.save(new RuleSet(UUID.randomUUID(), Type.DATA_QUALITY, OwnerType.DEPARTMENT, "registrar", 1,
                Status.DRAFT, LocalDate.of(2026, 1, 1), null, dmn("GO-5", true)));
        var listed = service.list(Type.ELIGIBILITY);
        assertEquals(List.of(eligibility.id()), listed.stream().map(RuleSet::id).toList());
        assertEquals(List.of("annualIncome"), listed.getFirst().requiredAttributes());
        assertEquals("", listed.getFirst().dmnXml());
    }

    @Test
    void doesNotSelectDraftsOrRulesOutsideTheirEffectiveDate() {
        store.save(new RuleSet(UUID.randomUUID(), Type.DATA_QUALITY, OwnerType.DEPARTMENT, "registrar", 1, Status.DRAFT,
                LocalDate.of(2026, 1, 1), null, dmn("DRAFT", true)));
        assertThrows(IllegalArgumentException.class, () -> service.evaluate(Type.DATA_QUALITY, OwnerType.DEPARTMENT,
                "registrar", LocalDate.of(2026, 1, 1), Map.of("age", 64)));
    }

    private static RuleSet ruleSet(int version, LocalDate from, LocalDate to, String clause, boolean eligible) {
        return new RuleSet(UUID.randomUUID(), Type.ELIGIBILITY, OwnerType.SCHEME, "pension", version, Status.APPROVED,
                from, to, dmn(clause, eligible));
    }

    private static String dmn(String clause, boolean eligible) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="definitions" name="rules" namespace="https://uddp.gov/rules">
                  <decision id="decision" name="decision">
                    <decisionTable id="table" hitPolicy="FIRST">
                      <input id="age"><inputExpression id="age-expression" typeRef="integer"><text>age</text></inputExpression></input>
                      <output id="eligible" name="eligible" typeRef="boolean" />
                      <output id="clause" name="clauseReference" typeRef="string" />
                      <rule id="rule"><inputEntry id="age-entry"><text>&gt;= 18</text></inputEntry><outputEntry id="eligible-output"><text>%s</text></outputEntry><outputEntry id="clause-output"><text>\"%s\"</text></outputEntry></rule>
                    </decisionTable>
                  </decision>
                </definitions>
                """.formatted(eligible, clause);
    }
}
