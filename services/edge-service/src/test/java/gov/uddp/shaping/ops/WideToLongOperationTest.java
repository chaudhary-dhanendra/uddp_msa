package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.LayoutPatterns.assertMatches;
import static gov.uddp.shaping.ops.LayoutPatterns.config;

import gov.uddp.shaping.api.RawRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class WideToLongOperationTest {

    @Test
    void oneRowPerTrancheAndEmptyTranchesAreNotEmitted() {
        List<RawRow> withHeaders = new HeaderDetectOperation().apply(LayoutPatterns.read("tranches"), config("{}"));

        List<RawRow> longRows = new WideToLongOperation().apply(withHeaders,
                config("{\"pattern\": \"Tranche {n} *\", \"n\": [1, 2], \"into\": \"TRANCHE\"}"));

        assertMatches("wide-to-long", longRows);
    }
}
