package gov.uddp.core.mapping;

import static gov.uddp.core.testsupport.AssetFixture.HEADER;
import static gov.uddp.core.testsupport.AssetFixture.RULES;
import static gov.uddp.core.testsupport.AssetFixture.pack;
import static gov.uddp.core.testsupport.AssetFixture.raw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.mapping.AssetMappingEngine.MappedAsset;
import gov.uddp.core.testsupport.ApplicationFixture;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssetMappingEngineTest {
    private final AssetMappingEngine engine = new AssetMappingEngine(new RawCsvRecordParser(new ObjectMapper()));

    private MappedAsset map(String line) {
        return engine.map(raw(UUID.randomUUID(), UUID.randomUUID(), "FX", HEADER, line), pack("1.0.0", RULES), "src")
                .orElseThrow().asset().orElseThrow();
    }

    @Test
    void mapsTypeQuantityUnitValueBenefitLinkAttributesAndExtensions() {
        var mapped = engine.map(raw(UUID.randomUUID(), UUID.randomUUID(), "FX", HEADER,
                "S-1,PV,Rooftop panel,3,kw,20000.50,B-7,K|1,blue,portal"), pack("1.0.0", RULES), "src")
                .orElseThrow();
        assertEquals("2.0.0", mapped.mappingVersion());
        var a = mapped.asset().orElseThrow();
        assertEquals("S-1", a.sourceAssetId());
        assertEquals("SOLAR_INSTALLATION", a.assetType());
        assertEquals("Rooftop panel", a.description());
        assertEquals(new BigDecimal("3"), a.quantity());
        assertEquals("KW", a.unit());
        assertEquals(new BigDecimal("20000.50"), a.value());
        assertEquals("B-7", a.linkedSourceBenefitId());
        assertEquals("K|1", a.holderSourceRecordKey());
        assertEquals(Map.of("color", "blue"), a.attributes());
        assertEquals(Map.of("channel", "portal"), a.extensions());
    }

    @Test
    void optionalFieldsMayBeBlankAndABlankAssetIdNamesNoAsset() {
        var minimal = map("S-2,COW,,,,,,,,");
        assertNull(minimal.quantity());
        assertNull(minimal.unit());
        assertNull(minimal.linkedSourceBenefitId());
        assertTrue(minimal.attributes().isEmpty());
        assertTrue(engine.map(raw(UUID.randomUUID(), UUID.randomUUID(), "FX", HEADER, ",COW,,,,,,,,"),
                pack("1.0.0", RULES), "src").orElseThrow().asset().isEmpty());
        assertTrue(engine.map(raw(UUID.randomUUID(), UUID.randomUUID(), "FX", "asset", "S-3"), pack("1.0.0", null), "src")
                .isEmpty(), "a source without Asset rules maps nothing");
    }

    @Test
    void rejectsUnknownTypesCodesNumbersAndTargets() {
        String[][] cases = {
            {"S-1,XYZ,,,,,,,,", "No crosswalk value for kind: XYZ"},
            {"S-1,ODD,,,,,,,,", "Unknown assetType SPACESHIP"},
            {"S-1,CAR,,two,,,,,,", "quantity is not a number"},
            {"S-1,CAR,,,,lots,,,,", "value is not a number"},
        };
        for (String[] c : cases) {
            var error = assertThrows(IllegalArgumentException.class, () -> map(c[0]));
            assertTrue(error.getMessage().contains(c[1]), c[1] + " <- " + error.getMessage());
        }
        var noType = List.of(ApplicationFixture.rule("asset", "sourceAssetId", null, null));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> engine.map(
                raw(UUID.randomUUID(), UUID.randomUUID(), "FX", "asset", "S-1"), pack("1.0.0", noType), "src"))
                .getMessage().contains("needs an assetType"));
        var unknown = List.of(ApplicationFixture.rule("asset", "asset.owner", null, null));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> engine.map(
                raw(UUID.randomUUID(), UUID.randomUUID(), "FX", "asset", "S-1"), pack("1.0.0", unknown), "src"))
                .getMessage().contains("Unsupported Asset mapping target"));
    }
}
