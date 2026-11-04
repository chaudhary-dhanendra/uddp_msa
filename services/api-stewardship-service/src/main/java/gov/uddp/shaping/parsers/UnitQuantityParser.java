package gov.uddp.shaping.parsers;

import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.ParseResult;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code unit-quantity@1.0} for a cell that holds a number followed by a unit, such as "1.5 Kanal" or "1 Unit".
 * Outputs {@code quantity} (a number) and {@code unit} (upper case). A bare number gives a quantity without a unit
 * at lower confidence; anything else gives no values and a warning that never holds the cell.
 */
@Component
public class UnitQuantityParser implements FieldParser {
    private static final Pattern CELL = Pattern.compile("^(\\d+(?:[.,]\\d+)?)\\s*(\\p{L}[\\p{L} ]*?)?\\s*$");

    @Override
    public String name() {
        return "unit-quantity";
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public ParseResult parse(String value, Map<String, Object> config) {
        var matcher = CELL.matcher(value == null ? "" : value.strip());
        if (!matcher.matches()) {
            return new ParseResult(Map.of(), 0.0, List.of("unit-quantity: the cell is not a number with a unit"));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("quantity", new BigDecimal(matcher.group(1).replace(',', '.')));
        if (matcher.group(2) == null) {
            return new ParseResult(values, 0.8, List.of("unit-quantity: the cell has no unit"));
        }
        values.put("unit", matcher.group(2).strip().toUpperCase(Locale.ROOT).replace(' ', '_'));
        return new ParseResult(values, 1.0, List.of());
    }
}
