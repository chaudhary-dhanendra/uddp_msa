package gov.uddp.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.identity.IdentifierType;
import gov.uddp.core.identity.IdentifierTypeRegistry;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.CrosswalkDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Suggested fixes per reason code; they name crosswalk codes, formats, patterns and columns, never row values. */
class FixSuggestionsTest {
    private static final LoadedStatePack PACK = new LoadedStatePack(null, List.of(), List.of(),
            Map.of("district", new CrosswalkDefinition("district", Map.of("Ngairang", "D01", "Senkhai", "D02",
                    "Lamkhai", "D03"))), List.of(), List.of(), List.of());

    private final FixSuggestions suggestions = new FixSuggestions();

    @Test
    void anUnmappedCodeSuggestsTheNearestCrosswalkValues() {
        String text = suggestions.suggest("UNMAPPED_CODE", Map.of("crosswalk", "district", "value", "Ngairng"), PACK,
                Map.of());
        assertEquals("Nearest values in crosswalk district: Ngairang", text);
        assertEquals("Add the value to crosswalk district", suggestions.suggest("UNMAPPED_CODE",
                Map.of("crosswalk", "district", "value", "Elsewhere"), PACK, Map.of()));
    }

    @Test
    void anUnreadableDateSuggestsTheFormatsItReadsWith() {
        String text = suggestions.suggest("UNREADABLE_DATE", Map.of("value", "1980-03-12"), PACK, Map.of());
        assertTrue(text.contains("YYYY-MM-DD"), text);
        assertFalse(text.contains("1980"), "the date itself is not repeated");
    }

    @Test
    void anIdFormatSuggestsTheExpectedPattern() {
        var registry = IdentifierTypeRegistry.of(List.of(new IdentifierType("CARD_NO", "Card number",
                IdentifierType.Subject.values()[0], IdentifierType.Scope.values()[0], IdentifierType.Trust.values()[0],
                new IdentifierType.Validation("[0-9]{8}", null, null, null), null, IdentifierType.Storage.values()[0],
                null, null, null, false, null, null)), IdentifierTypeRegistry.defaultValidators());
        suggestions.setIdentifiers(registry);
        assertEquals("Expected Card number format: [0-9]{8}",
                suggestions.suggest("ID_FORMAT", Map.of("identifierType", "CARD_NO"), PACK, Map.of()));
    }

    @Test
    void aMissingNameSuggestsTheOtherFilledNameColumns() {
        String text = suggestions.suggest("MISSING_NAME", Map.of("sourceField", "NAME"), PACK,
                Map.of("NAME", "", "FATHER_NAME", "Tomba Singh", "NICKNAME", " ", "VILLAGE", "Lamkhai"));
        assertEquals("Other name columns with a value: FATHER_NAME", text);
        assertFalse(text.contains("Tomba"));
    }

    @Test
    void otherCodesHaveNoSuggestion() {
        assertNull(suggestions.suggest("RULE_FAILED:x", Map.of(), PACK, Map.of()));
        assertNull(suggestions.suggest("DUPLICATE_SOURCE_KEY", Map.of(), PACK, Map.of()));
    }
}
