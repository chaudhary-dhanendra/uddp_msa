package gov.uddp.core.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import gov.uddp.core.mapping.RawCsvRecordParser;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.mapping.ResidentMappingEngine;
import gov.uddp.core.privacy.NationalIdLeakGuard;
import gov.uddp.core.privacy.NationalIdProtector;
import gov.uddp.shaping.parsers.IdClassifyParser;
import gov.uddp.shaping.parsers.MaskedIdParser;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.MappingDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.statepack.StatePackManifest;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Track E2: {@code identifiers.<TYPE>} mapping targets (and the {@code nationalIds.<TYPE>} alias) are validated,
 * normalised and protected by their registry type into one typed extension; household identifiers are kept for the
 * Family layer; an invalid or unknown identifier is a mapping error that names the field, never the value; and the
 * clear value is nowhere in the canonical record.
 */
class IdentifierMappingTest {
    private static final String AADHAAR = "234567890124";
    private static final String RATION = "RC-445566";
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final NationalIdProtector hashes = new NationalIdProtector(KEY, null);
    private final IdentifierTypeRegistry registry =
            IdentifierTypeRegistry.fromSeed(Path.of("../platform/identity/identifier-types.yaml"));
    private final ResidentMappingEngine engine = new ResidentMappingEngine(new RawCsvRecordParser(JSON));

    IdentifierMappingTest() {
        engine.setIdentifiers(new IdentifierProtection(registry, hashes));
    }

    @Test
    void theAadhaarAliasStillWorks() throws Exception {
        JsonNode resident = map(List.of(rule("aadhaar_no", "nationalIds.AADHAAR")), Map.of("aadhaar_no", "2345 6789 0124"));
        JsonNode id = resident.path("extensions").path(IdentifierProtection.EXTENSION).path("AADHAAR");
        assertEquals("PERSON", id.path("subject").asText());
        assertEquals("KEYED_HASH", id.path("storage").asText());
        assertEquals("0124", id.path("last4").asText());
        assertEquals(hashes.protect("AADHAAR", AADHAAR).keyedHash(), id.path("value").asText(),
                "the alias keeps the P1 keyed hash, so stored IDs still compare");
        assertTrue(id.path("issuer").isMissingNode(), "a NATIONAL identifier has no issuer");
        JsonNode same = map(List.of(rule("aadhaar_no", "identifiers.AADHAAR")), Map.of("aadhaar_no", AADHAAR));
        assertEquals(id.path("value"), same.path("extensions").path(IdentifierProtection.EXTENSION)
                .path("AADHAAR").path("value"));
    }

    @Test
    void aRationCardIsAHouseholdIdentifierKeyedByIssuer() throws Exception {
        JsonNode resident = map(List.of(rule("ration_no", "identifiers.RATION_CARD")), Map.of("ration_no", RATION));
        JsonNode id = resident.path("extensions").path(IdentifierProtection.EXTENSION).path("RATION_CARD");
        assertEquals("HOUSEHOLD", id.path("subject").asText());
        assertEquals("ISSUER", id.path("scope").asText());
        assertEquals("ST9", id.path("issuer").asText(), "the issuer defaults to the record's state");
        JsonNode other = map(List.of(new MappingRule("ration_no", "identifiers.RATION_CARD", "issuer:DEPT7", null, null)),
                Map.of("ration_no", RATION));
        JsonNode otherId = other.path("extensions").path(IdentifierProtection.EXTENSION).path("RATION_CARD");
        assertEquals("DEPT7", otherId.path("issuer").asText());
        assertNotEquals(id.path("value"), otherId.path("value"), "the same number from another issuer differs");
    }

    @Test
    void anInvalidChecksumOrUnknownTypeIsAMappingErrorThatNamesTheFieldOnly() {
        var bad = assertThrows(IllegalArgumentException.class,
                () -> map(List.of(rule("aadhaar_no", "identifiers.AADHAAR")), Map.of("aadhaar_no", "234567890123")));
        assertTrue(bad.getMessage().contains("aadhaar_no") && bad.getMessage().contains("VERHOEFF"), bad.getMessage());
        assertFalse(bad.getMessage().contains("234567890123"));
        var unknown = assertThrows(IllegalArgumentException.class,
                () -> map(List.of(rule("pp", "identifiers.PASSPORT")), Map.of("pp", "Z1234567")));
        assertTrue(unknown.getMessage().contains("pp") && unknown.getMessage().contains("PASSPORT"));
        assertFalse(unknown.getMessage().contains("Z1234567"));
    }

