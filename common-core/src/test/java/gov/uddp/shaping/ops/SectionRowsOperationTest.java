package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.LayoutPatterns.assertMatches;
import static gov.uddp.shaping.ops.LayoutPatterns.config;

import gov.uddp.shaping.api.RawRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class SectionRowsOperationTest {

    @Test
    void sectionRowBecomesCarriedValueNotARecord() {
        List<RawRow> shaped = new SectionRowsOperation().apply(LayoutPatterns.read("section"),
                config("{\"onlyFilled\": [0], \"carryAs\": \"DISTRICT\"}"));

        assertMatches("section", shaped);
    }
}
