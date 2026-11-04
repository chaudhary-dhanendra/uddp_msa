package gov.uddp.core.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gov.uddp.core.rules.RuleConditions;
import gov.uddp.core.rules.RuleSet;
import gov.uddp.core.scheme.SchemeCatalogue;
import gov.uddp.core.studio.StudioMetadata;
import gov.uddp.core.studio.StudioModels;
import gov.uddp.core.testsupport.SampleData;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Rule fields carry the data type and operators from platform metadata; built conditions are compiled on save. */
class RuleSetControllerFieldsTest {
    private final RuleSetController controller = new RuleSetController(null, new StudioMetadata(
            SampleData.REPOSITORY_ROOT.resolve("platform"), new SchemeCatalogue(SampleData.SCHEME_CATALOGUE)));

    private Map<String, RuleSetController.RuleField> fields() {
        return controller.fields().stream()
                .collect(Collectors.toMap(RuleSetController.RuleField::name, Function.identity()));
    }

    @Test
    void ageIsANumberAndGenderListsItsCodes() {
        var age = fields().get("age.years");
        assertEquals(RuleConditions.DataType.NUMBER, age.dataType());
        assertEquals(List.of("eq", "neq", "gt", "gte", "lt", "lte", "between", "present", "missing"),
                age.operators().stream().map(RuleConditions.Operator::code).toList());
        assertNull(age.values());

        var gender = fields().get("gender");
        assertEquals(RuleConditions.DataType.CODE, gender.dataType());
        assertEquals(List.of("MALE", "FEMALE", "OTHER", "UNKNOWN"),
                gender.values().stream().map(StudioModels.CodeValue::code).toList());
        assertEquals(RuleConditions.DataType.DATE, fields().get("dateOfBirth").dataType());
        assertEquals(RuleConditions.DataType.TEXT, fields().get("name").dataType());
    }

    @Test
    void builtConditionsBecomeOneFeelCondition() {
        var draft = controller.toDraft(new RuleSetController.TableRequest(RuleSet.Type.ELIGIBILITY,
                RuleSet.OwnerType.SCHEME, "SCHEME-X", LocalDate.of(2026, 1, 1), null, List.of(
                        new RuleSetController.TableRow(null, List.of(
                                new RuleConditions.Condition("gender", "eq", "FEMALE", null, null),
                                new RuleConditions.Condition("age.years", "between", "18", "60", null)),
                                "ELIGIBLE", "GO para 2"),
                        new RuleSetController.TableRow("age.years < 18", null, "NOT_ELIGIBLE", "GO para 3"))));
        assertEquals("gender = \"FEMALE\" and (age.years >= 18 and age.years <= 60)", draft.rows().get(0).condition());
        assertEquals("age.years < 18", draft.rows().get(1).condition());

        var error = assertThrows(IllegalArgumentException.class, () -> controller.toDraft(
                new RuleSetController.TableRequest(RuleSet.Type.ELIGIBILITY, RuleSet.OwnerType.SCHEME, "SCHEME-X",
                        LocalDate.of(2026, 1, 1), null, List.of(new RuleSetController.TableRow(null, List.of(
                                new RuleConditions.Condition("gender", "gt", "FEMALE", null, null)), "ELIGIBLE", "GO")))));
        assertEquals("Condition 1: 'gt' is not an operator for Resident · Gender (code)", error.getMessage());
    }
}
