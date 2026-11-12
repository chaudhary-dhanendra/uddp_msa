package gov.uddp.shaping.parsers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DateTextParserTest {
    private final DateTextParser parser = new DateTextParser();

    private String date(String value) {
        return (String) parser.parse(value, Map.of()).values().get("date");
    }

    @Test
    void everyWayOfWritingOneDateGivesTheSameDate() {
        for (String value : new String[] {"21-11-1979", "21/11/79", "21.11.1979", "21 Nov 1979", "21-nov-79",
                "November 21, 1979", "1979-11-21", "21st Nov 1979"}) {
            assertEquals("1979-11-21", date(value), value);
        }
    }

    @Test
    void ambiguousDayAndMonthAreReadDayFirstWithLowerConfidence() {
        var result = parser.parse("12-03-1958", Map.of());
        assertEquals("1958-03-12", result.values().get("date"));
        assertEquals(0.9, result.confidence());
        assertEquals("1958-12-03", parser.parse("12-03-1958", Map.of("dayFirst", false)).values().get("date"));
    }

    @Test
    void partialDatesKeepTheirPrecision() {
        assertEquals("1958-03", date("03/1958"));
        assertEquals("MONTH", parser.parse("03/1958", Map.of()).values().get("precision"));
        var year = parser.parse("1958", Map.of());
        assertEquals("1958", year.values().get("date"));
        assertEquals("YEAR", year.values().get("precision"));
        assertEquals(0.6, year.confidence());
    }

    @Test
    void notADateGivesNoValue() {
        for (String value : new String[] {"", "unknown", "31-02-1980", "13/1958"}) {
            var result = parser.parse(value, Map.of());
            assertTrue(result.values().isEmpty(), value);
            assertEquals(0.0, result.confidence(), value);
        }
    }
}
