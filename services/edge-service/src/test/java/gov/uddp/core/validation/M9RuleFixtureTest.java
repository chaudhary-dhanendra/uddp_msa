package gov.uddp.core.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.ValidationDefinition;
import gov.uddp.core.statepack.StatePackAssets.ValidationRule;
import gov.uddp.core.statepack.StatePackManifest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class M9RuleFixtureTest {
    private final ObjectMapper json=new ObjectMapper(); private final ResidentValidator validator=new ResidentValidator();
    private LoadedStatePack pack(ValidationRule... rules){return new LoadedStatePack(new StatePackManifest("fx","FX","1","1","draft",List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of()),List.of(),List.of(),Map.of(),List.of(new ValidationDefinition("Resident",List.of(rules))),List.of(),List.of());}
    private ValidationRule rule(String type,String field,String severity){return new ValidationRule(type,type,field,null,null,null,"^[0-9]{10}$",null,"TODAY",0,130,"lgd-district",null,severity);}
    @Test void patternDateReferenceAndBatchUniqueAreConfigurationDriven() throws Exception {
        var p=pack(rule("PATTERN","mobile","ERROR"),rule("DATE_RANGE","dateOfBirth","ERROR"),rule("REFERENCE_LIST","address.districtLGD","WARNING"),new ValidationRule("unique","UNIQUE_WITHIN_BATCH","extensions.familyReference",List.of("extensions.familyReference"),null,null,null,null,null,null,null,null,null,"ERROR"));
        var rows=List.of(json.readTree("{\"mobile\":\"123\",\"dateOfBirth\":\"2030-01-01\",\"address\":{\"districtLGD\":\"999\"},\"extensions\":{\"familyReference\":\"x\"}}"),json.readTree("{\"mobile\":\"1234567890\",\"dateOfBirth\":\"1900-01-01\",\"address\":{\"districtLGD\":\"100001\"},\"extensions\":{\"familyReference\":\"x\"}}"));
        var issues=validator.validateBatch(rows,p,Map.of("lgd-district",Set.of("100001")),Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"),ZoneOffset.UTC));
        assertEquals(5,issues.get(0).size()); assertEquals(1,issues.get(1).size());
    }
    @Test void allSupportedCrossFieldOperatorsHaveInvalidFixtures() throws Exception {
        for(String op:List.of("ALL_OR_NONE","REQUIRES","NOT_EQUALS")){
            var r=new ValidationRule(op,"CROSS_FIELD","name",List.of("name","mobile"),null,null,null,null,null,null,null,null,op,"WARNING");
            String body=op.equals("NOT_EQUALS") ? "{\"name\":\"same\",\"mobile\":\"same\"}" : "{\"name\":\"same\"}";
            var rows=validator.validateBatch(List.of(json.readTree(body)),pack(r),Map.of(),Clock.systemUTC());
            assertEquals(1,rows.getFirst().size());
        }
    }
}
