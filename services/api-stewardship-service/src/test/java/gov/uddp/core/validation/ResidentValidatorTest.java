package gov.uddp.core.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.ValidationDefinition;
import gov.uddp.core.statepack.StatePackAssets.ValidationRule;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.validation.ValidationIssue.Severity;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResidentValidatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ResidentValidator validator = new ResidentValidator();

    private static LoadedStatePack realPack(String packId) {
        var properties = new StatePackProperties();
        properties.setStatePackDirectory("../../services/statepack-service/seed/state-packs");
        return new StatePackLoader(properties).loadAll(Path.of("../../services/statepack-service/seed/state-packs")).get(packId);
    }

    private static LoadedStatePack withRules(LoadedStatePack pack, ValidationRule... rules) {
        return new LoadedStatePack(pack.manifest(), pack.sources(), pack.mappings(), pack.crosswalks(),
                List.of(new ValidationDefinition("Resident", List.of(rules))), pack.matching(), pack.survivorship());
    }

    @Test
    void validResidentPassesTheRealPackRules() throws Exception {
        var resident = json.readTree("{\"name\":\"Ram Singh\",\"gender\":\"MALE\"}");
        assertEquals(List.of(), validator.validate(resident, realPack("state-1")));
    }

    @Test
    void blankNameAndInvalidGenderAreErrorsUnderTheRealPackRules() throws Exception {
        var resident = json.readTree("{\"name\":\"  \",\"gender\":\"M\"}");
        var issues = validator.validate(resident, realPack("state-2"));

        assertEquals(2, issues.size());
        assertEquals(new ValidationIssue("name", "required", Severity.ERROR, "required value is blank"), issues.get(0));
        assertEquals("gender", issues.get(1).field());
        assertEquals("allowedValues", issues.get(1).rule());
        assertEquals(Severity.ERROR, issues.get(1).severity());
        assertTrue(issues.get(1).message().contains("'M'"));
    }

    @Test
    void missingNameIsAnErrorAndOptionalBlankFieldsAreNotChecked() throws Exception {
        var issues = validator.validate(json.readTree("{\"gender\":null}"), realPack("state-3"));
        assertEquals(List.of(new ValidationIssue("name", "required", Severity.ERROR, "required value is blank")), issues);
    }

    @Test
    void severityAndNestedPathsComeFromConfiguration() throws Exception {
        var pack = withRules(realPack("state-1"),
                new ValidationRule("mobile", true, null, "warning"),
                new ValidationRule("address.districtLGD", null, List.of("100001"), "WARNING"));
        var issues = validator.validate(json.readTree("{\"name\":\"Ram\",\"address\":{\"districtLGD\":\"100002\"}}"), pack);

        assertEquals(2, issues.size());
        assertTrue(issues.stream().allMatch(issue -> issue.severity() == Severity.WARNING));
        assertEquals("mobile", issues.get(0).field());
        assertEquals("address.districtLGD", issues.get(1).field());
    }
}
