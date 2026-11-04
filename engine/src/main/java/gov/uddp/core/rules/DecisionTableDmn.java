package gov.uddp.core.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Rules Studio decision table ({@code {"rows":[{"condition","output","clauseReference"}]}}) as DMN. Each distinct
 * FEEL condition becomes one boolean input; a row matches when its own condition is true. Outputs are
 * {@code outcome} (the row's output) and the required {@code clauseReference}; the hit policy is COLLECT.
 */
public final class DecisionTableDmn {
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern IDENTIFIER = Pattern.compile("(?<![\\w.])([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)");
    private static final Set<String> FEEL_WORDS = Set.of("and", "or", "not", "true", "false", "null", "in", "between",
            "if", "then", "else", "some", "every", "satisfies", "instance", "of", "date", "time", "duration",
            "string", "number", "today", "now", "years", "months", "contains", "starts", "with", "ends", "upper",
            "case", "lower", "length", "abs");

    private DecisionTableDmn() {
    }

    public record DecisionRow(String condition, String output, String clauseReference) {
    }

    public record DecisionTable(List<DecisionRow> rows) {
        public DecisionTable {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /** Rejects an empty table or a row without condition, output or clause reference. */
    public static void validate(DecisionTable table) {
        if (table == null || table.rows().isEmpty()) {
            throw new IllegalArgumentException("The decision table has no rows");
        }
        for (int i = 0; i < table.rows().size(); i++) {
            DecisionRow row = table.rows().get(i);
            if (row == null || blank(row.condition()) || blank(row.output()) || blank(row.clauseReference())) {
                throw new IllegalArgumentException("Row " + (i + 1) + " needs condition, output and clauseReference");
            }
        }
    }

    /** The record attributes the conditions read, in name order (string literals and FEEL words excluded). */
    public static List<String> attributes(DecisionTable table) {
        Set<String> names = new TreeSet<>();
        for (DecisionRow row : table.rows()) {
            String text = STRING_LITERAL.matcher(row.condition()).replaceAll(" ");
            Matcher m = IDENTIFIER.matcher(text);
            while (m.find()) {
                String name = m.group(1);
                if (!FEEL_WORDS.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    names.add(name);
                }
            }
        }
        return List.copyOf(names);
    }

    /** The parts of a condition joined by {@code and} at the top level (outside brackets and strings). */
    public static List<String> conjuncts(String condition) {
        List<String> parts = new ArrayList<>();
        String text = condition == null ? "" : condition.strip();
        int depth = 0;
        int start = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (!quoted && (c == '(' || c == '[')) {
                depth++;
            } else if (!quoted && (c == ')' || c == ']')) {
                depth--;
            } else if (!quoted && depth == 0 && text.startsWith(" and ", i)) {
                parts.add(text.substring(start, i).strip());
                start = i + 5;
                i += 4;
            }
        }
        parts.add(text.substring(start).strip());
        return parts.stream().filter(p -> !p.isEmpty()).toList();
    }

    public static String toDmn(DecisionTable table, String name) {
        validate(table);
        Map<String, String> inputs = new LinkedHashMap<>();
        for (DecisionRow row : table.rows()) {
            inputs.computeIfAbsent(row.condition().strip(), c -> "in" + (inputs.size() + 1));
        }
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<definitions xmlns=\"https://www.omg.org/spec/DMN/20191111/MODEL/\" id=\"definitions\" name=\"")
                .append(escape(name)).append("\" namespace=\"https://uddp.gov/rules\">\n")
                .append("  <decision id=\"decision\" name=\"").append(escape(name)).append("\">\n")
                .append("    <decisionTable id=\"table\" hitPolicy=\"COLLECT\">\n");
        for (var input : inputs.entrySet()) {
            xml.append("      <input id=\"").append(input.getValue()).append("\"><inputExpression id=\"")
                    .append(input.getValue()).append("-expression\" typeRef=\"boolean\"><text>")
                    .append(escape(input.getKey())).append("</text></inputExpression></input>\n");
        }
        xml.append("      <output id=\"outcome\" name=\"outcome\" typeRef=\"string\" />\n")
                .append("      <output id=\"clause\" name=\"clauseReference\" typeRef=\"string\" />\n");
        List<DecisionRow> rows = new ArrayList<>(table.rows());
        for (int r = 0; r < rows.size(); r++) {
            DecisionRow row = rows.get(r);
            String own = inputs.get(row.condition().strip());
            xml.append("      <rule id=\"rule").append(r + 1).append("\">");
            for (String inputId : inputs.values()) {
                xml.append("<inputEntry id=\"rule").append(r + 1).append('-').append(inputId).append("\"><text>")
                        .append(inputId.equals(own) ? "true" : "-").append("</text></inputEntry>");
            }
            xml.append("<outputEntry id=\"rule").append(r + 1).append("-outcome\"><text>")
                    .append(escape(feelString(row.output()))).append("</text></outputEntry>")
                    .append("<outputEntry id=\"rule").append(r + 1).append("-clause\"><text>")
                    .append(escape(feelString(row.clauseReference()))).append("</text></outputEntry></rule>\n");
        }
        xml.append("    </decisionTable>\n  </decision>\n</definitions>\n");
        return xml.toString();
    }

    private static String feelString(String value) {
        return "\"" + value.strip().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
