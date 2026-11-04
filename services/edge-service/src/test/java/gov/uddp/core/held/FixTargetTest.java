package gov.uddp.core.held;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.MappingDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import gov.uddp.core.validation.ValidationIssue.Severity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Each reason code points at the pack part that fixes it; the pack names the exact column or crosswalk. */
class FixTargetTest {
    private static final LoadedStatePack PACK = new LoadedStatePack(null, List.of(),
            List.of(new MappingDefinition("1", "census", Map.of("Resident", List.of(
                    new MappingRule("SEX", "gender", null, "gender-codes", null, null),
                    new MappingRule("DOB", "dateOfBirth", "date:DD-MM-YYYY", null, null, null))))),
            Map.of(), List.of(), List.of(), List.of());

    @Test
    void everyCodeHasItsTarget() {
        assertEquals(new FixTarget("RULE", "dob-not-future"), FixTarget.of("RULE_FAILED:dob-not-future", "dateOfBirth"));
        assertEquals(new FixTarget("CROSSWALK", "gender-codes"),
                FixTarget.of("UNMAPPED_CODE", "gender", Map.of(), PACK, "census"));
        assertEquals(new FixTarget("MAPPING", "DOB"), FixTarget.of("UNREADABLE_DATE", "dateOfBirth", Map.of(), PACK,
                "census"));
        assertEquals(new FixTarget("MAPPING", "NAME_COL"), FixTarget.of("MISSING_NAME", "name",
                Map.of("sourceField", "NAME_COL"), PACK, "census"), "the raise point's column wins");
        assertEquals(new FixTarget("MAPPING", "identifiers.ID"), FixTarget.of("ID_FORMAT", "identifiers.ID"));
        assertEquals(new FixTarget("SHAPING", "name"), FixTarget.of("LOW_CONFIDENCE", "name"));
        assertEquals(new FixTarget("PACK_SOURCE", "census"), FixTarget.of("DUPLICATE_SOURCE_KEY", null, Map.of(), PACK,
                "census"));
    }

    @Test
    void aReasonStoredWithoutATargetGetsTheOneItsCodeImplies() {
        var reason = new HeldReason("RULE_FAILED:mobile-10-digits", "bad", "mobile", Severity.SEND_TO_STEWARD, null);
        assertEquals(new FixTarget("RULE", "mobile-10-digits"), reason.fix());
    }
}
