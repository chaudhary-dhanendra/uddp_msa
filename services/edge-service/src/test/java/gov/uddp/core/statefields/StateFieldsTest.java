package gov.uddp.core.statefields;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.api.RuleSetController;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.golden.CanonicalObservation;
import gov.uddp.core.golden.SurvivorshipEngine;
import gov.uddp.core.golden.SurvivorshipPolicy;
import gov.uddp.core.mapping.RawCsvRecordParser;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.mapping.ResidentMappingEngine;
import gov.uddp.core.privacy.PiiMasker;
import gov.uddp.core.rules.RuleConditions;
import gov.uddp.core.scheme.SchemeImpactService;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.MappingDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.statepack.StatePackAssets.StateField;
import gov.uddp.core.statepack.StatePackLoadException;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.statepack.StatePackManifest;
import gov.uddp.core.studio.PackGenerator;
import gov.uddp.core.studio.StudioModels;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** State fields: declared in a pack, typed on mapping, carried to Golden, masked, and usable by scheme rules. */
class StateFieldsTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ResidentMappingEngine engine = new ResidentMappingEngine(new RawCsvRecordParser(json));

    @TempDir
    Path temp;

    @Test
    void studioDraftGeneratesAStateFieldsFileThatThePackLoaderReads() throws Exception {
        var rules = List.of(
                new StudioModels.MappingRule("person_name", "Resident", "name", null, null, true),
                new StudioModels.MappingRule("disability_pct", "Resident", "extensions.disabilityPct", null, null, null,
                        new StudioModels.StateFieldSpec("Disability percentage", "number", true)));
        var source = new StudioModels.Source("welfare", "file", "welfare.csv", temp.resolve("welfare.csv").toString(),
                10L, "person_name", null, null, null, null, null, null, null);
        var draft = new StudioModels.Draft("d1", "state-9", "ST9", "State 9", "1.0.0",
                StudioModels.DraftStatus.DRAFT, OffsetDateTime.now(), OffsetDateTime.now(), source, rules, List.of(),
                new StudioModels.Rules(List.of(), new StudioModels.Matching(List.of("name"), 0.9, 0.7), Map.of()),
                null, null, null);

        Path pack = temp.resolve("state-9");
        for (var file : new PackGenerator().generate(draft, "1.0.0", temp)) {
            Path target = pack.resolve(file.path());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content());
        }
        assertTrue(Files.readString(pack.resolve("state.yaml")).contains("extensions: [extensions/resident.yaml]"));

        LoadedStatePack loaded = loader().load(pack);
        assertEquals(List.of(new StateField("disabilityPct", "Disability percentage", "number", true)),
                loaded.stateFields());

        // A pack round-trips through its stored JSON document with its state fields.
        LoadedStatePack stored = json.readValue(json.writeValueAsString(loaded), LoadedStatePack.class);
        assertEquals(loaded.stateFields(), stored.stateFields());
    }

    @Test
    void draftWithoutStateFieldsKeepsItsManifestUnchanged() {
        var source = new StudioModels.Source("welfare", "file", "welfare.csv", temp.resolve("welfare.csv").toString(),
                10L, "person_name", null, null, null, null, null, null, null);
        var draft = new StudioModels.Draft("d1", "state-9", "ST9", "State 9", "1.0.0",
                StudioModels.DraftStatus.DRAFT, OffsetDateTime.now(), OffsetDateTime.now(), source,
                List.of(new StudioModels.MappingRule("person_name", "Resident", "name", null, null, true)), List.of(),
                new StudioModels.Rules(List.of(), new StudioModels.Matching(List.of("name"), 0.9, 0.7), Map.of()),
                null, null, null);
        var files = new PackGenerator().generate(draft, "1.0.0", temp);
        assertFalse(files.stream().anyMatch(file -> file.path().startsWith("extensions/")));
        assertTrue(files.getFirst().content().endsWith("survivorship: [survivorship/resident.yaml]\n"));
    }

    @Test
    void loaderRejectsBadStateFieldDeclarations() {
        Path pack = temp.resolve("p");
        assertThrows(StatePackLoadException.class, () -> StatePackLoader.validateStateFields(
                List.of(new StateField("Disability.pct", "x", "number", true)), pack));
        assertThrows(StatePackLoadException.class, () -> StatePackLoader.validateStateFields(
                List.of(new StateField("pct", "x", "money", true)), pack));
        assertThrows(StatePackLoadException.class, () -> StatePackLoader.validateStateFields(
                List.of(new StateField("pct", "x", "number", true), new StateField("pct", "y", "text", false)), pack));
        assertThrows(StatePackLoadException.class, () -> StatePackLoader.validateStateFields(
                List.of(new StateField("uddpThing", "x", "text", true)), pack));
    }

    @Test
    void mappingTypesADeclaredStateFieldAndRejectsAValueThatDoesNotFit() {
        var pack = pack(List.of(new StateField("disabilityPct", "Disability percentage", "number", true)));
        var resident = engine.map(raw("person,pct,blood", "Asha Devi,55,B+"), pack, "src");
        assertEquals(new BigDecimal("55"), resident.extensions().get("disabilityPct"));
        // An undeclared, unmapped column is still kept as a plain extension.
        assertEquals("B+", resident.extensions().get("blood"));

        var error = assertThrows(IllegalArgumentException.class,
                () -> engine.map(raw("person,pct,blood", "Asha Devi,high,B+"), pack, "src"));
        assertTrue(error.getMessage().contains("disabilityPct"));
    }

    @Test
    void goldenCarriesAStateFieldFromItsOwnStateCodeOnly() throws Exception {
        var st9 = observation("00000000-0000-0000-0000-000000000001", "ST9",
                "{\"name\":\"Asha Devi\",\"extensions\":{\"disabilityPct\":55}}");
        var other = observation("00000000-0000-0000-0000-000000000002", "ST1",
                "{\"name\":\"Asha Devi\",\"extensions\":{\"disabilityPct\":10}}");
        var goldenFields = SurvivorshipPolicy.goldenStateFields(List.of(
                pack(List.of(new StateField("disabilityPct", "Disability percentage", "number", true),
                        new StateField("note", "Note", "text", false)))));
        assertEquals(Map.of("ST9", List.of("disabilityPct")), goldenFields);

        var engine = new SurvivorshipEngine();
        var fields = engine.survive(List.of(st9, other), SurvivorshipPolicy.V1, null, goldenFields);
        var field = fields.get("stateFields.ST9.disabilityPct");
        assertEquals(55, field.value().asInt());
        assertEquals(st9.id(), field.selectedCanonicalId());
        assertEquals(List.of(st9.id()), field.contributors());

        var payload = engine.payload(fields, List.of(st9, other), json);
        assertEquals(55, payload.path("stateFields").path("ST9").path("disabilityPct").asInt());
        assertEquals("Asha Devi", payload.path("name").asText());

        // Without declarations nothing new reaches Golden.
        assertNull(engine.survive(List.of(st9), SurvivorshipPolicy.V1).get("stateFields.ST9.disabilityPct"));
    }

    @Test
    void stateFieldValuesAreMaskedUntilRevealed() throws Exception {
        var golden = json.readTree("{\"name\":\"Asha Devi\",\"stateFields\":{\"ST9\":{\"disabilityPct\":55.5}},"
                + "\"provenance\":[{\"fieldPath\":\"stateFields.ST9.disabilityPct\",\"value\":55.5}]}");
        var masked = PiiMasker.mask(golden);
        assertEquals("•••", masked.path("stateFields").path("ST9").path("disabilityPct").asText());
        assertEquals("•••", masked.path("provenance").get(0).path("value").asText());
    }

    @Test
    void schemeRulesCanReadAGoldenStateField() {
        var field = RuleSetController.stateField("ST9", new StateField("disabilityPct", "Disability percentage",
                "number", true));
        assertEquals("stateFields.ST9.disabilityPct", field.name());
        assertEquals(RuleConditions.DataType.NUMBER, field.dataType());
        assertTrue(field.label().contains("Disability percentage"));

        var pack = pack(List.of(new StateField("disabilityPct", "Disability percentage", "number", true)));
        assertEquals("extensions.disabilityPct", SchemeImpactService.suppliedTarget("stateFields.ST9.disabilityPct", pack));
        assertNull(SchemeImpactService.suppliedTarget("stateFields.ST1.disabilityPct", pack));
        assertEquals("gender", SchemeImpactService.suppliedTarget("gender", pack));
    }

    private LoadedStatePack pack(List<StateField> stateFields) {
        var manifest = new StatePackManifest("fixture", "ST9", "1", "1", "ACTIVE", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        var source = new SourceDefinition("src", "file", "generic-file-ingest.hpl", null, null, "person", "1");
        var rules = List.of(new MappingRule("person", "name", null, null, true),
                new MappingRule("pct", "extensions.disabilityPct", null, null, false));
        return new LoadedStatePack(manifest, List.of(source),
                List.of(new MappingDefinition("1", "src", Map.of("Resident", rules))), Map.of(), List.of(), List.of(),
                List.of(), stateFields);
    }

    private CanonicalObservation observation(String id, String state, String payload) throws Exception {
        return new CanonicalObservation(UUID.fromString(id), state, "src", json.readTree(payload));
    }

    private RawRecord raw(String header, String line) {
        try {
            return new RawRecord(UUID.randomUUID(), UUID.randomUUID(), "ST9", "src",
                    OffsetDateTime.parse("2026-10-24T00:00:00Z"),
                    json.writeValueAsString(Map.of("csvHeader", header, "rawLine", line, "sourceEntity", "src")),
                    Map.of("sourceEntity", "src"), "1.0.0", "1.0", "generic-file-ingest.hpl");
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private StatePackLoader loader() {
        var properties = new StatePackProperties();
        properties.setStatePackDirectory(temp.toString());
        return new StatePackLoader(properties);
    }
}
