package gov.uddp.shaping.parsers;

import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.ParseResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code mobile-number@1.0} for a cell that holds one or more mobile numbers as people type them: "+91 94190 00001",
 * "094190-00001", "(94190) 00001" or "9419000001 / 9419000002". Spaces, dashes, dots and brackets are removed, and a
 * leading country code 91 or trunk 0 is dropped. Outputs {@code mobile} (the first number, 10 digits) and
 * {@code otherMobiles} (the rest, comma-separated). Confidence 1.0 when the first number is 10 digits starting 6-9,
 * 0.5 for 10 digits starting otherwise, 0 when no 10-digit number is found. Warnings never hold the cell.
 */
@Component
public class MobileNumberParser implements FieldParser {
    private static final Pattern SEPARATORS = Pattern.compile("\\s*[/,;|]\\s*|\\s+or\\s+");
    private static final Pattern NOISE = Pattern.compile("[\\s().\\-]");

    @Override
    public String name() {
        return "mobile-number";
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public ParseResult parse(String value, Map<String, Object> config) {
        List<String> numbers = new ArrayList<>();
        for (String part : SEPARATORS.split(value == null ? "" : value.strip())) {
            String digits = NOISE.matcher(part).replaceAll("");
            if (digits.startsWith("+")) {
                digits = digits.substring(1);
            }
            if (digits.length() == 12 && digits.startsWith("91")) {
                digits = digits.substring(2);
            } else if (digits.length() == 11 && digits.startsWith("0")) {
                digits = digits.substring(1);
            }
            if (digits.matches("\\d{10}")) {
                numbers.add(digits);
            }
        }
        if (numbers.isEmpty()) {
            return new ParseResult(Map.of(), 0.0, List.of("mobile-number: no 10-digit mobile number in the cell"));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("mobile", numbers.getFirst());
        if (numbers.size() > 1) {
            values.put("otherMobiles", String.join(",", numbers.subList(1, numbers.size())));
        }
        if (!numbers.getFirst().matches("[6-9]\\d{9}")) {
            return new ParseResult(values, 0.5, List.of("mobile-number: the number does not start with 6, 7, 8 or 9"));
        }
        return new ParseResult(values, 1.0, List.of());
    }
}
