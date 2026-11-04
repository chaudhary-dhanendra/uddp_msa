package gov.uddp.core.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rules Studio conditions built from a field, an operator and a value, compiled to FEEL. The operators a field offers
 * come from its data type alone (number, date, code, boolean, text), which platform metadata gives; nothing here names
 * a state, a scheme or a source.
 */
public final class RuleConditions {
    private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

    private RuleConditions() {
    }

    public enum DataType { NUMBER, DATE, CODE, BOOLEAN, TEXT }

    /** How many values an operator takes: none, one, two (a range) or a list. */
    public enum Arity { NONE, ONE, TWO, MANY }

    /** An operator as the Studio offers it for one data type. */
    public record Operator(String code, String label, Arity arity) {
    }

    /** One condition as the Studio sends it; {@code values} holds the list of an {@code in}/{@code not_in}. */
    public record Condition(String field, String operator, String value, String valueTo, List<String> values) {
    }

    /** What the compiler needs to know about a field: its name, label, data type and allowed codes (if listed). */
    public record FieldSpec(String name, String label, DataType dataType, List<String> codes) {
    }

    private static final Operator EQ = new Operator("eq", "is", Arity.ONE);
    private static final Operator NEQ = new Operator("neq", "is not", Arity.ONE);
    private static final Operator PRESENT = new Operator("present", "is provided", Arity.NONE);
    private static final Operator MISSING = new Operator("missing", "is missing", Arity.NONE);

    /** The data type of a metadata field from its {@code type}, {@code valueKind} and {@code codeSet}. */
    public static DataType dataType(String type, String valueKind, String codeSet) {
        String t = lower(type);
        String kind = lower(valueKind);
        if ("number".equals(t) || "number".equals(kind)) {
            return DataType.NUMBER;
        }
        if ("date".equals(t) || "date".equals(kind)) {
            return DataType.DATE;
        }
        if ("boolean".equals(t)) {
            return DataType.BOOLEAN;
        }
        if ("code".equals(t) || (codeSet != null && !codeSet.isBlank())) {
            return DataType.CODE;
        }
        return DataType.TEXT;
    }

    /** The operators a data type offers, in the order the Studio lists them. */
    public static List<Operator> operators(DataType type) {
        return switch (type) {
            case NUMBER -> List.of(new Operator("eq", "equals", Arity.ONE), new Operator("neq", "does not equal", Arity.ONE),
                    new Operator("gt", "is greater than", Arity.ONE), new Operator("gte", "is at least", Arity.ONE),
                    new Operator("lt", "is less than", Arity.ONE), new Operator("lte", "is at most", Arity.ONE),
                    new Operator("between", "is between", Arity.TWO), PRESENT, MISSING);
            case DATE -> List.of(new Operator("eq", "is on", Arity.ONE), new Operator("neq", "is not on", Arity.ONE),
                    new Operator("lt", "is before", Arity.ONE), new Operator("lte", "is on or before", Arity.ONE),
                    new Operator("gt", "is after", Arity.ONE), new Operator("gte", "is on or after", Arity.ONE),
                    new Operator("between", "is between", Arity.TWO), PRESENT, MISSING);
            case CODE -> List.of(EQ, NEQ, new Operator("in", "is one of", Arity.MANY),
                    new Operator("not_in", "is not one of", Arity.MANY), PRESENT, MISSING);
            case BOOLEAN -> List.of(new Operator("is_true", "is yes", Arity.NONE),
                    new Operator("is_false", "is no", Arity.NONE), PRESENT, MISSING);
            case TEXT -> List.of(EQ, NEQ, new Operator("contains", "contains", Arity.ONE),
                    new Operator("starts_with", "starts with", Arity.ONE), PRESENT, MISSING);
        };
    }

    /** All conditions joined with {@code and}; each is validated against its field. */
    public static String compileAll(List<Condition> conditions, java.util.function.Function<String, FieldSpec> fields) {
        if (conditions == null || conditions.isEmpty()) {
            throw new IllegalArgumentException("Add at least one condition");
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < conditions.size(); i++) {
            Condition c = conditions.get(i);
            String prefix = "Condition " + (i + 1) + ": ";
            if (c == null || c.field() == null || c.field().isBlank()) {
                throw new IllegalArgumentException(prefix + "choose a field");
            }
            FieldSpec field = fields.apply(c.field().strip());
            if (field == null) {
                throw new IllegalArgumentException(prefix + "unknown field " + c.field());
            }
            try {
                parts.add(compile(c, field));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(prefix + e.getMessage(), e);
            }
        }
        return String.join(" and ", parts);
    }

