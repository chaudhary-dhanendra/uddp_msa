package gov.uddp.core.statepack;

import gov.uddp.core.statepack.StatePackAssets.StateField;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/** Converts a mapped value to its declared state field type; a value that does not fit fails the record. */
public final class StateFieldValues {
    private StateFieldValues() {
    }

    public static Object convert(StateField field, Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        try {
            return switch (field.typeOrDefault()) {
                case "number" -> new BigDecimal(text);
                case "integer" -> new BigDecimal(text).toBigIntegerExact();
                case "boolean" -> bool(text);
                case "date" -> value instanceof LocalDate date ? date.toString() : LocalDate.parse(text).toString();
                default -> value instanceof String ? text : value;
            };
        } catch (NumberFormatException | ArithmeticException | DateTimeParseException e) {
            throw new IllegalArgumentException("State field " + field.key() + " expects " + field.typeOrDefault()
                    + " but got '" + text + "'", e);
        }
    }

    private static Boolean bool(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "1" -> Boolean.TRUE;
            case "false", "no", "n", "0" -> Boolean.FALSE;
            default -> throw new NumberFormatException("not a boolean");
        };
    }
}
