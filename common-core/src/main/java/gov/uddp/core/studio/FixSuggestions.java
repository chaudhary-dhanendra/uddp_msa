package gov.uddp.core.studio;

import gov.uddp.core.identity.IdentifierTypeRegistry;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.validation.ReasonCodes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A suggested fix for a preview issue, from the reason code and its raise-point context only: the nearest values of
 * the crosswalk for UNMAPPED_CODE, the date formats the value parses with for UNREADABLE_DATE, the identifier type's
 * expected pattern for ID_FORMAT and the other filled name columns for MISSING_NAME. Other codes have none. A
 * suggestion names crosswalk codes, formats, patterns and column names, never the row's values.
 */
@Component
public class FixSuggestions {
    private static final int NEAREST = 3;

    private IdentifierTypeRegistry identifiers;

    @Autowired(required = false)
    public void setIdentifiers(IdentifierTypeRegistry identifiers) {
        this.identifiers = identifiers;
    }

    public String suggest(String code, Map<String, String> context, LoadedStatePack pack, Map<String, String> row) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case ReasonCodes.UNMAPPED_CODE -> nearestCodes(context, pack);
            case ReasonCodes.UNREADABLE_DATE -> dateFormats(context.get("value"));
            case ReasonCodes.ID_FORMAT -> expectedPattern(context.get("identifierType"));
            case ReasonCodes.MISSING_NAME -> otherNameColumns(context.get("sourceField"), row);
            default -> null;
        };
    }

    private static String nearestCodes(Map<String, String> context, LoadedStatePack pack) {
        String crosswalk = context.get("crosswalk");
        String value = context.get("value");
        if (pack == null || crosswalk == null || value == null || pack.crosswalks().get(crosswalk) == null) {
            return null;
        }
        String wanted = fold(value);
        List<String> nearest = pack.crosswalks().get(crosswalk).values().keySet().stream()
                .filter(candidate -> distance(fold(candidate), wanted) <= Math.max(2, wanted.length() / 3)
                        || fold(candidate).contains(wanted) || wanted.contains(fold(candidate)))
                .sorted(Comparator.comparingInt(candidate -> distance(fold(candidate), wanted)))
                .limit(NEAREST)
                .toList();
        return nearest.isEmpty() ? "Add the value to crosswalk " + crosswalk
                : "Nearest values in crosswalk " + crosswalk + ": " + String.join(", ", nearest);
    }

    private static String dateFormats(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        List<String> formats = new ArrayList<>();
        for (String conversion : StudioMetadata.DATE_CONVERSIONS) {
            String pattern = conversion.substring("date:".length());
            try {
                java.time.LocalDate.parse(value.strip(), CsvProfiler.formatter(pattern));
                formats.add(pattern);
            } catch (java.time.format.DateTimeParseException | IllegalArgumentException e) {
                // not this format
            }
        }
        return formats.isEmpty() ? "No configured date format reads this value; check the column"
                : "The value reads with date format " + String.join(" or ", formats);
    }

    private String expectedPattern(String type) {
        if (identifiers == null || type == null) {
            return null;
        }
        return identifiers.find(type).map(t -> t.validation().pattern() == null
                ? "Check the value against the " + t.label() + " format"
                : "Expected " + t.label() + " format: " + t.validation().pattern()).orElse(null);
    }

    private static String otherNameColumns(String sourceField, Map<String, String> row) {
        if (row == null) {
            return null;
        }
        List<String> filled = row.entrySet().stream()
                .filter(e -> !e.getKey().equals(sourceField))
                .filter(e -> e.getKey().toLowerCase(Locale.ROOT).contains("name"))
                .filter(e -> e.getValue() != null && !e.getValue().isBlank())
                .map(Map.Entry::getKey).toList();
        return filled.isEmpty() ? null : "Other name columns with a value: " + String.join(", ", filled);
    }

    private static String fold(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
