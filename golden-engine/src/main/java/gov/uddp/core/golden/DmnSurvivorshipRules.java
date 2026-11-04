package gov.uddp.core.golden;

import gov.uddp.core.rules.RuleSet;
import gov.uddp.core.rules.RuleSetService;
import gov.uddp.core.rules.RulesProperties;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Converts a DMN selected-source result into an existing candidate, never manufacturing provenance. */
@Component
public class DmnSurvivorshipRules {
    private final RuleSetService rules;
    private final RulesProperties properties;

    public DmnSurvivorshipRules(RuleSetService rules, RulesProperties properties) {
        this.rules = rules; this.properties = properties;
    }

    public Optional<Choice> choose(String field, List<CanonicalObservation> candidates) {
        String ownerId = properties.getSurvivorshipOwnerId();
        if (ownerId == null || ownerId.isBlank()) return Optional.empty();
        List<Map<String, String>> evidence = new ArrayList<>();
        for (CanonicalObservation candidate : candidates) {
            evidence.add(Map.of("canonicalId", candidate.id().toString(), "stateCode", candidate.stateCode(),
                    "source", candidate.source(), "value", value(candidate, field)));
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("field", field); input.put("candidateCount", candidates.size()); input.put("candidates", evidence);
        return rules.evaluateIfEffective(RuleSet.Type.SURVIVORSHIP, properties.getSurvivorshipOwnerType(), ownerId,
                        LocalDate.now(), input)
                .flatMap(evaluation -> evaluation.firedRules().stream().findFirst())
                .flatMap(fired -> select(candidates, fired));
    }

    private static Optional<Choice> select(List<CanonicalObservation> candidates, RuleSetService.FiredRule fired) {
        Object source = fired.outputs().get("selectedSource");
        if (source == null) return Optional.empty();
        return candidates.stream().filter(candidate -> candidate.source().equals(source.toString())).findFirst()
                .map(candidate -> new Choice(candidate, fired.clauseReference()));
    }

    private static String value(CanonicalObservation candidate, String field) {
        var value = candidate.payload();
        for (String part : field.split("\\.")) value = value.path(part);
        return value.isMissingNode() || value.isNull() ? "" : value.asText();
    }

    public record Choice(CanonicalObservation candidate, String clauseReference) { }
}
