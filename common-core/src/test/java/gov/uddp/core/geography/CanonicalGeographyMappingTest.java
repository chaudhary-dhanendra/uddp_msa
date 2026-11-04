package gov.uddp.core.geography;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.mapping.RawCsvRecordParser;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.mapping.ResidentMappingEngine;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.CrosswalkDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.statepack.StatePackManifest;
import gov.uddp.core.read.Db;
import gov.uddp.core.testsupport.TestDatabase;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Track F4: generic canonical geography resolution, ancestry and legacy district projection. */
class CanonicalGeographyMappingTest {
    private final ObjectMapper json = new ObjectMapper();
    private final PostgresConnectionFactory database = TestDatabase.freshMigrated();
    private final GeographyService geography = new GeographyService(new Db(database, json));
    private final ResidentMappingEngine engine = new ResidentMappingEngine(new RawCsvRecordParser(json));

    @Test
    @SuppressWarnings("unchecked")
    void resolvesCanonicalGeographyWithoutGuessingAndKeepsDistrictCompatibility() {
        new LgdImportService(database, json).importSnapshot(FileLgdSourceClient.read(LgdMasterImportTest.FIXTURE), "test");
        engine.setGeographyService(geography);

        var exact = engine.map(raw("900", null, null), pack(List.of(rule("geo", "geography.district.lgd", null))), "source");
        assertNull(geographyOf(exact, "district").get("lgd"));
        assertEquals("UNKNOWN", geographyOf(exact, "district").get("status"));

        var code = engine.map(raw("901", null, null), pack(List.of(rule("geo", "geography.district.lgd", null))), "source");
        assertEquals("901", code.address().districtLGD()); // exact LGD and legacy address are one truth
        assertEquals("North District", geographyOf(code, "district").get("name"));

        var crosswalk = engine.map(raw("NORTH", null, null), pack(List.of(rule("geo", "geography.district.lgd", "district"))), "source");
        assertEquals("901", crosswalk.address().districtLGD());
        assertEquals("NORTH", geographyOf(crosswalk, "district").get("raw"));

        var named = engine.map(raw(null, "North District", "90"), pack(List.of(rule("parent", "geography.state.lgd", null),
                rule("name", "geography.district.name", null))), "source");
        assertEquals("901", geographyOf(named, "district").get("lgd"));

        var namedCrosswalk = engine.map(raw(null, "NORTH", "90"),
                pack(List.of(rule("parent", "geography.state.lgd", null),
                        rule("name", "geography.district.name", "district"))), "source");
        assertEquals("901", geographyOf(namedCrosswalk, "district").get("lgd"));

        var fuzzy = engine.map(raw(null, "North", "90"), pack(List.of(rule("parent", "geography.state.lgd", null),
                rule("name", "geography.district.name", null))), "source");
        assertEquals("UNKNOWN", geographyOf(fuzzy, "district").get("status"));
        assertNull(geographyOf(fuzzy, "district").get("lgd"));
        assertNotNull(geographyOf(fuzzy, "district").get("suggestions"));

        var partial = engine.map(raw("500001", "560001", null), pack(List.of(rule("geo", "geography.village.lgd", null),
                rule("name", "geography.pincode", null))), "source");
        assertEquals("500001", geographyOf(partial, "village").get("lgd"));
        assertEquals("901", partial.address().districtLGD(), "ancestor projection supplies districtLGD");
        assertEquals("560001", geographyOf(partial, "pincode").get("value"));
        assertEquals("9011", geographyOf(partial, "subDistrict").get("lgd"));

        var unknown = engine.map(raw("not-a-place", null, null), pack(List.of(rule("geo", "geography.village.name", null))), "source");
        assertEquals("UNKNOWN", geographyOf(unknown, "village").get("status"));
        assertEquals("not-a-place", geographyOf(unknown, "village").get("raw"));
        assertNull(unknown.address().districtLGD());

        assertThrows(IllegalArgumentException.class, () -> engine.map(raw("500001", null, "902"),
                pack(List.of(rule("parent", "geography.district.lgd", null), rule("geo", "geography.village.lgd", null))),
                "source"), "inconsistent explicit parents are flagged");
    }

    @Test
    void addressDistrictLgdPassesThroughUntilTheMasterIsLoaded() {
        engine.setGeographyService(geography);
        var legacy = engine.map(raw("100001", null, null), pack(List.of(rule("geo", "address.districtLGD", null))), "source");
        assertEquals("100001", legacy.address().districtLGD(), "no LGD snapshot yet: the mapped code is kept as before");
        assertEquals("UNKNOWN", geographyOf(legacy, "district").get("status"));
    }

    private LoadedStatePack pack(List<MappingRule> rules) {
        var manifest = new StatePackManifest("fixture", "FX", "1", "1", "ACTIVE", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        return new LoadedStatePack(manifest, List.of(new SourceDefinition("source", "file", "generic-file-ingest.hpl", null,
                null, "person", "1")), List.of(new MappingDefinition("1", "source", Map.of("Resident", withName(rules)))),
                Map.of("district", new CrosswalkDefinition("district", Map.of("NORTH", "901"))), List.of(), List.of(), List.of());
    }

    private List<MappingRule> withName(List<MappingRule> geography) {
        var rules = new java.util.ArrayList<MappingRule>();
        rules.add(new MappingRule("person", "name", null, null, true));
        rules.addAll(geography);
        return rules;
    }

    private MappingRule rule(String source, String target, String crosswalk) {
        return new MappingRule(source, target, null, crosswalk, false);
    }

    private RawRecord raw(String geo, String name, String parent) {
        try {
            String header = "person,geo,name,parent";
            String line = "Person," + value(geo) + "," + value(name) + "," + value(parent);
            return new RawRecord(UUID.randomUUID(), UUID.randomUUID(), "FX", "source", OffsetDateTime.now(),
                    json.writeValueAsString(Map.of("csvHeader", header, "rawLine", line)), Map.of("sourceEntity", "source"),
                    "1", "1", "generic-file-ingest.hpl");
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static String value(String value) { return value == null ? "" : value; }

    private Map<String, Object> geographyOf(gov.uddp.core.model.Resident resident, String level) {
        return (Map<String, Object>) ((Map<String, Object>) resident.extensions().get(CanonicalGeographyResolver.EXTENSION)).get(level);
    }
}
