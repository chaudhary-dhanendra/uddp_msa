package gov.uddp.core.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.scheme.SchemeCatalogue;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.CrosswalkDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.statepack.StatePackManifest;
import gov.uddp.core.testsupport.SampleData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BenefitMappingEngineTest {
    private final ObjectMapper json = new ObjectMapper();
    private final BenefitMappingEngine engine = new BenefitMappingEngine(new RawCsvRecordParser(json),
            new SchemeCatalogue(SampleData.SCHEME_CATALOGUE));

    private static final String HEADER = "code,order,st,since,amt,paid,tag";
    private static final List<MappingRule> PENSION = List.of(
            rule("code", "schemeCode", "scheme", null, null),
            rule("order", "sourceBenefitId", null, null, null),
            rule("st", "status", "status", null, null),
            rule("since", "startDate", null, "date:YYYYMMDD", null),
            rule("amt", "entitlement.amount", null, null, null),
            rule("paid", "disbursement.date", null, "date:YYYYMMDD", null),
            rule("amt", "disbursement.amount", null, null, null),
            rule("tag", "extensions.channel", null, null, null));

    @Test
    void mapsSchemeThroughTheCrosswalkAndCatalogueWithEntitlementDisbursementAndDefaults() {
        var mapped = engine.map(raw(HEADER, "OAP,PO-1,1,20250401,1000,20261001,bank"), pack(PENSION), "src")
                .orElseThrow();

        assertEquals("2.0.0", mapped.mappingVersion());
        assertEquals("1.0.0", mapped.catalogueVersion());
        var benefit = mapped.benefit().orElseThrow();
        assertEquals("OAP", benefit.sourceSchemeCode());
        assertEquals("OLD-AGE-PENSION", benefit.scheme().schemeId());
        assertEquals("PO-1", benefit.sourceBenefitId());
        assertEquals("ACTIVE", benefit.status());
        assertEquals(LocalDate.of(2025, 4, 1), benefit.startDate());
        assertEquals(new BigDecimal("1000"), benefit.amount());
        // Unit and frequency come from the catalogue unless the pack maps them.
        assertEquals("INR", benefit.unit());
        assertEquals("MONTHLY", benefit.frequency());
        assertEquals(LocalDate.of(2026, 10, 1), benefit.disbursement().date());
        assertEquals(new BigDecimal("1000"), benefit.disbursement().amount());
        assertEquals(Map.of("channel", "bank"), benefit.extensions());
    }

    @Test
    void aBlankSchemeNamesNoBenefitAndAbsentRulesNoMapping() {
        var none = engine.map(raw(HEADER, ",,,,,,"), pack(PENSION), "src").orElseThrow();
        assertTrue(none.benefit().isEmpty());
        assertTrue(engine.map(raw("code", "OAP"), pack(null), "src").isEmpty());
        // Without a status rule or value the benefit is ACTIVE; without a date there is no disbursement.
        var minimal = engine.map(raw("code,order", "OAP,PO-2"), pack(PENSION.subList(0, 2)), "src").orElseThrow();
        assertEquals("ACTIVE", minimal.benefit().orElseThrow().status());
        assertNull(minimal.benefit().orElseThrow().disbursement());
        var required = List.of(rule("code", "schemeCode", "scheme", null, true));
        assertThrows(IllegalArgumentException.class, () -> engine.map(raw("code,x", ",1"), pack(required), "src"));
    }

    @Test
    void householdBenefitsNeedAHousehold() {
        var rules = List.of(rule("code", "schemeCode", "scheme", null, null),
                rule("card", "sourceBenefitId", null, null, null), rule("hh", "sourceFamilyId", null, null, null));
        var mapped = engine.map(raw("code,card,hh", "PDS,R1,R1"), pack(rules), "src").orElseThrow()
                .benefit().orElseThrow();
        assertEquals("FAMILY", mapped.scheme().beneficiaryLevel());
        assertEquals("R1", mapped.sourceFamilyId());
        assertEquals("KG", mapped.unit());
        var error = assertThrows(IllegalArgumentException.class, () -> engine.map(raw("code,card,hh", "PDS,R1,"),
                pack(rules), "src"));
        assertTrue(error.getMessage().contains("sourceFamilyId"), error.getMessage());
    }

    @Test
    void keepsAnUnmappedSchemeCodeAsUnmappedWithAWarningInsteadOfRejecting() {
        var mapped = engine.map(raw(HEADER, "XYZ,PO-1,1,20250401,1000,20261001,a"), pack(PENSION), "src").orElseThrow();

        var benefit = mapped.benefit().orElseThrow();
        assertEquals("UNMAPPED", benefit.scheme().schemeId());
        assertEquals("XYZ", benefit.sourceSchemeCode());
        assertEquals("PO-1", benefit.sourceBenefitId());
        assertEquals(List.of("No crosswalk value for scheme: XYZ"), mapped.warnings());
        assertTrue(engine.map(raw(HEADER, "OAP,PO-1,1,20250401,1000,20261001,a"), pack(PENSION), "src")
                .orElseThrow().warnings().isEmpty());
    }

    @Test
    void rejectsUnknownTargetsSchemesStatusesNumbersAndMissingIds() {
        String[][] cases = {
            {"OAP,PO-1,7,20250401,1000,20261001,a", "No crosswalk value for status: 7"},
            {"GHOST,PO-1,1,20250401,1000,20261001,a", "not in scheme catalogue"},
            {"OAP,,1,20250401,1000,20261001,a", "sourceBenefitId"},
            {"OAP,PO-1,9,20250401,1000,20261001,a", "Unsupported benefit status 'PAID'"},
            {"OAP,PO-1,1,20250401,1k,20261001,a", "Not a number"},
            {"OAP,PO-1,1,2025-04-01,1000,20261001,a", "Invalid date"},
            {"OAP,PO-1,1,20250401,1000,,a", "needs a disbursement.date"},
        };
        for (String[] c : cases) {
            var error = assertThrows(IllegalArgumentException.class, () -> engine.map(raw(HEADER, c[0]), pack(PENSION),
                    "src"));
            assertTrue(error.getMessage().contains(c[1]), c[1] + " <- " + error.getMessage());
        }
        var unknown = assertThrows(IllegalArgumentException.class, () -> engine.map(raw("code", "OAP"),
                pack(List.of(rule("code", "benefit.owner", null, null, null))), "src"));
        assertTrue(unknown.getMessage().contains("Unsupported Benefit mapping target"));
    }

    private static MappingRule rule(String field, String target, String crosswalk, String conversion, Boolean required) {
        return new MappingRule(field, target, conversion, crosswalk, required);
    }

    private static LoadedStatePack pack(List<MappingRule> benefitRules) {
        Map<String, List<MappingRule>> entities = benefitRules == null
                ? Map.of("Resident", List.of())
                : Map.of("Resident", List.of(), "Benefit", benefitRules);
        return new LoadedStatePack(new StatePackManifest("fx", "FX", "1.0.0", "1.0.0", "draft", List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of()),
                List.of(new SourceDefinition("src", "file", "generic-file-ingest.hpl", "c", "x.csv", "order", "1.0")),
                List.of(new MappingDefinition("2.0.0", "src", entities)),
                Map.of("scheme", new CrosswalkDefinition("scheme", Map.of("OAP", "OLD-AGE-PENSION", "PDS", "PDS-RATION",
                                "GHOST", "GHOST-SCHEME")),
                        "status", new CrosswalkDefinition("status", Map.of("1", "ACTIVE", "2", "SUSPENDED", "9", "PAID"))),
                List.of(), List.of(), List.of());
    }

    private RawRecord raw(String header, String line) {
        String payload = json.createObjectNode().put("csvHeader", header).put("rawLine", line).toString();
        return new RawRecord(UUID.randomUUID(), UUID.randomUUID(), "FX", "src", OffsetDateTime.now(), payload,
                Map.of("sourceEntity", "src"), "1.0.0", "1.0", "generic-file-ingest.hpl");
    }
}