    /** One condition as a FEEL boolean expression. */
    public static String compile(Condition c, FieldSpec field) {
        if (!FIELD.matcher(field.name()).matches()) {
            throw new IllegalArgumentException("field " + field.name() + " cannot be used in a rule");
        }
        String op = c.operator() == null ? "" : c.operator().strip();
        Operator operator = operators(field.dataType()).stream().filter(o -> o.code().equals(op)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("'" + op + "' is not an operator for "
                        + field.label() + " (" + lower(field.dataType().name()) + ")"));
        String f = field.name();
        switch (operator.code()) {
            case "present":
                return f + " != null";
            case "missing":
                return f + " = null";
            case "is_true":
                return f + " = true";
            case "is_false":
                return f + " = false";
            default:
                break;
        }
        return switch (operator.arity()) {
            case ONE -> binary(f, operator.code(), literal(field, c.value()), field.dataType());
            case TWO -> {
                String from = literal(field, c.value());
                String to = literal(field, c.valueTo());
                if (compareLiterals(field.dataType(), c.value(), c.valueTo()) > 0) {
                    throw new IllegalArgumentException("the first value must not be after the second");
                }
                String subject = subject(f, field.dataType());
                yield "(" + subject + " >= " + from + " and " + subject + " <= " + to + ")";
            }
            case MANY -> {
                List<String> values = c.values() == null ? List.of() : c.values().stream()
                        .filter(v -> v != null && !v.isBlank()).toList();
                if (values.isEmpty()) {
                    throw new IllegalArgumentException("choose at least one value");
                }
                String list = "[" + String.join(", ", values.stream().map(v -> literal(field, v)).toList()) + "]";
                yield "in".equals(operator.code()) ? f + " in " + list : "not(" + f + " in " + list + ")";
            }
            case NONE -> throw new IllegalStateException();
        };
    }

    private static String binary(String f, String code, String literal, DataType type) {
        String subject = subject(f, type);
        return switch (code) {
            case "eq" -> subject + " = " + literal;
            case "neq" -> subject + " != " + literal;
            case "gt" -> subject + " > " + literal;
            case "gte" -> subject + " >= " + literal;
            case "lt" -> subject + " < " + literal;
            case "lte" -> subject + " <= " + literal;
            case "contains" -> "contains(" + f + ", " + literal + ")";
            case "starts_with" -> "starts with(" + f + ", " + literal + ")";
            default -> throw new IllegalArgumentException("unsupported operator " + code);
        };
    }

    /** Dates arrive in records as ISO strings, so the attribute is read with date() before comparing. */
    private static String subject(String f, DataType type) {
        return type == DataType.DATE ? "date(" + f + ")" : f;
    }

    private static String literal(FieldSpec field, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("enter a value for " + field.label());
        }
        String value = raw.strip();
        return switch (field.dataType()) {
            case NUMBER -> {
                try {
                    yield new BigDecimal(value).toPlainString();
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(field.label() + " needs a number, not '" + value + "'");
                }
            }
            case DATE -> {
                try {
                    yield "date(\"" + LocalDate.parse(value) + "\")";
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException(field.label() + " needs a date as YYYY-MM-DD, not '" + value + "'");
                }
            }
            case CODE -> {
                if (field.codes() != null && !field.codes().isEmpty() && !field.codes().contains(value)) {
                    throw new IllegalArgumentException("'" + value + "' is not a value of " + field.label());
                }
                yield string(value);
            }
            case BOOLEAN -> {
                if (!Set.of("true", "false").contains(value.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException(field.label() + " needs yes or no");
                }
                yield value.toLowerCase(Locale.ROOT);
            }
            case TEXT -> string(value);
        };
    }

    private static int compareLiterals(DataType type, String a, String b) {
        if (type == DataType.NUMBER) {
            return new BigDecimal(a.strip()).compareTo(new BigDecimal(b.strip()));
        }
        if (type == DataType.DATE) {
            return LocalDate.parse(a.strip()).compareTo(LocalDate.parse(b.strip()));
        }
        return 0;
    }

    private static String string(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String lower(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