    @Test
    void noClearValueAnywhere() throws Exception {
        JsonNode resident = map(List.of(rule("aadhaar_no", "identifiers.AADHAAR"),
                rule("ration_no", "identifiers.RATION_CARD")), Map.of("aadhaar_no", AADHAAR, "ration_no", RATION));
        String text = JSON.writeValueAsString(resident);
        assertFalse(text.contains(AADHAAR), "clear Aadhaar");
        assertFalse(text.contains("445566"), "clear ration card");
        assertTrue(NationalIdLeakGuard.of(registry).leaksIn(resident).isEmpty());

        JsonNode leaky = map(List.of(rule("aadhaar_no", "identifiers.AADHAAR")),
                Map.of("aadhaar_no", AADHAAR, "note", "old card " + AADHAAR));
        assertEquals(List.of("extensions.note"), NationalIdLeakGuard.of(registry).leaksIn(leaky),
                "the registry's sensitive types drive the leak guard");
        var protection = new IdentifierProtection(registry, hashes);
        ProtectedIdentifier token = protection.protect("RATION_CARD", "ST9", RATION);
        assertEquals(token, protection.protect("RATION_CARD", "ST9", token.token()), "a redacted raw value replays");
        assertNull(protection.protect("AADHAAR", "ST9", AADHAAR).issuer());
    }

    @Test
    void flagKeepsTheRecordAndStoresOnlyTheInvalidStatus() throws Exception {
        String bad = "234567890123";
        var reject = assertThrows(IllegalArgumentException.class,
                () -> map(List.of(rule("aadhaar_no", "identifiers.AADHAAR")), Map.of("aadhaar_no", bad)));
        assertFalse(reject.getMessage().contains(bad));

        var flagged = new MappingRule("aadhaar_no", "identifiers.AADHAAR", null, null, null, "flag");
        JsonNode resident = map(List.of(flagged, rule("ration_no", "identifiers.RATION_CARD")),
                Map.of("aadhaar_no", bad, "ration_no", RATION));
        JsonNode ids = resident.path("extensions").path(IdentifierProtection.EXTENSION);
        assertEquals("INVALID", ids.path("AADHAAR").path("status").asText());
        assertEquals("AADHAAR", ids.path("AADHAAR").path("type").asText());
        assertTrue(ids.path("AADHAAR").path("reason").asText().contains("VERHOEFF"));
        assertTrue(ids.path("AADHAAR").path("value").isMissingNode(), "an invalid identifier has no value to match on");
        assertEquals("HOUSEHOLD", ids.path("RATION_CARD").path("subject").asText(), "valid identifiers are unaffected");
        assertEquals("Ram Singh", resident.path("name").asText(), "the record is kept");
        assertFalse(IdentityPolicyEvaluator.Subject.of("k", "ST9", "registry", resident).identifiers()
                .containsKey("AADHAAR"), "matching never sees an INVALID identifier");

        assertThrows(IllegalArgumentException.class,
                () -> new MappingRule("a", "identifiers.AADHAAR", null, null, null, "ignore"));
    }

    @Test
    void invalidValuesAreNeverStoredInClear() throws Exception {
        String bad = "234567890123";
        var flagged = new MappingRule("aadhaar_no", "identifiers.AADHAAR", null, null, null, "flag");
        JsonNode resident = map(List.of(flagged), Map.of("aadhaar_no", bad));
        assertFalse(JSON.writeValueAsString(resident).contains(bad));
        assertTrue(NationalIdLeakGuard.of(registry).leaksIn(resident).isEmpty());
        String redacted = IdentifierProtection.INVALID_TOKEN_PREFIX + "AADHAAR";
        assertTrue(IdentifierProtection.isToken(redacted));
        JsonNode replay = map(List.of(flagged), Map.of("aadhaar_no", redacted));
        assertEquals("INVALID", replay.path("extensions").path(IdentifierProtection.EXTENSION).path("AADHAAR")
                .path("status").asText(), "a redacted invalid value replays as INVALID");
    }

    @Test
    void lastFourComesFromMaskedAndFullSources() {
        var parser = new MaskedIdParser(registry);
        for (String masked : List.of("XXXXXXXX4821", "********4821", "XXXX XXXX 4821", "xxxxxxxx4821", "4821")) {
            assertEquals(Map.of("AADHAAR_LAST4", "4821"), parser.parse(masked, Map.of()).values(), masked);
        }
        var full = parser.parse("2345 6789 0124", Map.of());
        assertEquals("0124", full.values().get("AADHAAR_LAST4"), "a full valid Aadhaar also emits its last four");
        assertEquals(AADHAAR, full.values().get("AADHAAR"));
        var junk = parser.parse("XXXXXXXX48", Map.of());
        assertTrue(junk.values().isEmpty());
        assertEquals(0.0, junk.confidence());
        assertFalse(junk.warnings().toString().contains("XXXXXXXX48"));
        assertTrue(parser.parse("234567890123", Map.of()).values().isEmpty(), "a full number with a bad check digit");

        JsonNode fromMasked = stored("AADHAAR_LAST4", "4821");
        assertEquals("4821", fromMasked.path("value").asText());
        assertEquals("PLAIN", fromMasked.path("storage").asText());
        assertEquals("0124", stored("AADHAAR_LAST4", full.values().get("AADHAAR_LAST4").toString()).path("value").asText());
    }

