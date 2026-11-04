package gov.uddp.core.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.model.Gender;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.MappingDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.statepack.StatePackManifest;
import java.util.List;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResidentMappingEngineTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ResidentMappingEngine engine = new ResidentMappingEngine(new RawCsvRecordParser(json));

    @Test void oneGenericEngineMapsAllThreeStatePacksAndPreservesUnmappedFields() {
        Map<String, LoadedStatePack> packs = loader().loadAll(Path.of("../../state-packs"));

        var state1 = engine.map(raw("ST1", "pds", "beneficiary_nm,dob,sex_cd,dist_cd,ration_no,mobile",
                "Ram Singh,12-03-1980,M,101,R1001,9876543210"), packs.get("state-1"), "pds");
        assertEquals("Ram Singh", state1.name());
        assertEquals("1980-03-12", state1.dateOfBirth().toString());
        assertEquals(Gender.MALE, state1.gender());
        assertEquals("100001", state1.address().districtLGD());
        assertEquals("R1001", state1.extensions().get("familyReference"));

        var state2Male = engine.map(raw("ST2", "pension", "ben_name,birth_dt,gender,district_name,family_ref,phone",
                "Ram Singh,19800312,1,Patna,F5001,9876543210"), packs.get("state-2"), "pension");
        var state2Female = engine.map(raw("ST2", "pension", "ben_name,birth_dt,gender,district_name,family_ref,phone",
                "Anita Devi,19780217,2,Gaya,F5002,9876543230"), packs.get("state-2"), "pension");
        assertEquals("1980-03-12", state2Male.dateOfBirth().toString());
        assertEquals(Gender.MALE, state2Male.gender());
        assertEquals(Gender.FEMALE, state2Female.gender());
        assertEquals("F5001", state2Male.extensions().get("familyReference"));

        var state3 = engine.map(raw("ST3", "beneficiaries", "person_name,dob,gender,district,family_id,mobile,blood_group,disability_pct",
                "Ram Singh,1980-03-12,M,Patna,F9001,9876543210,B+,20"), packs.get("state-3"), "beneficiaries");
        assertEquals("1980-03-12", state3.dateOfBirth().toString());
        assertEquals("F9001", state3.extensions().get("familyReference"));
        assertEquals("B+", state3.extensions().get("blood_group"));
        assertEquals("20", state3.extensions().get("disability_pct"));
    }

    @Test void parsesQuotedCsvWithoutSplitSemantics() {
        var fields = new RawCsvRecordParser(json).parse("{\"csvHeader\":\"name,note\",\"rawLine\":\"\\\"Singh, Ram\\\",\\\"quoted, value\\\"\"}");
        assertEquals("Singh, Ram", fields.get("name"));
        assertEquals("quoted, value", fields.get("note"));
    }

    @Test void v10PayloadsGainNoV11Keys() throws Exception {
        var packs = loader().loadAll(Path.of("../../state-packs"));
        var resident = engine.map(raw("ST1", "pds", "beneficiary_nm,dob,sex_cd,dist_cd,ration_no,mobile",
                "Ram Singh,12-03-1980,M,101,R1001,9876543210"), packs.get("state-1"), "pds");
        var keys = new ObjectMapper().findAndRegisterModules().valueToTree(resident).fieldNames();
        var names = new java.util.ArrayList<String>();
        keys.forEachRemaining(names::add);
        assertEquals(List.of("residentId", "name", "dateOfBirth", "gender", "mobile", "address", "sourceReferences",
                "extensions", "metadata"), names);
        assertNull(resident.dateOfBirthPrecision());
        assertNull(resident.relations());
    }

    @Test void v11TargetsAreMapped() {
        var rules = List.of(
                new MappingRule("person", "name", null, null, true),
                new MappingRule("norm", "nameNormalised", null, null, false),
                new MappingRule("father", "relations[].name", "relation:father", null, false),
                new MappingRule("father_norm", "relations[].nameNormalised", null, null, false),
                new MappingRule("father_late", "relations[].deceased", null, null, false),
                new MappingRule("husband", "relations[].name", "relation:HUSBAND", null, false),
                new MappingRule("age", "age.years", null, null, false),
                new MappingRule("cat", "socialCategory", null, null, false),
                new MappingRule("addr", "geography.freeText", null, null, false),
                new MappingRule("dob", "dateOfBirth", "date:YYYY-MM-DD", null, false));
        var header = "person,norm,father,father_norm,father_late,husband,age,cat,addr,dob";
        var line = "Ram Singh,ram singh,Mohan Singh,mohan singh,true,Sita Devi,45,OBC,near the temple,1980-01-01";

        var withDate = engine.map(raw("ST9", "src", header, line), pack("2026-01-31", rules), "src");
        assertEquals("ram singh", withDate.nameNormalised());
        assertEquals(2, withDate.relations().size());
        assertEquals("FATHER", withDate.relations().get(0).type());
        assertEquals("mohan singh", withDate.relations().get(0).nameNormalised());
        assertEquals(Boolean.TRUE, withDate.relations().get(0).deceased());
        assertEquals("HUSBAND", withDate.relations().get(1).type());
        assertEquals(45, withDate.age().years());
        assertEquals("2026-01-31", withDate.age().asOf().toString());
        assertEquals("OBC", withDate.socialCategory());
        assertEquals("near the temple", withDate.geography().freeText());
        // a date of birth on 1 January is year-precision only
        assertEquals("YEAR", withDate.dateOfBirthPrecision());
        assertEquals(1980, withDate.birthYear());

        var noDob = engine.map(raw("ST9", "src", header.replace(",dob", ""), line.replace(",1980-01-01", "")),
                pack(null, rules.subList(0, rules.size() - 1)), "src");
        assertEquals("2024-05-06", noDob.age().asOf().toString(), "falls back to the raw ingestion date");
        assertEquals(2024 - 45, noDob.birthYear());
        assertNull(noDob.dateOfBirthPrecision());
    }

    @Test void aDateOfBirthFromAParserIsReadAsAnIsoDateOfDayMonthOrYearPrecision() {
        // A parse: step (date-text, a calendar plug-in) outputs ISO dates; the rule then carries no date: conversion.
        var rules = List.of(new MappingRule("person", "name", null, null, true),
                new MappingRule("dob", "dateOfBirth", null, null, false));
        var day = engine.map(raw("ST9", "src", "person,dob", "Asha Test,1958-03-12"), pack(null, rules), "src");
        assertEquals("1958-03-12", day.dateOfBirth().toString());
        assertNull(day.dateOfBirthPrecision());

        var year = engine.map(raw("ST9", "src", "person,dob", "Asha Test,1958"), pack(null, rules), "src");
        assertEquals("1958-01-01", year.dateOfBirth().toString());
        assertEquals("YEAR", year.dateOfBirthPrecision());
        assertEquals(1958, year.birthYear());

        var month = engine.map(raw("ST9", "src", "person,dob", "Asha Test,1958-01"), pack(null, rules), "src");
        assertEquals("1958-01-01", month.dateOfBirth().toString());
        assertEquals("MONTH", month.dateOfBirthPrecision(), "a January month is not mistaken for a year");
        assertEquals(1958, month.birthYear());

        var error = assertThrows(gov.uddp.core.validation.ReasonCodes.ReasonedException.class,
                () -> engine.map(raw("ST9", "src", "person,dob", "Asha Test,12/3/58"), pack(null, rules), "src"));
        assertEquals(gov.uddp.core.validation.ReasonCodes.UNREADABLE_DATE, error.code());
    }

    @Test void aParserOutputWithNoValueIsNotKeptAsAnExtension() {
        // An address the free-address parser cannot resolve leaves its output columns null.
        var csv = new RawCsvRecordParser(json);
        gov.uddp.shaping.api.RecordShaper shaper = new gov.uddp.shaping.api.RecordShaper() {
            @Override public Shaped shapeWithWarnings(RawRecord raw, LoadedStatePack pack, String sourceId) {
                Map<String, Object> fields = new java.util.LinkedHashMap<>(csv.parse(raw.rawPayload()));
                fields.put("address_village", null);
                return new Shaped(new gov.uddp.shaping.api.TidyRecord(fields, Map.of()), List.of());
            }
            @Override public boolean shaped(LoadedStatePack pack, String sourceId) { return true; }
            @Override public String digest(LoadedStatePack pack, String sourceId) { return "test"; }
        };
        var rules = List.of(new MappingRule("person", "name", null, null, true));
        var resident = new ResidentMappingEngine(shaper).map(raw("ST9", "src", "person,note", "Asha Test,kept"),
                pack(null, rules), "src");
        assertEquals("kept", resident.extensions().get("note"));
        assertFalse(resident.extensions().containsKey("address_village"));
    }

    private LoadedStatePack pack(String asOfDate, List<MappingRule> rules) {
        var manifest = new StatePackManifest("fixture", "ST9", "1", "1", "ACTIVE", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        var source = new SourceDefinition("src", "file", "generic-file-ingest.hpl", null, null, "person", "1", null,
                null, null, null, null, null, null, asOfDate);
        return new LoadedStatePack(manifest, List.of(source),
                List.of(new MappingDefinition("1", "src", Map.of("Resident", rules))), Map.of(), List.of(), List.of(),
                List.of());
    }

    private StatePackLoader loader() {
        StatePackProperties properties = new StatePackProperties();
        properties.setStatePackDirectory("../../state-packs");
        properties.setFixtureStatePacksEnabled(true);
        var loader = new StatePackLoader(properties);
        loader.reload();
        return loader;
    }

    private RawRecord raw(String stateCode, String sourceSystem, String header, String line) {
        try {
            return new RawRecord(UUID.randomUUID(), UUID.randomUUID(), stateCode, sourceSystem, OffsetDateTime.parse(stateCode.equals("ST9") ? "2024-05-06T00:00:00Z" : "2026-10-24T00:00:00Z"),
                    json.writeValueAsString(Map.of("csvHeader", header, "rawLine", line, "sourceEntity", sourceSystem)),
                    Map.of("sourceEntity", sourceSystem), "1.0.0", "1.0", "generic-file-ingest.hpl");
        } catch (Exception error) { throw new AssertionError(error); }
    }
}
