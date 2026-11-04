package gov.uddp.core.validation;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.ValidationRule;
import gov.uddp.core.validation.ValidationIssue.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.rules.RuleSet;
import gov.uddp.core.rules.RuleSetService;
import org.springframework.stereotype.Component;

/**
 * Executes a State Pack's Resident validation rules ({@code required}, {@code allowedValues}, {@code severity})
 * against the canonical Resident. Rule fields are canonical paths such as {@code name} or {@code address.districtLGD}.
 */
@Component
public class ResidentValidator {
    public static final String ENTITY = "Resident";
    private RuleSetService rules;
    private final ObjectMapper json = new ObjectMapper();

    /** A1 is optional here so existing direct users and packs retain exactly their prior behavior. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRuleSetService(RuleSetService rules) { this.rules = rules; }

    public List<ValidationIssue> validate(JsonNode resident, LoadedStatePack pack) {
        return validateBatch(List.of(resident), pack, Map.of(), Clock.systemUTC()).getFirst();
    }

    /** Evaluates one mapped batch. Reference values are platform data supplied by the caller. */
    public List<List<ValidationIssue>> validateBatch(List<JsonNode> residents, LoadedStatePack pack,
                                                     Map<String, Set<String>> references, Clock clock) {
        var all = new ArrayList<List<ValidationIssue>>();
        for (int i = 0; i < residents.size(); i++) all.add(new ArrayList<>());
        for (var definition : pack.validations()) {
            if (!ENTITY.equals(definition.entity()) || definition.rules() == null) continue;
            for (ValidationRule rule : definition.rules()) {
                for (int i = 0; i < residents.size(); i++) check(residents.get(i), rule, references, clock, all.get(i));
                if ("UNIQUE_WITHIN_BATCH".equals(rule.type())) checkUnique(residents, rule, all);
            }
        }
        if (rules != null) {
            for (int i = 0; i < residents.size(); i++) {
                dmnIssues(residents.get(i), pack, LocalDate.now(clock)).ifPresent(all.get(i)::addAll);
            }
        }
        return all.stream().map(List::copyOf).toList();
    }

    private java.util.Optional<List<ValidationIssue>> dmnIssues(JsonNode resident, LoadedStatePack pack, LocalDate on) {
        Map<String, Object> values = json.convertValue(resident, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        return rules.evaluateIfEffective(RuleSet.Type.DATA_QUALITY, RuleSet.OwnerType.SOURCE_PACK,
                        pack.manifest().packId(), on, values)
                .map(evaluation -> evaluation.firedRules().stream().map(this::issue).toList());
    }

    private ValidationIssue issue(RuleSetService.FiredRule fired) {
        Map<String, Object> output = fired.outputs();
        String field = required(output, "field", fired.clauseReference());
        String severity = required(output, "severity", fired.clauseReference());
        String message = required(output, "message", fired.clauseReference());
        return new ValidationIssue(field, fired.clauseReference(), Severity.valueOf(severity.toUpperCase(Locale.ROOT)), message);
    }

    private static String required(Map<String, Object> output, String name, String clause) {
        Object value = output.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("DMN clause " + clause + " must return " + name);
        }
        return value.toString();
    }

    private static void checkUnique(List<JsonNode> residents, ValidationRule rule, List<List<ValidationIssue>> all) {
        var seen = new HashMap<String, Integer>();
        List<String> paths = rule.fields() == null || rule.fields().isEmpty() ? List.of(rule.field()) : rule.fields();
        for (int i = 0; i < residents.size(); i++) {
            JsonNode resident = residents.get(i);
            String key = paths.stream().map(p -> text(resident, p)).reduce("", (a, b) -> a + "|" + (b == null ? "" : b));
            if (key.replace("|", "").isBlank()) continue;
            Integer first = seen.putIfAbsent(key, i);
            if (first != null) {
                String message = "duplicate value within batch; first seen at item " + first;
                all.get(i).add(issue(rule, "UNIQUE_WITHIN_BATCH", message));
                all.get(first).add(issue(rule, "UNIQUE_WITHIN_BATCH", "duplicate value within batch"));
            }
        }
    }

