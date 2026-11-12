package gov.uddp.core.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** When every row is rejected while mapping, MAPPED fails with the rows' own reasons instead of DQ_CHECKED failing. */
class OnboardingRejectedRowsMessageTest {

    @Test
    void namesTheCountsAndTheReasons() {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        reasons.put("Source record key field is blank: Name of Owner", 10);
        reasons.put("Duplicate source record key in batch: x", 1);
        assertEquals("No row reached the data-quality checks: 11 of 12 rows were rejected while mapping. "
                + "Source record key field is blank: Name of Owner (10 rows); "
                + "Duplicate source record key in batch: x (1 row)",
                OnboardingService.rejectedMessage(12, 11, reasons));
    }

    @Test
    void withoutReasonsStillGivesTheCounts() {
        assertEquals("No row reached the data-quality checks: 12 of 12 rows were rejected while mapping",
                OnboardingService.rejectedMessage(12, 12, Map.of()));
    }
}
