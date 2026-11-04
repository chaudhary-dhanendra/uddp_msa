package gov.uddp.core.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.CrosswalkDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.statepack.StatePackManifest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FamilyMappingEngineTest {
    private final ObjectMapper json = new ObjectMapper();
    private final FamilyMappingEngine engine = new FamilyMappingEngine(new RawCsvRecordParser(json));

    @Test
    void mapsHouseholdIdsRelationshipAndExtensionsFromRules() {
        var pack = pack(List.of(
                rule("hh", "sourceFamilyIds[]", null, null),
                rule("alt_hh", "sourceFamilyIds[]", null, null),
                rule("rel", "membership.relationshipType", "relationship", null),
                rule("cat", "extensions.category", null, null)));
        var mapped = engine.map(raw("hh,alt_hh,rel,cat", "A1, B2 ,H,AAY"), pack, "src").orElseThrow();

        assertEquals(List.of("A1", "B2"), mapped.sourceFamilyIds());
        assertEquals("HEAD", mapped.relationshipType());
        assertEquals(Map.of("category", "AAY"), mapped.extensions());
        assertEquals("2.0.0", mapped.mappingVersion());
    }

    @Test
    void aSourceWithoutFamilyRulesHasNoFamilyMapping() {
        var pack = pack(null);
        assertFalse(engine.hasFamilyRules(pack, "src"));
        assertTrue(engine.map(raw("hh", "A1"), pack, "src").isEmpty());
    }

    @Test
    void blankHouseholdMapsToNoHouseholdUnlessRequired() {
        var optional = pack(List.of(rule("hh", "sourceFamilyIds[]", null, null)));
        assertEquals(List.of(), engine.map(raw("hh,x", ",1"), optional, "src").orElseThrow().sourceFamilyIds());
        assertEquals("UNKNOWN", engine.map(raw("hh,x", ",1"), optional, "src").orElseThrow().relationshipType());

        var required = pack(List.of(rule("hh", "sourceFamilyIds[]", null, true)));
        assertThrows(IllegalArgumentException.class, () -> engine.map(raw("hh,x", ",1"), required, "src"));
    }

    @Test
    void rejectsUnknownTargetsRelationshipsAndCrosswalkValues() {
        assertThrows(IllegalArgumentException.class, () ->
                engine.map(raw("hh", "A1"), pack(List.of(rule("hh", "address.line1", null, null))), "src"));
        var relationships = pack(List.of(rule("rel", "membership.relationshipType", "relationship", null)));
        var cousin = assertThrows(IllegalArgumentException.class, () -> engine.map(raw("rel", "Z"), relationships, "src"));
        assertTrue(cousin.getMessage().contains("COUSIN"));
        assertThrows(IllegalArgumentException.class, () -> engine.map(raw("rel", "Q"), relationships, "src"));
    }

    private static MappingRule rule(String field, String target, String crosswalk, Boolean required) {
        return new MappingRule(field, target, null, crosswalk, required);
    }

    private static LoadedStatePack pack(List<MappingRule> familyRules) {
        Map<String, List<MappingRule>> entities = familyRules == null
                ? Map.of("Resident", List.of())
                : Map.of("Resident", List.of(), "Family", familyRules);
        return new LoadedStatePack(new StatePackManifest("fx", "FX", "1.0.0", "1.0.0", "draft", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(new SourceDefinition("src", "file", "generic-file-ingest.hpl", "c", "x.csv", "hh", "1.0")),
                List.of(new MappingDefinition("2.0.0", "src", entities)),
                Map.of("relationship", new CrosswalkDefinition("relationship", Map.of("H", "HEAD", "Z", "COUSIN"))),
                List.of(), List.of(), List.of());
    }

    private RawRecord raw(String header, String line) {
        String payload = json.createObjectNode().put("csvHeader", header).put("rawLine", line).toString();
        return new RawRecord(UUID.randomUUID(), UUID.randomUUID(), "FX", "src", OffsetDateTime.now(), payload,
                Map.of("sourceEntity", "src"), "1.0.0", "1.0", "generic-file-ingest.hpl");
    }
}