    @Test
    void classifyPicksTheTypeWhoseValidationMatches() {
        var parser = new IdClassifyParser(registry);
        Map<String, Object> config = Map.of("types", List.of("AADHAAR", "RATION_CARD"));
        var aadhaar = parser.parse("2345 6789 0124", config);
        assertEquals("AADHAAR", aadhaar.values().get("type"));
        assertEquals("2345 6789 0124", aadhaar.values().get("AADHAAR"));
        var ration = parser.parse(RATION, config);
        assertEquals("RATION_CARD", ration.values().get("type"));
        var none = parser.parse("12", config);
        assertEquals(Map.of("type", "INVALID"), none.values(), "an unclassified value is not passed on");
        assertEquals("INVALID", parser.parse("234567890123!", config).values().get("type"));
    }

    @Test
    void anAliasIsAnOldSchemeIdOfTheSameSubject() throws Exception {
        String old = "345678901234";
        JsonNode resident = map(List.of(rule("aadhaar_no", "identifiers.AADHAAR"),
                rule("ration_no", "identifiers.RATION_CARD.alias"),
                new MappingRule("old_no", "identifiers.AADHAAR.alias", null, null, null, "flag")),
                Map.of("aadhaar_no", AADHAAR, "ration_no", RATION, "old_no", old));
        JsonNode extensions = resident.path("extensions");
        assertEquals("0124", extensions.path(IdentifierProtection.EXTENSION).path("AADHAAR").path("last4").asText());
        assertTrue(extensions.path(IdentifierProtection.EXTENSION).path("RATION_CARD").isMissingNode(),
                "an alias is not the current identifier");
        JsonNode aliases = extensions.path(ResidentMappingEngine.ALIAS_EXTENSION);
        assertEquals("HOUSEHOLD", aliases.path("RATION_CARD").get(0).path("subject").asText());
        assertEquals("INVALID", aliases.path("AADHAAR").get(0).path("status").asText());
        assertFalse(JSON.writeValueAsString(resident).contains(old));
        assertTrue(ResidentMappingEngine.isAlias("identifiers.AADHAAR.alias"));
        assertEquals("AADHAAR", ResidentMappingEngine.identifierType("nationalIds.AADHAAR.alias"));
        assertFalse(ResidentMappingEngine.isAlias("identifiers.AADHAAR"));
    }

    private JsonNode stored(String type, String value) {
        return JSON.valueToTree(new IdentifierProtection(registry, hashes).protect(type, "ST9", value).extensionEntry());
    }

    /** Maps one row with columns id, name and {@code columns} (in order). */
    private JsonNode map(List<MappingRule> identifierRules, Map<String, String> columns) throws Exception {
        var rules = new java.util.ArrayList<MappingRule>(List.of(rule("name", "name")));
        rules.addAll(identifierRules);
        var mapping = new MappingDefinition("1.0", "registry", Map.of("Resident", rules));
        var source = new SourceDefinition("registry", "file", "generic-file-ingest", null, "x.csv", "id", "1");
        var manifest = new StatePackManifest("st9", "ST9", "1.0.0", "1.0", "ACTIVE", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
        var pack = new LoadedStatePack(manifest, List.of(source), List.of(mapping), Map.of(), List.of(), List.of(),
                List.of());
        StringBuilder header = new StringBuilder("id,name");
        StringBuilder line = new StringBuilder("1,Ram Singh");
        columns.forEach((name, value) -> {
            header.append(',').append(name);
            line.append(",\"").append(value).append('"');
        });
        String payload = JSON.writeValueAsString(Map.of("csvHeader", header.toString(), "rawLine", line.toString()));
        var raw = new RawRecord(UUID.randomUUID(), UUID.randomUUID(), "ST9", "registry", OffsetDateTime.now(), payload,
                Map.of(), "1.0.0", "1", "generic-file-ingest");
        return JSON.valueToTree(engine.map(raw, pack, "registry"));
    }

    private static MappingRule rule(String field, String target) {
        return new MappingRule(field, target, null, null, null);
    }
}
