package gov.uddp.core.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.rules.RuleSet;
import gov.uddp.core.rules.RuleSetService;
import gov.uddp.core.rules.RuleSetStore;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.ValidationDefinition;
import gov.uddp.core.statepack.StatePackAssets.ValidationRule;
import gov.uddp.core.statepack.StatePackManifest;
import gov.uddp.core.testsupport.TestDatabase;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DmnDataQualityIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ResidentValidator validator = new ResidentValidator();
    private RuleSetStore store;

    @BeforeEach
    void setUp() {
        store = new RuleSetStore(TestDatabase.freshMigrated());
        validator.setRuleSetService(new RuleSetService(store));
    }

    @Test
    void appendsDmnFindingWithItsClauseReferenceWithoutReplacingYamlRules() throws Exception {
        store.save(new RuleSet(UUID.randomUUID(), RuleSet.Type.DATA_QUALITY, RuleSet.OwnerType.SOURCE_PACK, "resident-pack",
                1, RuleSet.Status.APPROVED, LocalDate.of(2020, 1, 1), null, dmn()));
        var issues = validator.validate(json.readTree("{\"name\":\"\"}"), pack());

        assertEquals(2, issues.size());
        assertEquals("required", issues.getFirst().rule());
        assertEquals(new ValidationIssue("mobile", "GO-12.4", ValidationIssue.Severity.WARNING,
                "mobile needs verification"), issues.get(1));
    }

    @Test
    void keepsYamlOnlyBehaviorWhenNoDmnRuleSetExists() throws Exception {
        var issues = validator.validate(json.readTree("{\"name\":\"\"}"), pack());
        assertEquals(List.of(new ValidationIssue("name", "required", ValidationIssue.Severity.ERROR,
                "required value is blank")), issues);
    }

    private static LoadedStatePack pack() {
        var manifest = new StatePackManifest("resident-pack", "XX", "1", "1", "APPROVED", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        return new LoadedStatePack(manifest, List.of(), List.of(), Map.of(),
                List.of(new ValidationDefinition("Resident", List.of(new ValidationRule("name", true, null, "ERROR")))),
                List.of(), List.of());
    }

    private static String dmn() {
        return """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="definitions" name="rules" namespace="https://uddp.gov/rules">
                  <decision id="decision" name="decision"><decisionTable id="table" hitPolicy="FIRST">
                    <output id="field" name="field" typeRef="string"/><output id="severity" name="severity" typeRef="string"/>
                    <output id="message" name="message" typeRef="string"/><output id="clause" name="clauseReference" typeRef="string"/>
                    <rule id="rule"><outputEntry id="field-value"><text>\"mobile\"</text></outputEntry><outputEntry id="severity-value"><text>\"WARNING\"</text></outputEntry><outputEntry id="message-value"><text>\"mobile needs verification\"</text></outputEntry><outputEntry id="clause-value"><text>\"GO-12.4\"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>
                """;
    }
}
