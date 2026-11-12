package gov.uddp.shaping.parsers;

import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.ParseResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code amount-text@1.0} for a money column written as people write it: "Rs. 1,25,000", "₹75,500/-", "INR 40000",
 * "1.5 lakh", "2 crore", "Rs 2.5 lakhs" or "12,345.50". Outputs {@code amount} (a plain decimal, for example
 * "150000") and {@code currency} ("INR"). Lakh is 1,00,000 and crore 1,00,00,000; digit grouping (Indian or Western)
 * is ignored. Confidence 1.0 when the cell is an amount, 0 when it is not.
 */
@Component
public class AmountTextParser implements FieldParser {
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:rs\\.?|inr|₹)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(lakhs?|lacs?|crores?|cr)?\\s*(?:/-)?");
    private static final BigDecimal LAKH = new BigDecimal("100000");
    private static final BigDecimal CRORE = new BigDecimal("10000000");

    @Override
    public String name() {
        return "amount-text";
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public ParseResult parse(String value, Map<String, Object> config) {
        String text = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        Matcher m = AMOUNT.matcher(text);
        if (text.isEmpty() || !m.matches()) {
            return new ParseResult(Map.of(), 0.0, List.of("amount-text: not an amount"));
        }
        BigDecimal amount = new BigDecimal(m.group(1).replace(",", ""));
        String unit = m.group(2);
        if (unit != null) {
            amount = amount.multiply(unit.startsWith("l") ? LAKH : CRORE);
        }
        String plain = amount.stripTrailingZeros().toPlainString();
        return new ParseResult(Map.of("amount", plain, "currency", "INR"), 1.0, List.of());
    }
}
