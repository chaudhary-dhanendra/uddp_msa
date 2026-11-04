package gov.uddp.core.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PiiMaskerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void residentValuesAreMasked() throws Exception {
        JsonNode masked = PiiMasker.mask(json.readTree(
                "{\"name\":\"Asha Test\",\"dateOfBirth\":\"1980-04-12\",\"mobile\":\"9876543210\"}"));
        assertEquals("Asha T.", masked.path("name").asText());
        assertEquals("1980", masked.path("dateOfBirth").asText());
        assertEquals("******3210", masked.path("mobile").asText());
    }

    @Test
    void aWholeNumberUnderAMobileKeyIsStillMasked() throws Exception {
        JsonNode masked = PiiMasker.mask(json.readTree("{\"mobile\":9876543210}"));
        assertEquals("******3210", masked.path("mobile").asText());
    }

    @Test
    void fractionsUnderResidentKeysAreStatisticsAndKept() throws Exception {
        // Field completeness per source (Matching Studio, data readiness) is keyed by the field it measures.
        JsonNode masked = PiiMasker.mask(json.readTree(
                "{\"completeness\":{\"name\":1.0,\"mobile\":0.75,\"date_of_birth\":0.5}}"));
        assertEquals(1.0, masked.path("completeness").path("name").asDouble());
        assertEquals(0.75, masked.path("completeness").path("mobile").asDouble());
    }
}
