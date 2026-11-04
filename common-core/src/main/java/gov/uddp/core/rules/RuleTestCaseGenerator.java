package gov.uddp.core.rules;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.camunda.bpm.model.dmn.Dmn;
import org.camunda.bpm.model.dmn.instance.Input;
import org.camunda.bpm.model.dmn.instance.InputEntry;
import org.camunda.bpm.model.dmn.instance.Rule;

/**
 * Track A5 (3): test cases generated from a rule set's own conditions, for any department or scheme. Every comparison
 * of an attribute with a literal yields boundary cases (just below, at and just above a number; the value and another
 * value for a text), and every attribute yields a missing-data case. Each case is evaluated so a reviewer sees which
 * clauses fire; nothing is stored.
 */
public final class RuleTestCaseGenerator {
    static final int MAX_CASES = 60;
    private static final String LITERAL = "(-?\\d+(?:\\.\\d+)?|\"[^\"]*\"|true|false)";
    private static final String PATH = "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*";
    private static final Pattern IDENTIFIER = Pattern.compile(PATH);
    /** {@code a.b >= 18}, {@code date(dob) < date("1966-01-01")} (the Studio's compiled form) and the like. */
    private static final Pattern COMPARISON = Pattern.compile(
            "(?<![\\w.\"])(?:date\\(\\s*(" + PATH + ")\\s*\\)|(" + PATH + "))\\s*(>=|<=|!=|>|<|=)\\s*"
                    + "(?:date\\(\\s*\"(\\d{4}-\\d{2}-\\d{2})\"\\s*\\)|" + LITERAL + ")");
    /** {@code gender in ["FEMALE", "OTHER"]}: the first listed value stands for the list. */
    private static final Pattern IN_LIST = Pattern.compile(
            "(?<![\\w.\"])(" + PATH + ")\\s+in\\s*\\[\\s*(\"[^\"]*\"|-?\\d+(?:\\.\\d+)?)");
    private static final Pattern UNARY = Pattern.compile("^\\s*(>=|<=|!=|>|<|=)?\\s*" + LITERAL + "\\s*$");
    private static final Pattern RANGE = Pattern.compile(
            "^\\s*[\\[(\\]]\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\.\\.\\s*(-?\\d+(?:\\.\\d+)?)\\s*[\\])\\[]\\s*$");

    private static final Set<String> FEEL_WORDS = Set.of("true", "false", "null", "date", "not", "and", "or");

    public record Constraint(String attribute, String operator, Object value) {
    }

    public record TestCase(String name, Map<String, Object> record, List<String> firedClauses, List<Object> outcomes,
                           String error) {
    }

    private RuleTestCaseGenerator() {
    }

    public static List<TestCase> generate(RuleSet set, RuleSetService rules) {
        List<Constraint> constraints = constraints(set.dmnXml());
        Set<String> attributes = new LinkedHashSet<>(set.requiredAttributes());
        constraints.forEach(c -> attributes.add(c.attribute()));

        // The baseline satisfies each attribute's first constraint, so a case varies one attribute at a time.
        Map<String, Object> baseline = new LinkedHashMap<>();
        for (String attribute : attributes) {
            baseline.put(attribute, constraints.stream().filter(c -> c.attribute().equals(attribute))
                    .map(RuleTestCaseGenerator::satisfying).findFirst().orElse(null));
        }

        Map<Map<String, Object>, String> cases = new LinkedHashMap<>();
        for (Constraint constraint : constraints) {
            String rule = constraint.attribute() + " " + constraint.operator() + " " + display(constraint.value());
            if (constraint.value() instanceof java.time.LocalDate date) {
                for (int delta : new int[] {-1, 0, 1}) {
                    String value = date.plusDays(delta).toString();
                    add(cases, baseline, constraint.attribute(), value,
                            constraint.attribute() + " = " + value + " (boundary of " + rule + ")");
                }
            } else if (constraint.value() instanceof Number number) {
                boolean whole = number instanceof Integer || number instanceof Long;
                double step = whole ? 1 : 0.01;
                for (double delta : new double[] {-step, 0, step}) {
                    Object value = whole ? (Object) (int) (number.doubleValue() + delta)
                            : (Object) (Math.round((number.doubleValue() + delta) * 100) / 100.0);
                    add(cases, baseline, constraint.attribute(), value,
                            constraint.attribute() + " = " + value + " (boundary of " + rule + ")");
                }
            } else {
                add(cases, baseline, constraint.attribute(), constraint.value(),
                        constraint.attribute() + " = " + display(constraint.value()) + " (" + rule + ")");
                Object other = constraint.value() instanceof Boolean b ? (Object) !b : "OTHER";
                add(cases, baseline, constraint.attribute(), other,
                        constraint.attribute() + " = " + display(other) + " (not " + rule + ")");
            }
        }
        for (String attribute : attributes) {
            add(cases, baseline, attribute, null, attribute + " missing");
        }

        List<TestCase> out = new ArrayList<>();
        for (var entry : cases.entrySet()) {
            if (out.size() >= MAX_CASES) {
                break;
            }
            out.add(evaluate(set, rules, entry.getValue(), entry.getKey()));
        }
        return List.copyOf(out);
    }

