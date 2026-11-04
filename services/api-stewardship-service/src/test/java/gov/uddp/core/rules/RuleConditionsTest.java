package gov.uddp.core.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.rules.RuleConditions.Condition;
import gov.uddp.core.rules.RuleConditions.DataType;
import gov.uddp.core.rules.RuleConditions.FieldSpec;
import gov.uddp.core.rules.RuleConditions.Operator;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.camunda.bpm.dmn.engine.DmnEngine;
import org.camunda.bpm.dmn.engine.DmnEngineConfiguration;
import org.camunda.bpm.model.dmn.Dmn;
import org.junit.jupiter.api.Test;

/** Operators follow a field's data type, and every compiled condition is FEEL the DMN engine evaluates as intended. */
class RuleConditionsTest {
    private static final DmnEngine ENGINE = DmnEngineConfiguration.createDefaultDmnEngineConfiguration().buildEngine();
    private static final FieldSpec AGE = new FieldSpec("age.years", "Resident · Age in years", DataType.NUMBER, List.of());
    private static final FieldSpec GENDER = new FieldSpec("gender", "Resident · Gender", DataType.CODE,
            List.of("MALE", "FEMALE", "OTHER", "UNKNOWN"));
    private static final FieldSpec DOB = new FieldSpec("dateOfBirth", "Resident · Date of birth", DataType.DATE, List.of());
    private static final FieldSpec NAME = new FieldSpec("name", "Resident · Full name", DataType.TEXT, List.of());
    private static final FieldSpec ALIVE = new FieldSpec("alive", "Resident · Alive", DataType.BOOLEAN, List.of());

    @Test
    void theDataTypeComesFromMetadata() {
        assertEquals(DataType.NUMBER, RuleConditions.dataType("text", "number", null), "age.years is text with a number value kind");
        assertEquals(DataType.NUMBER, RuleConditions.dataType("number", null, null));
        assertEquals(DataType.DATE, RuleConditions.dataType("date", "date", null));
        assertEquals(DataType.CODE, RuleConditions.dataType("code", null, "gender"));
        assertEquals(DataType.CODE, RuleConditions.dataType("geography", null, "lgd-district"));
        assertEquals(DataType.BOOLEAN, RuleConditions.dataType("boolean", null, null));
        assertEquals(DataType.TEXT, RuleConditions.dataType("text", null, null));
    }

    @Test
    void numbersOfferComparisonsAndCodesOfferValueLists() {
        assertEquals(List.of("eq", "neq", "gt", "gte", "lt", "lte", "between", "present", "missing"),
                RuleConditions.operators(DataType.NUMBER).stream().map(Operator::code).toList());
        assertEquals(List.of("eq", "neq", "in", "not_in", "present", "missing"),
                RuleConditions.operators(DataType.CODE).stream().map(Operator::code).toList());
        assertTrue(RuleConditions.operators(DataType.DATE).stream().anyMatch(o -> o.label().equals("is before")));
        assertTrue(RuleConditions.operators(DataType.TEXT).stream().anyMatch(o -> o.code().equals("contains")));
        assertTrue(RuleConditions.operators(DataType.BOOLEAN).stream().anyMatch(o -> o.code().equals("is_true")));
    }

