package gov.uddp.core.studio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A record ID or record key chosen before a header was renamed is no longer a column: saving it must fail, because
 * the key would be blank on every row and onboarding would reject every row.
 */
class StudioRecordKeyColumnsTest {
    private static final List<String> COLUMNS = List.of("OWNER_NAME", "DOB", "VILLAGE_LGD");

    @Test
    void currentColumnsAreAccepted() {
        assertDoesNotThrow(() -> StudioService.requireRecordKeyColumns(COLUMNS, "OWNER_NAME",
                List.of("OWNER_NAME", "DOB", "VILLAGE_LGD")));
        assertDoesNotThrow(() -> StudioService.requireRecordKeyColumns(COLUMNS, null, null));
    }

    @Test
    void staleRecordIdIsRejected() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> StudioService.requireRecordKeyColumns(COLUMNS, "Name of Owner", List.of()));
        assertEquals("Unknown record ID column: Name of Owner", error.getMessage());
    }

    @Test
    void staleRecordKeyIsRejected() {
        var error = assertThrows(IllegalArgumentException.class, () -> StudioService.requireRecordKeyColumns(COLUMNS,
                "OWNER_NAME", List.of("Name of Owner", "OWNER_NAME", "DOB")));
        assertEquals("Unknown record key column: Name of Owner", error.getMessage());
    }

    @Test
    void blankRecordKeyIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> StudioService.requireRecordKeyColumns(COLUMNS, "OWNER_NAME", Arrays.asList("DOB", null)));
    }
}
