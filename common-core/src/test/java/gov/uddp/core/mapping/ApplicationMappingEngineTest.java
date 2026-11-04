package gov.uddp.core.mapping;

import static gov.uddp.core.testsupport.ApplicationFixture.HEADER;
import static gov.uddp.core.testsupport.ApplicationFixture.RULES;
import static gov.uddp.core.testsupport.ApplicationFixture.pack;
import static gov.uddp.core.testsupport.ApplicationFixture.raw;
import static gov.uddp.core.testsupport.ApplicationFixture.rule;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.mapping.ApplicationMappingEngine.MappedApplication;
import gov.uddp.core.scheme.SchemeCatalogue;
import gov.uddp.core.testsupport.SampleData;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApplicationMappingEngineTest {
    private final ApplicationMappingEngine engine = new ApplicationMappingEngine(
            new RawCsvRecordParser(new ObjectMapper()), new SchemeCatalogue(SampleData.SCHEME_CATALOGUE));

    private MappedApplication map(String header, String line, List<gov.uddp.core.statepack.StatePackAssets.MappingRule> rules) {
        return engine.map(raw(UUID.randomUUID(), UUID.randomUUID(), "FX", header, line), pack("1.0.0", rules), "src")
                .orElseThrow().application().orElseThrow();
    }

    @Test
    void mapsSchemeStageStatusDatesAttributesAndExtensions() {
        var mapped = engine.map(raw(UUID.randomUUID(), UUID.randomUUID(), "FX", HEADER,
                "A-1,OAP,K|1,VERIFICATION,PENDING,APPROVAL,20260105,20260110,online,portal"), pack("1.0.0", RULES), "src")
                .orElseThrow();

        assertEquals("2.0.0", mapped.mappingVersion());
        assertEquals("1.0.0", mapped.catalogueVersion());
        var a = mapped.application().orElseThrow();
        assertEquals("OAP", a.sourceSchemeCode());
        assertEquals("OLD-AGE-PENSION", a.scheme().schemeId());
        assertEquals("A-1", a.sourceApplicationId());
        assertEquals("K|1", a.applicantSourceRecordKey());
        assertEquals("VERIFICATION", a.currentStage());
        assertEquals("PENDING", a.currentStatus());
        assertEquals("APPROVAL", a.nextStage());
        assertEquals(LocalDate.of(2026, 1, 5), a.submittedOn());
        assertEquals(LocalDate.of(2026, 1, 10), a.lastActionDate());
        assertEquals(Map.of("mode", "online"), a.attributes());
        assertEquals(Map.of("channel", "portal"), a.extensions());
    }

    @Test
    void optionalFieldsMayBeBlankAndABlankApplicationIdNamesNoApplication() {
        var minimal = map(HEADER, "A-2,PDS,,FILED,OPEN,,,,,", RULES);
        assertNull(minimal.applicantSourceRecordKey());
        assertNull(minimal.nextStage());
        assertNull(minimal.submittedOn());
        assertTrue(minimal.attributes().isEmpty());

        var none = engine.map(raw(UUID.randomUUID(), UUID.randomUUID(), "FX", HEADER, ",PDS,,FILED,OPEN,,,,,"),
                pack("1.0.0", RULES), "src").orElseThrow();
        assertTrue(none.application().isEmpty());
        assertTrue(engine.map(raw(UUID.randomUUID(), UUID.randomUUID(), "FX", "app", "A-3"), pack("1.0.0", null), "src")
                .isEmpty(), "a source without Application rules maps nothing");
    }

    @Test
    void rejectsUnknownTargetsSchemesAndMissingStageOrStatus() {
        String[][] cases = {
            {"A-1,XYZ,,S,ST,,,,,", "No crosswalk value for scheme: XYZ"},
            {"A-1,GHOST,,S,ST,,,,,", "not in scheme catalogue"},
            {"A-1,OAP,,,ST,,,,,", "needs a currentStage"},
            {"A-1,OAP,,S,,,,,,", "needs a currentStatus"},
            {"A-1,OAP,,S,ST,,2026-01-05,,,", "Invalid date"},
        };
        for (String[] c : cases) {
            var error = assertThrows(IllegalArgumentException.class, () -> map(HEADER, c[0], RULES));
            assertTrue(error.getMessage().contains(c[1]), c[1] + " <- " + error.getMessage());
        }
        var noScheme = List.of(rule("app", "sourceApplicationId", null, null), rule("stage", "currentStage", null, null));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> map("app,stage", "A-1,S", noScheme))
                .getMessage().contains("schemeCode"));
        var unknown = List.of(rule("app", "application.owner", null, null));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> map("app", "A-1", unknown))
                .getMessage().contains("Unsupported Application mapping target"));
    }
}
