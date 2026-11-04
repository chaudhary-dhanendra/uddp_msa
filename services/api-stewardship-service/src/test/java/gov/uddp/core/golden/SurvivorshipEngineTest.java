package gov.uddp.core.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.SurvivorshipDefinition;
import gov.uddp.core.statepack.StatePackManifest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SurvivorshipEngineTest {
    private static final String ID_1 = "00000000-0000-0000-0000-000000000001";
    private static final String ID_2 = "00000000-0000-0000-0000-000000000002";

    private final ObjectMapper json = new ObjectMapper();
    private final SurvivorshipEngine engine = new SurvivorshipEngine();

    private CanonicalObservation observation(String id, String state, String source, String name, String mobile)
            throws Exception {
        return new CanonicalObservation(UUID.fromString(id), state, source, json.readTree(
                "{\"name\":\"" + name + "\",\"mobile\":" + mobile + ",\"address\":{\"districtLGD\":\"1\"}}"));
    }

    private static LoadedStatePack pack(String stateCode, Map<String, List<String>> fieldPriority) {
        var manifest = new StatePackManifest("pack-" + stateCode, stateCode, "1.0.0", "1.0.0", "draft",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        return new LoadedStatePack(manifest, List.of(), List.of(), Map.of(), List.of(), List.of(),
                List.of(new SurvivorshipDefinition("Resident", fieldPriority)));
    }

    @Test
    void agreementNullConflictAndOrderAreDeterministic() throws Exception {
        var a = observation(ID_1, "S", "a", "Ram", "111");
        var b = observation(ID_2, "S", "b", "Ram", "222");
        var policy = SurvivorshipPolicy.fromStatePacks(List.of(pack("S", Map.of("mobile", List.of("b", "a")))));

        var mobile = engine.survive(List.of(a, b), policy).get("mobile");
        assertEquals("222", mobile.value().asText());
        assertEquals("CONFIGURED_SOURCE_PRECEDENCE", mobile.rule());
        assertEquals(mobile.value(), engine.survive(List.of(b, a), policy).get("mobile").value());

        var withNull = new CanonicalObservation(UUID.fromString("00000000-0000-0000-0000-000000000003"), "S", "a",
                json.readTree("{\"name\":\"Ram\",\"mobile\":null}"));
        assertEquals("111", engine.survive(List.of(withNull, a), SurvivorshipPolicy.V1).get("mobile").value().asText());

        var agreeing = observation("00000000-0000-0000-0000-000000000004", "S", "b", "Ram", "111");
        assertEquals("ALL_VALUES_AGREE", engine.survive(List.of(a, agreeing), SurvivorshipPolicy.V1).get("mobile").rule());
    }

    @Test
    void perFieldPriorityFromStatePackWinsAndEachFieldUsesItsOwnOrder() throws Exception {
        // One pack, two sources, opposite priority for two fields: the configuration alone decides each winner.
        var policy = SurvivorshipPolicy.fromStatePacks(List.of(pack("S", Map.of(
                "name", List.of("census", "pension"),
                "mobile", List.of("pension", "census")))));
        var census = observation(ID_1, "S", "census", "Ram Singh", "\"111\"");
        var pension = observation(ID_2, "S", "pension", "Ram Kumar Singh", "\"222\"");

        var fields = engine.survive(List.of(census, pension), policy);
        assertEquals("resident-survivorship-v2", policy.version());
        assertEquals("Ram Singh", fields.get("name").value().asText());
        assertEquals(census.id(), fields.get("name").selectedCanonicalId());
        assertEquals("CONFIGURED_SOURCE_PRECEDENCE", fields.get("name").rule());
        // census has the lower canonical ID, so the default order alone would have picked "111".
        assertEquals("222", fields.get("mobile").value().asText());
        assertEquals(pension.id(), fields.get("mobile").selectedCanonicalId());
        assertEquals("CONFIGURED_SOURCE_PRECEDENCE", fields.get("mobile").rule());
        assertEquals("DETERMINISTIC_CANONICAL_ID_FALLBACK",
                engine.survive(List.of(census, pension), SurvivorshipPolicy.V1).get("mobile").rule());
    }

    @Test
    void equalRanksFromDifferentPacksFallBackToCanonicalIdOrder() throws Exception {
        var policy = SurvivorshipPolicy.fromStatePacks(List.of(
                pack("ST1", Map.of("mobile", List.of("pds"))),
                pack("ST2", Map.of("mobile", List.of("pension")))));
        var st1 = observation(ID_1, "ST1", "pds", "Ram", "\"111\"");
        var st2 = observation(ID_2, "ST2", "pension", "Ram", "\"222\"");

        var mobile = engine.survive(List.of(st2, st1), policy).get("mobile");
        assertEquals("111", mobile.value().asText());
        assertEquals("DETERMINISTIC_CANONICAL_ID_FALLBACK", mobile.rule());
    }

    @Test
    void unsupportedFieldInFieldPriorityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SurvivorshipPolicy.fromStatePacks(
                List.of(pack("S", Map.of("bloodGroup", List.of("a"))))));
    }

    private CanonicalObservation parsed(String id, String source, String mobile, Double confidence) throws Exception {
        String provenance = confidence == null ? ""
                : ",\"extensions\":{\"uddp.fieldProvenance\":{\"mobile\":{\"confidence\":" + confidence + "}}}";
        return new CanonicalObservation(UUID.fromString(id), "S", source, json.readTree(
                "{\"name\":\"Ram\",\"mobile\":\"" + mobile + "\"" + provenance + "}"));
    }

    @Test
    void lowConfidenceNeverBeatsHighConfidence() throws Exception {
        // the low-confidence value is ranked first by policy and sorts first by ID, yet loses
        var policy = SurvivorshipPolicy.fromStatePacks(List.of(pack("S", Map.of("mobile", List.of("a", "b")))));
        var low = parsed(ID_1, "a", "111", 0.79);
        var high = parsed(ID_2, "b", "222", 0.8);

        var mobile = engine.survive(List.of(low, high), policy).get("mobile");
        assertEquals("222", mobile.value().asText());
        assertEquals("CONFIDENCE_THRESHOLD", mobile.rule());
        assertEquals(2, mobile.contributors().size());

        // when every value is low, the normal rules still pick one
        var alsoLow = parsed(ID_2, "b", "222", 0.5);
        assertEquals("111", engine.survive(List.of(low, alsoLow), policy).get("mobile").value().asText());
    }

    @Test
    void structuredBeatsParsedAtEqualPriority() throws Exception {
        var parsedValue = parsed(ID_1, "a", "111", 0.95);
        var structuredValue = parsed(ID_2, "b", "222", null);

        var mobile = engine.survive(List.of(parsedValue, structuredValue), SurvivorshipPolicy.V1).get("mobile");
        assertEquals("222", mobile.value().asText());
        assertEquals("STRUCTURED_OVER_PARSED", mobile.rule());
    }

    @Test
    void relationsAreKeptAsAllObservationsWithoutAWinner() throws Exception {
        var first = new CanonicalObservation(UUID.fromString(ID_1), "S", "a", json.readTree(
                "{\"name\":\"Ram\",\"relations\":[{\"type\":\"FATHER\",\"name\":\"Mohan\"}]}"));
        var second = new CanonicalObservation(UUID.fromString(ID_2), "S", "b", json.readTree(
                "{\"name\":\"Ram\",\"relations\":[{\"type\":\"FATHER\",\"name\":\"Mohun\"}]}"));
        var fields = engine.survive(List.of(second, first), SurvivorshipPolicy.V1);
        assertEquals(false, fields.containsKey("relations"));

        var relations = engine.payload(fields, List.of(second, first), json).path("relations");
        assertEquals(2, relations.size());
        assertEquals("Mohan", relations.get(0).path("name").asText());
        assertEquals("S:b", relations.get(1).path("source").asText());
    }
}
