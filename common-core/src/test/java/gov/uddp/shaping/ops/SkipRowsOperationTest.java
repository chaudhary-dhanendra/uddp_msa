package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.LayoutPatterns.assertMatches;
import static gov.uddp.shaping.ops.LayoutPatterns.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gov.uddp.shaping.api.RawRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkipRowsOperationTest {

    private final SkipRowsOperation operation = new SkipRowsOperation();

    @Test
    void dropsBannerTotalPageFooterAndBlankRows() {
        List<RawRow> kept = operation.apply(LayoutPatterns.read("skip"), config(
                "{\"rules\": [{\"firstColumn\": \"^Total\"}, {\"anyColumn\": \"Page \\\\d+ of\"},"
                        + " {\"allBlank\": true}, {\"onlyFilled\": [0]}]}"));

        assertMatches("skip", kept);
    }

    @Test
    void allBlankFalseKeepsBlankRows() {
        List<RawRow> kept = operation.apply(LayoutPatterns.read("skip"), config("{\"rules\": [{\"allBlank\": false}]}"));

        assertEquals(7, kept.size());
    }

    @Test
    void onlyFilledRuleDoesNotMatchOrFailWhenItsColumnBelongsToAnotherSheetLayout() {
        RawRow row = new RawRow("f", "other", 1, List.of("value"), Map.of("@h0", "OTHER_COLUMN"));

        assertEquals(List.of(row), operation.apply(List.of(row),
                config("{\"rules\": [{\"onlyFilled\": [\"COLUMN_A\"]}]}")));
    }

    @Test
    void unknownRuleIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> operation.apply(LayoutPatterns.read("skip"), config("{\"rules\": [{\"bogus\": 1}]}")));
    }
}