    @Test
    void compiledConditionsEvaluateAsTheirOperatorsSay() {
        String age = compile(AGE, "gte", "60");
        assertEquals("age.years >= 60", age);
        assertTrue(fires(age, Map.of("age", Map.of("years", 60))));
        assertTrue(!fires(age, Map.of("age", Map.of("years", 59))));

        String range = RuleConditions.compile(new Condition("age.years", "between", "18", "40", null), AGE);
        assertTrue(fires(range, Map.of("age", Map.of("years", 18))));
        assertTrue(fires(range, Map.of("age", Map.of("years", 40))));
        assertTrue(!fires(range, Map.of("age", Map.of("years", 41))));

        assertTrue(fires(compile(AGE, "lt", "18"), Map.of("age", Map.of("years", 17))));
        assertTrue(fires(compile(AGE, "neq", "18"), Map.of("age", Map.of("years", 17))));

        String female = compile(GENDER, "eq", "FEMALE");
        assertEquals("gender = \"FEMALE\"", female);
        assertTrue(fires(female, Map.of("gender", "FEMALE")));
        String oneOf = RuleConditions.compile(new Condition("gender", "in", null, null, List.of("FEMALE", "OTHER")), GENDER);
        assertTrue(fires(oneOf, Map.of("gender", "OTHER")));
        assertTrue(!fires(oneOf, Map.of("gender", "MALE")));
        String notOneOf = RuleConditions.compile(new Condition("gender", "not_in", null, null, List.of("MALE")), GENDER);
        assertTrue(fires(notOneOf, Map.of("gender", "FEMALE")));
        assertTrue(!fires(notOneOf, Map.of("gender", "MALE")));

        String before = compile(DOB, "lt", "1966-01-01");
        assertTrue(fires(before, Map.of("dateOfBirth", "1960-05-04")));
        assertTrue(!fires(before, Map.of("dateOfBirth", "1970-05-04")));
        String dobRange = RuleConditions.compile(new Condition("dateOfBirth", "between", "1960-01-01", "1960-12-31", null), DOB);
        assertTrue(fires(dobRange, Map.of("dateOfBirth", "1960-05-04")));

        assertTrue(fires(compile(NAME, "contains", "Singh"), Map.of("name", "Ram Singh")));
        assertTrue(fires(compile(NAME, "starts_with", "Ram"), Map.of("name", "Ram Singh")));
        assertTrue(fires(compile(ALIVE, "is_true", null), Map.of("alive", true)));
        assertTrue(!fires(compile(ALIVE, "is_false", null), Map.of("alive", true)));

        Map<String, Object> noName = new HashMap<>();
        noName.put("name", null);
        assertTrue(fires(compile(NAME, "missing", null), noName));
        assertTrue(fires(compile(NAME, "present", null), Map.of("name", "Ram")));
    }