    private static TestCase evaluate(RuleSet set, RuleSetService rules, String name, Map<String, Object> record) {
        try {
            var fired = rules.evaluateById(set.id(), record).firedRules();
            List<Object> outcomes = new ArrayList<>();
            fired.forEach(f -> {
                Object outcome = f.outputs().containsKey("outcome") ? f.outputs().get("outcome") : f.outputs().get("eligible");
                if (outcome != null) {
                    outcomes.add(outcome);
                }
            });
            return new TestCase(name, record, fired.stream().map(RuleSetService.FiredRule::clauseReference).toList(),
                    List.copyOf(outcomes), null);
        } catch (RuntimeException e) {
            return new TestCase(name, record, List.of(), List.of(), e.getMessage());
        }
    }

    private static void add(Map<Map<String, Object>, String> cases, Map<String, Object> baseline, String attribute,
                            Object value, String name) {
        Map<String, Object> record = new HashMap<>(baseline);
        record.put(attribute, value);
        cases.putIfAbsent(nested(baseline.keySet(), record), name);
    }

    /** Dotted attributes become nested objects ({@code age.years} as {@code {"age":{"years":..}}}), as FEEL reads them. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> nested(Set<String> order, Map<String, Object> flat) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : order) {
            String[] parts = key.split("\\.");
            Map<String, Object> at = out;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = at.get(parts[i]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<String, Object>();
                    at.put(parts[i], next);
                }
                at = (Map<String, Object>) next;
            }
            at.put(parts[parts.length - 1], flat.get(key));
        }
        return out;
    }

    /** A value that makes the constraint true. */
    private static Object satisfying(Constraint c) {
        Object v = c.value();
        boolean strictUp = ">".equals(c.operator());
        boolean strictDown = "<".equals(c.operator());
        if (v instanceof java.time.LocalDate d) {
            return (strictUp ? d.plusDays(1) : strictDown ? d.minusDays(1) : d).toString();
        }
        if (v instanceof Integer i) {
            return strictUp ? i + 1 : strictDown ? i - 1 : "!=".equals(c.operator()) ? i + 1 : i;
        }
        if (v instanceof Double d) {
            return strictUp ? d + 0.01 : strictDown ? d - 0.01 : "!=".equals(c.operator()) ? d + 1 : d;
        }
        if ("!=".equals(c.operator())) {
            return v instanceof Boolean b ? !b : "OTHER";
        }
        return v;
    }

    /** Attribute-literal comparisons in input expressions and in unary input entries of simple inputs. */
    static List<Constraint> constraints(String dmnXml) {
        var model = Dmn.readModelFromStream(new ByteArrayInputStream(dmnXml.getBytes(StandardCharsets.UTF_8)));
        List<Input> inputs = new ArrayList<>(model.getModelElementsByType(Input.class));
        Set<Constraint> found = new LinkedHashSet<>();
        for (Input input : inputs) {
            String expression = input.getInputExpression() == null || input.getInputExpression().getText() == null
                    ? "" : input.getInputExpression().getText().getTextContent().strip();
            if (!IDENTIFIER.matcher(expression).matches()) {
                String text = expression;
                Matcher m = COMPARISON.matcher(text);
                while (m.find()) {
                    String attribute = m.group(1) != null ? m.group(1) : m.group(2);
                    if (FEEL_WORDS.contains(attribute)) {
                        continue;
                    }
                    Object value = m.group(4) != null ? java.time.LocalDate.parse(m.group(4)) : literal(m.group(5));
                    found.add(new Constraint(attribute, m.group(3), value));
                }
                Matcher in = IN_LIST.matcher(text);
                while (in.find()) {
                    found.add(new Constraint(in.group(1), "=", literal(in.group(2))));
                }
            }
        }
        for (Rule rule : model.getModelElementsByType(Rule.class)) {
            List<InputEntry> entries = new ArrayList<>(rule.getInputEntries());
            for (int i = 0; i < entries.size() && i < inputs.size(); i++) {
                Input input = inputs.get(i);
                String attribute = input.getInputExpression() == null || input.getInputExpression().getText() == null
                        ? "" : input.getInputExpression().getText().getTextContent().strip();
                if (!IDENTIFIER.matcher(attribute).matches() || entries.get(i).getText() == null) {
                    continue;
                }
                String entry = entries.get(i).getText().getTextContent().strip();
                Matcher range = RANGE.matcher(entry);
                if (range.matches()) {
                    found.add(new Constraint(attribute, ">=", literal(range.group(1))));
                    found.add(new Constraint(attribute, "<=", literal(range.group(2))));
                    continue;
                }
                Matcher unary = UNARY.matcher(entry);
                if (unary.matches()) {
                    found.add(new Constraint(attribute, unary.group(1) == null ? "=" : unary.group(1),
                            literal(unary.group(2))));
                }
            }
        }
        return List.copyOf(found);
    }

    private static Object literal(String text) {
        if (text.startsWith("\"")) {
            return text.substring(1, text.length() - 1);
        }
        if ("true".equals(text) || "false".equals(text)) {
            return Boolean.parseBoolean(text);
        }
        return text.contains(".") ? (Object) Double.parseDouble(text) : (Object) Integer.parseInt(text);
    }

    private static String display(Object value) {
        return value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
    }
}
