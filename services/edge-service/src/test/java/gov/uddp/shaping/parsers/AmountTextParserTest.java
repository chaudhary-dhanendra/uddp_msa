package gov.uddp.shaping.parsers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AmountTextParserTest {
    private final AmountTextParser parser = new AmountTextParser();

    private Object amount(String value) {
        return parser.parse(value, Map.of()).values().get("amount");
    }

    @Test
    void rupeeAmountsAsPeopleWriteThem() {
        assertEquals("125000", amount("Rs. 1,25,000"));
        assertEquals("75500", amount("₹75,500/-"));
        assertEquals("40000", amount("INR 40000"));
        assertEquals("12345.5", amount("12,345.50"));
        assertEquals("INR", parser.parse("Rs 500", Map.of()).values().get("currency"));
    }

    @Test
    void lakhAndCroreAreMultiplied() {
        assertEquals("150000", amount("1.5 lakh"));
        assertEquals("250000", amount("Rs 2.5 lakhs"));
        assertEquals("20000000", amount("2 crore"));
        assertEquals("15000000", amount("1.5 cr"));
    }

    @Test
    void textThatIsNotAnAmountGivesNoValue() {
        for (String value : new String[] {"", "nil", "about fifty thousand", "1.5 Kanal"}) {
            var result = parser.parse(value, Map.of());
            assertTrue(result.values().isEmpty(), value);
            assertEquals(0.0, result.confidence(), value);
        }
    }
}
