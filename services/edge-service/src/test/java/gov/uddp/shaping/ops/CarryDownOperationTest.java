package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.LayoutPatterns.assertMatches;
import static gov.uddp.shaping.ops.LayoutPatterns.config;

import gov.uddp.shaping.api.RawRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CarryDownOperationTest {

    @Test
    void blankTakesValueAboveAndResetsPerSheet() {
        List<RawRow> shaped = new CarryDownOperation().apply(LayoutPatterns.read("carry", "carry2"),
                config("{\"columns\": [0]}"));

        assertMatches("carry-down", shaped);
    }

    @Test
    void skipsAConfiguredColumnOnSheetsWithAnotherLayoutAndResumesOnLaterSheets() {
        Map<String, String> target = Map.of("@h0", "COLUMN_A");
        Map<String, String> other = Map.of("@h0", "COLUMN_B");
        List<RawRow> shaped = new CarryDownOperation().apply(List.of(
                new RawRow("f", "one", 1, List.of("first"), target),
                new RawRow("f", "one", 2, List.of(""), target),
                new RawRow("f", "two", 1, List.of("unchanged"), other),
                new RawRow("f", "three", 1, List.of("last"), target),
                new RawRow("f", "three", 2, List.of(""), target)), config("{\"columns\": [\"COLUMN_A\"]}"));

        assertEquals(List.of("first", "first", "unchanged", "last", "last"),
                shaped.stream().map(row -> row.cells().getFirst()).toList());
    }
}
