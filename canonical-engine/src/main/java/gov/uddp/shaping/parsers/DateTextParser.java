package gov.uddp.shaping.parsers;

import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.ParseResult;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code date-text@1.0} for a date column typed in several ways in one file: "12-03-1958", "12/3/58", "12.03.1958",
 * "12 Mar 1958", "March 12, 1958", "1958-03-12", "03/1958" or just "1958". Outputs {@code date} (ISO: yyyy-MM-dd,
 * yyyy-MM or yyyy) and {@code precision} (DAY, MONTH or YEAR). Day comes first unless the step sets
 * {@code dayFirst: false}; a two-digit year is in this century when it is not after this year, else the last one.
 * Confidence 1.0 for an unambiguous full date, 0.9 when day and month could be swapped (both 12 or less), 0.7 for a
 * month and year, 0.6 for a year alone, 0 when the cell is not a date.
 */
@Component
public class DateTextParser implements FieldParser {
    private static final Pattern NUMERIC = Pattern.compile("(\\d{1,4})[-/. ](\\d{1,2})[-/. ](\\d{1,4})");
    private static final Pattern MONTH_YEAR = Pattern.compile("(\\d{1,2})[-/. ](\\d{4})");
    private static final Pattern YEAR = Pattern.compile("(\\d{4})");
    private static final Pattern DAY_NAME_YEAR = Pattern.compile("(\\d{1,2})(?:st|nd|rd|th)?[- ]([a-z]+)\\.?,?[- ](\\d{2,4})");
    private static final Pattern NAME_DAY_YEAR = Pattern.compile("([a-z]+)\\.? (\\d{1,2})(?:st|nd|rd|th)?,? (\\d{4})");
    private static final List<String> MONTHS = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep",
            "oct", "nov", "dec");

    @Override
    public String name() {
        return "date-text";
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public ParseResult parse(String value, Map<String, Object> config) {
        String text = value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        boolean dayFirst = !Boolean.FALSE.equals(config == null ? null : config.get("dayFirst"));
        try {
            Matcher m;
            if ((m = NUMERIC.matcher(text)).matches()) {
                int a = Integer.parseInt(m.group(1));
                int b = Integer.parseInt(m.group(2));
                int c = Integer.parseInt(m.group(3));
                if (m.group(1).length() == 4) {
                    return day(LocalDate.of(a, b, c), 1.0);
                }
                int day = dayFirst ? a : b;
                int month = dayFirst ? b : a;
                boolean ambiguous = a <= 12 && b <= 12 && a != b;
                return day(LocalDate.of(year(c, m.group(3).length()), month, day), ambiguous ? 0.9 : 1.0);
            }
            if ((m = DAY_NAME_YEAR.matcher(text)).matches()) {
                return day(LocalDate.of(year(Integer.parseInt(m.group(3)), m.group(3).length()), month(m.group(2)),
                        Integer.parseInt(m.group(1))), 1.0);
            }
            if ((m = NAME_DAY_YEAR.matcher(text)).matches()) {
                return day(LocalDate.of(Integer.parseInt(m.group(3)), month(m.group(1)), Integer.parseInt(m.group(2))),
                        1.0);
            }
            if ((m = MONTH_YEAR.matcher(text)).matches()) {
                int month = Integer.parseInt(m.group(1));
                if (month < 1 || month > 12) {
                    return none("the month is not 1 to 12");
                }
                return new ParseResult(Map.of("date", String.format("%s-%02d", m.group(2), month), "precision", "MONTH"),
                        0.7, List.of());
            }
            if ((m = YEAR.matcher(text)).matches()) {
                return new ParseResult(Map.of("date", m.group(1), "precision", "YEAR"), 0.6,
                        List.of("date-text: only the year is known"));
            }
        } catch (DateTimeException | IllegalArgumentException notADate) {
            return none("not a calendar date");
        }
        return none("not a date");
    }

    private static ParseResult day(LocalDate date, double confidence) {
        List<String> warnings = confidence < 1.0 ? List.of("date-text: day and month could be swapped") : List.of();
        return new ParseResult(Map.of("date", date.toString(), "precision", "DAY"), confidence, warnings);
    }

    private static ParseResult none(String why) {
        return new ParseResult(Map.of(), 0.0, List.of("date-text: " + why));
    }

    /** A two-digit year is in this century unless that puts it in the future. */
    private static int year(int year, int digits) {
        if (digits > 2) {
            return year;
        }
        int now = Year.now().getValue();
        int candidate = now - now % 100 + year;
        return candidate > now ? candidate - 100 : candidate;
    }

    private static int month(String name) {
        int index = MONTHS.indexOf(name.length() >= 3 ? name.substring(0, 3) : name);
        if (index < 0) {
            throw new IllegalArgumentException("Unknown month: " + name);
        }
        return index + 1;
    }
}