    private static ValidationIssue issue(ValidationRule rule, String fallback, String message) {
        return new ValidationIssue(rule.field(), rule.id() == null ? fallback : rule.id(), severity(rule), message);
    }

    private static void check(JsonNode resident, ValidationRule rule, Map<String, Set<String>> references,
                              Clock clock, List<ValidationIssue> issues) {
        String type = rule.type();
        String value = text(resident, rule.field());
        if (type == null || "REQUIRED".equals(type) || "ALLOWED_VALUES".equals(type)) {
            if (value == null && Boolean.TRUE.equals(rule.required())) issues.add(issue(rule, "required", "required value is blank"));
            if (value != null && rule.allowedValues() != null && !rule.allowedValues().contains(value))
                issues.add(issue(rule, "allowedValues", "value '" + value + "' is not one of " + rule.allowedValues()));
            return;
        }
        if (value == null) return;
        switch (type) {
            case "PATTERN" -> { if (rule.pattern() == null || !Pattern.matches(rule.pattern(), value)) issues.add(issue(rule, type, "value does not match configured pattern")); }
            case "DATE_RANGE" -> checkDate(value, rule, clock, issues);
            case "REFERENCE_LIST" -> { if (!references.getOrDefault(rule.referenceSet(), Set.of()).contains(value)) issues.add(issue(rule, type, "value is not in reference set " + rule.referenceSet())); }
            case "CROSS_FIELD" -> checkCrossField(resident, rule, issues);
            case "UNIQUE_WITHIN_BATCH" -> { /* evaluated once for the whole batch */ }
            default -> throw new IllegalArgumentException("Unsupported validation type: " + type);
        }
    }

    private static void checkDate(String value, ValidationRule rule, Clock clock, List<ValidationIssue> issues) {
        try {
            LocalDate date = LocalDate.parse(value); LocalDate today = LocalDate.now(clock);
            if ("TODAY".equals(rule.max()) && date.isAfter(today)) issues.add(issue(rule, "DATE_RANGE", "date is after reference date"));
            int age = Period.between(date, today).getYears();
            if (rule.minAgeYears() != null && age < rule.minAgeYears()) issues.add(issue(rule, "DATE_RANGE", "age is below configured minimum"));
            if (rule.maxAgeYears() != null && age > rule.maxAgeYears()) issues.add(issue(rule, "DATE_RANGE", "age exceeds configured maximum"));
        } catch (Exception e) { issues.add(issue(rule, "DATE_RANGE", "value is not an ISO-8601 date")); }
    }

    private static void checkCrossField(JsonNode resident, ValidationRule rule, List<ValidationIssue> issues) {
        List<String> paths = rule.fields() == null ? List.of() : rule.fields();
        List<String> values = paths.stream().map(p -> text(resident, p)).toList();
        boolean any = values.stream().anyMatch(v -> v != null), all = values.stream().allMatch(v -> v != null);
        boolean invalid = switch (rule.operator()) {
            case "ALL_OR_NONE" -> any && !all;
            case "REQUIRES" -> values.size() == 2 && values.getFirst() != null && values.get(1) == null;
            case "NOT_EQUALS" -> values.size() == 2 && values.getFirst() != null && values.getFirst().equals(values.get(1));
            default -> throw new IllegalArgumentException("Unsupported CROSS_FIELD operator: " + rule.operator());
        };
        if (invalid) issues.add(issue(rule, "CROSS_FIELD", "configured cross-field relationship is not satisfied"));
    }

    public static Severity severity(ValidationRule rule) {
        return Severity.valueOf(rule.severity().trim().toUpperCase(Locale.ROOT));
    }

    /** The field's text, or null when it is missing, JSON null or blank. */
    private static String text(JsonNode node, String path) {
        for (String part : path.split("\\.")) {
            node = node.path(part);
        }
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }
}
