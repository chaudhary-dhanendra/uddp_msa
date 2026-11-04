package gov.uddp.core.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.rules.RuleSet;
import gov.uddp.core.rules.RuleSetService;
import gov.uddp.core.rules.RuleSetStore;
import gov.uddp.core.rules.RulesProperties;
import gov.uddp.core.testsupport.TestDatabase;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DmnSurvivorshipIntegrationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void dmnSelectsAnExistingSourceAndRecordsItsClauseInProvenance() throws Exception {
        RuleSetStore store = new RuleSetStore(TestDatabase.freshMigrated());
        store.save(new RuleSet(UUID.randomUUID(), RuleSet.Type.SURVIVORSHIP, RuleSet.OwnerType.DEPARTMENT, "golden-policy",
                1, RuleSet.Status.APPROVED, LocalDate.of(2020, 1, 1), null, dmn()));
        RulesProperties properties = new RulesProperties();
        properties.setSurvivorshipOwnerId("golden-policy");
        var engine = new SurvivorshipEngine();
        var census = observation("00000000-0000-0000-0000-000000000001", "census", "111");
        var registry = observation("00000000-0000-0000-0000-000000000002", "registry", "222");

        var result = engine.survive(List.of(census, registry), SurvivorshipPolicy.V1,
                new DmnSurvivorshipRules(new RuleSetService(store), properties)).get("mobile");

        assertEquals("222", result.value().asText());
        assertEquals(registry.id(), result.selectedCanonicalId());
        assertEquals("DMN:GO-9.2", result.rule());
        assertEquals(List.of(census.id(), registry.id()), result.contributors());
    }

    @Test
    void absentDmnOwnerKeepsTheExistingSelection() throws Exception {
        var engine = new SurvivorshipEngine();
        var first = observation("00000000-0000-0000-0000-000000000001", "census", "111");
        var second = observation("00000000-0000-0000-0000-000000000002", "registry", "222");
        var properties = new RulesProperties();

        var result = engine.survive(List.of(first, second), SurvivorshipPolicy.V1,
                new DmnSurvivorshipRules(new RuleSetService(new RuleSetStore(TestDatabase.freshMigrated())), properties)).get("mobile");

        assertEquals("111", result.value().asText());
        assertEquals("DETERMINISTIC_CANONICAL_ID_FALLBACK", result.rule());
    }

    private CanonicalObservation observation(String id, String source, String mobile) throws Exception {
        return new CanonicalObservation(UUID.fromString(id), "XX", source,
                json.readTree("{\"mobile\":\"" + mobile + "\"}"));
    }

    private static String dmn() {
        return """
                <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="definitions" name="rules" namespace="https://uddp.gov/rules">
                  <decision id="decision" name="decision"><decisionTable id="table" hitPolicy="FIRST">
                    <input id="field"><inputExpression id="field-expression" typeRef="string"><text>field</text></inputExpression></input>
                    <output id="source" name="selectedSource" typeRef="string"/><output id="clause" name="clauseReference" typeRef="string"/>
                    <rule id="rule"><inputEntry id="field-entry"><text>\"mobile\"</text></inputEntry><outputEntry id="source-value"><text>\"registry\"</text></outputEntry><outputEntry id="clause-value"><text>\"GO-9.2\"</text></outputEntry></rule>
                  </decisionTable></decision>
                </definitions>
                """;
    }
}
