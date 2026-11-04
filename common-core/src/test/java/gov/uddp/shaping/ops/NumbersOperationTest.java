package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.LayoutPatterns.assertMatches;
import static gov.uddp.shaping.ops.LayoutPatterns.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gov.uddp.shaping.api.RawRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NumbersOperationTest {

    private final NumbersOperation operation = new NumbersOperation();

    @Test
    void indianAndWesternGroupingAreRemovedPerColumn() {
        List<RawRow> indian = operation.apply(LayoutPatterns.read("numbers"),
                config("{\"grouping\": \"indian\", \"columns\": [1]}"));
        List<RawRow> both = operation.apply(indian, config("{\"grouping\": \"western\", \"columns\": [2]}"));

        assertMatches("numbers", both);
    }

    @Test
    void lakhFigureBecomesPlainNumber() {
        RawRow row = new RawRow("f", "s", 1, List.of("25,23,809"), Map.of());

        assertEquals("2523809",
                operation.apply(List.of(row), config("{\"columns\": [0]}")).getFirst().cells().getFirst());
    }

    @Test
    void unknownGroupingIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> operation.apply(List.of(), config("{\"grouping\": \"roman\", \"columns\": [0]}")));
    }
}
