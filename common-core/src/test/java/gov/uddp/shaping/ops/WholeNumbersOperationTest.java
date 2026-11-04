package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.LayoutPatterns.config;
import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.uddp.shaping.api.RawRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WholeNumbersOperationTest {

    private final WholeNumbersOperation operation = new WholeNumbersOperation();

    @Test
    void integralDecimalsLoseTheirZeroFractionOnlyInTheNamedColumns() {
        RawRow header = new RawRow("f", "s", 1, List.of("A", "B", "C", "D"), Map.of("@h0", "A", "@h1", "B", "@h2", "C", "@h3", "D"));
        RawRow row = new RawRow("f", "s", 2, List.of("67.0", "-3.00", "1.5", "2.0"), header.carried());

        List<String> cells = operation.apply(List.of(row), config("{\"columns\": [\"A\", \"B\", \"C\"]}")).getFirst().cells();

        assertEquals(List.of("67", "-3", "1.5", "2.0"), cells);
    }
}
