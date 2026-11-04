package gov.uddp.core.scheme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SchemeEligibilityAgeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final LocalDate on = LocalDate.of(2026, 11, 1);

    @Test
    void aFullDateOfBirthGivesTheAgeInCompletedYears() throws Exception {
        var payload = SchemeEligibilityService.withAge(json.readTree("{\"dateOfBirth\":\"1960-11-02\"}"), on);
        assertEquals(65, payload.path("age").path("years").asInt(), "the birthday is tomorrow");
    }

    @Test
    void aMappedAgeIsKept() throws Exception {
        var payload = SchemeEligibilityService.withAge(
                json.readTree("{\"dateOfBirth\":\"1960-01-01\",\"age\":{\"years\":40}}"), on);
        assertEquals(40, payload.path("age").path("years").asInt());
    }

    @Test
    void ruleInputsAreNestedByPathAndKeepTheirType() {
        var input = new java.util.LinkedHashMap<String, Object>();
        SchemeEligibilityService.put(input, "age.years", 65);
        SchemeEligibilityService.put(input, "gender", "FEMALE");
        assertEquals(java.util.Map.of("years", 65), input.get("age"));
        assertEquals("FEMALE", input.get("gender"));
    }

    @Test
    void theEvaluatedAgeIsReadFromAgeYears() throws Exception {
        assertEquals(65, SchemeEligibilityService.ageYears(
                SchemeEligibilityService.withAge(json.readTree("{\"dateOfBirth\":\"1960-11-02\"}"), on)));
        assertEquals(40, SchemeEligibilityService.ageYears(json.readTree("{\"age\":{\"years\":\"40\"}}")));
        org.junit.jupiter.api.Assertions.assertNull(SchemeEligibilityService.ageYears(json.readTree("{\"dateOfBirth\":\"1960\"}")));
    }

    @Test
    void noAgeWithoutAFullDate() throws Exception {
        var payload = SchemeEligibilityService.withAge(json.readTree("{\"dateOfBirth\":\"1960\"}"), on);
        assertFalse(payload.has("age"));
    }
}