    @Test
    void conditionsAreJoinedWithAndAndCheckedAgainstTheirFields() {
        Map<String, FieldSpec> fields = Map.of(AGE.name(), AGE, GENDER.name(), GENDER);
        String both = RuleConditions.compileAll(List.of(new Condition("gender", "eq", "FEMALE", null, null),
                new Condition("age.years", "gte", "60", null, null)), fields::get);
        assertEquals("gender = \"FEMALE\" and age.years >= 60", both);
        assertTrue(fires(both, Map.of("gender", "FEMALE", "age", Map.of("years", 61))));

        assertEquals("Condition 1: 'gte' is not an operator for Resident · Gender (code)", assertThrows(
                IllegalArgumentException.class, () -> RuleConditions.compileAll(
                        List.of(new Condition("gender", "gte", "1", null, null)), fields::get)).getMessage());
        assertEquals("Condition 2: Resident · Age in years needs a number, not 'sixty'", assertThrows(
                IllegalArgumentException.class, () -> RuleConditions.compileAll(List.of(
                        new Condition("gender", "eq", "FEMALE", null, null),
                        new Condition("age.years", "gte", "sixty", null, null)), fields::get)).getMessage());
        assertEquals("Condition 1: 'female' is not a value of Resident · Gender", assertThrows(
                IllegalArgumentException.class, () -> RuleConditions.compileAll(
                        List.of(new Condition("gender", "eq", "female", null, null)), fields::get)).getMessage());
        assertEquals("Condition 1: the first value must not be after the second", assertThrows(
                IllegalArgumentException.class, () -> RuleConditions.compileAll(
                        List.of(new Condition("age.years", "between", "60", "18", null)), fields::get)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> RuleConditions.compileAll(
                List.of(new Condition("unknown", "eq", "1", null, null)), fields::get));
        assertThrows(IllegalArgumentException.class, () -> RuleConditions.compileAll(List.of(), fields::get));
        assertThrows(IllegalArgumentException.class, () -> RuleConditions.compile(
                new Condition("dateOfBirth", "lt", "04-05-1960", null, null), DOB));
    }

    @Test
    void attributesReadByCompiledConditionsAreTheFieldsOnly() {
        var table = new DecisionTableDmn.DecisionTable(List.of(new DecisionTableDmn.DecisionRow(
                "date(dateOfBirth) < date(\"1966-01-01\") and starts with(name, \"R\") and gender in [\"FEMALE\"]",
                "ELIGIBLE", "GO 1")));
        assertEquals(List.of("dateOfBirth", "gender", "name"), DecisionTableDmn.attributes(table));
    }

    @Test
    void generatedTestCasesReadTheStudiosCompiledConditions() {
        String xml = DecisionTableDmn.toDmn(new DecisionTableDmn.DecisionTable(List.of(new DecisionTableDmn.DecisionRow(
                "gender = \"FEMALE\" and (age.years >= 18 and age.years <= 60) and date(dateOfBirth) < date(\"1966-01-01\")"
                        + " and maritalStatus in [\"WIDOWED\", \"SEPARATED\"]", "ELIGIBLE", "GO 1"))), "test");
        var found = RuleTestCaseGenerator.constraints(xml);
        assertEquals(List.of(
                new RuleTestCaseGenerator.Constraint("gender", "=", "FEMALE"),
                new RuleTestCaseGenerator.Constraint("age.years", ">=", 18),
                new RuleTestCaseGenerator.Constraint("age.years", "<=", 60),
                new RuleTestCaseGenerator.Constraint("dateOfBirth", "<", java.time.LocalDate.of(1966, 1, 1)),
                new RuleTestCaseGenerator.Constraint("maritalStatus", "=", "WIDOWED")), found);

        Map<String, Object> flat = new HashMap<>(Map.of("age.years", 17, "gender", "FEMALE"));
        var record = RuleTestCaseGenerator.nested(new java.util.LinkedHashSet<>(List.of("age.years", "gender")), flat);
        assertEquals(Map.of("age", Map.of("years", 17), "gender", "FEMALE"), record);
        assertTrue(!fires("gender = \"FEMALE\" and age.years >= 18", record));
        record.put("age", Map.of("years", 18));
        assertTrue(fires("gender = \"FEMALE\" and age.years >= 18", record));
    }

    @Test
    void aConditionSplitsIntoItsTopLevelParts() {
        assertEquals(List.of("gender = \"FEMALE\"", "(age.years >= 18 and age.years <= 60)", "name = \"a and b\""),
                DecisionTableDmn.conjuncts("gender = \"FEMALE\" and (age.years >= 18 and age.years <= 60) and name = \"a and b\""));
    }

    @Test
    void anEvaluationExplainsWhichConditionFailed() {
        String xml = DecisionTableDmn.toDmn(new DecisionTableDmn.DecisionTable(List.of(new DecisionTableDmn.DecisionRow(
                "gender = \"FEMALE\" and age.years >= 18", "ELIGIBLE", "GO para 2"))), "test");
        var checks = RuleSetService.checks(ENGINE, xml, new HashMap<>(Map.of("gender", "FEMALE", "age", Map.of("years", 17))));
        assertEquals(List.of(new RuleSetService.ConditionCheck("GO para 2", "gender = \"FEMALE\"", true),
                new RuleSetService.ConditionCheck("GO para 2", "age.years >= 18", false)), checks);
    }

    private static String compile(FieldSpec field, String operator, String value) {
        return RuleConditions.compile(new Condition(field.name(), operator, value, null, null), field);
    }

    private static boolean fires(String condition, Map<String, Object> record) {
        String xml = DecisionTableDmn.toDmn(new DecisionTableDmn.DecisionTable(List.of(
                new DecisionTableDmn.DecisionRow(condition, "ELIGIBLE", "GO 1"))), "test");
        var model = Dmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        var decision = ENGINE.parseDecision("decision", model);
        return !ENGINE.evaluateDecisionTable(decision, new HashMap<>(record)).getResultList().isEmpty();
    }
}
