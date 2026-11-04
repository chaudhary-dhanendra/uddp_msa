package gov.uddp.core.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlatformSurvivorshipPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void sourceTrustWinsRegardlessOfCanonicalIdOrderAndFallbackRemainsStable() throws Exception {
        var policy = SurvivorshipPolicy.fromPlatform(new PlatformSurvivorshipPolicy("Resident", "resident-survivorship-v3",
                Map.of("mobile", new PlatformSurvivorshipPolicy.FieldPolicy("SOURCE_TRUST", List.of("trusted", "other")))));
        var low = new CanonicalObservation(UUID.fromString("00000000-0000-0000-0000-000000000001"),"A","other",json.readTree("{\"mobile\":\"111\"}"));
        var high = new CanonicalObservation(UUID.fromString("00000000-0000-0000-0000-000000000002"),"B","trusted",json.readTree("{\"mobile\":\"222\"}"));
        var value = new SurvivorshipEngine().survive(List.of(high, low), policy).get("mobile");
        assertEquals("222", value.value().asText()); assertEquals("CONFIGURED_SOURCE_PRECEDENCE", value.rule()); assertEquals(high.id(), value.selectedCanonicalId());
        var fallback = new SurvivorshipEngine().survive(List.of(high, low), SurvivorshipPolicy.V1).get("mobile");
        assertEquals("111", fallback.value().asText()); assertEquals("DETERMINISTIC_CANONICAL_ID_FALLBACK", fallback.rule());
    }
}
