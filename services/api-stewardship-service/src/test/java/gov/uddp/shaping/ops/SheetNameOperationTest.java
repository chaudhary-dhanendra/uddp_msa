package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.LayoutPatterns.assertMatches;
import static gov.uddp.shaping.ops.LayoutPatterns.config;

import gov.uddp.shaping.api.RawRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class SheetNameOperationTest {

    @Test
    void sheetNameIsCarriedUnderTheConfiguredColumn() {
        List<RawRow> shaped = new SheetNameOperation().apply(LayoutPatterns.read("carry2"),
                config("{\"as\": \"SHEET\"}"));

        assertMatches("sheet-name", shaped);
    }
}
