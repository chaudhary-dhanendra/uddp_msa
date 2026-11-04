package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.LayoutPatterns.assertMatches;
import static gov.uddp.shaping.ops.LayoutPatterns.config;
import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.uddp.shaping.api.RawRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeaderDetectOperationTest {

    private final HeaderDetectOperation operation = new HeaderDetectOperation();

    @Test
    void skipsBannerAndCleansAnnotatedHeaders() {
        List<RawRow> shaped = operation.apply(LayoutPatterns.read("header"), config("{\"maxScanRows\": 10}"));

        assertMatches("header", shaped);
    }

    @Test
    void joinsMergedGroupHeaderWithSubHeader() {
        List<RawRow> shaped = operation.apply(LayoutPatterns.read("tranches"), config("{\"multiRow\": \"auto\"}"));

        assertMatches("tranches", shaped);
    }

    @Test
    void multiRowCanBeTurnedOff() {
        List<RawRow> shaped = operation.apply(LayoutPatterns.read("tranches"), config("{\"multiRow\": false}"));

        assertEquals("Amount", shaped.getFirst().carried().get("@h2"));
    }

    @Test
    void headersAreDetectedPerSheet() {
        List<RawRow> shaped = operation.apply(LayoutPatterns.read("header", "aliases"), config("{}"));

        assertEquals("Name", shaped.getFirst().carried().get("@h0"));
        assertEquals("Beneficiary Name", shaped.getLast().carried().get("@h0"));
    }

    @Test
    void sheetWithoutHeaderIsUnchanged() {
        List<RawRow> rows = List.of(new RawRow("f", "s", 1, List.of("1", "2"), Map.of()));

        assertEquals(rows, operation.apply(rows, config("{}")));
    }
}
