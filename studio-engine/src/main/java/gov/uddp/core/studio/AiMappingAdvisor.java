package gov.uddp.core.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.ai.AiDraftService;
import gov.uddp.core.studio.StudioMetadata.Target;
import gov.uddp.core.studio.StudioModels.ColumnProfile;
import gov.uddp.core.studio.StudioModels.ColumnSuggestion;
import gov.uddp.core.studio.StudioModels.Profile;
import gov.uddp.core.studio.StudioModels.Suggestions;
import gov.uddp.core.studio.StudioModels.TargetSuggestion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Track D2: asks the {@link AiDraftService} provider for mappings and combines them with the learned (D1)
 * suggestions. Only column names, profiled types and masked sample shapes (letters as A/a, digits as 9) are sent with
 * the canonical target list, never a value. A model that agrees with a D1 suggestion raises its confidence; a
 * suggestion only the model makes is added for review and is never pre-accepted. A disabled provider, a failed call
 * or unusable output leaves the D1 suggestions unchanged.
 */
@Component
public class AiMappingAdvisor {
    private static final Logger log = LoggerFactory.getLogger(AiMappingAdvisor.class);
    static final int SAMPLES = 3;
    static final double AGREEMENT_BOOST = 0.1;

    static final String INSTRUCTION = """
            Map source columns of a government data source onto canonical targets.
            The content is JSON: "columns" (name, inferredType, dateFormat, maskedSamples where letters are A/a and
            digits 9) and "targets" (the only allowed targets, as Entity:field).
            Answer with JSON only: {"suggestions":[{"column":"<column name>","target":"<one of targets>",
            "confidence":<0..1>,"reason":"<short reason>"}]}. Leave out columns you cannot map.""";

    private final AiDraftService ai;
    private final StudioMetadata metadata;
    private final ObjectMapper json;

    public AiMappingAdvisor(AiDraftService ai, StudioMetadata metadata, ObjectMapper json) {
        this.ai = ai;
        this.metadata = metadata;
        this.json = json;
    }

    public Suggestions combine(Profile profile, Suggestions learned) {
        if (!ai.enabled()) {
            return learned;
        }
        try {
            String answer = ai.complete(INSTRUCTION, request(profile));
            return merge(profile, learned, parse(answer));
        } catch (RuntimeException e) {
            log.warn("AI mapping suggestions ignored: {}", e.getMessage());
            return learned;
        }
    }

    /** Column names, types and masked shapes plus the canonical target list; no source value leaves the platform. */
    String request(Profile profile) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (ColumnProfile column : profile.columns()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", column.name());
            entry.put("inferredType", column.inferredType());
            entry.put("dateFormat", column.dateFormat());
            entry.put("maskedSamples", column.topValues().stream().limit(SAMPLES).map(v -> mask(v.value())).toList());
            columns.add(entry);
        }
        List<String> targets = metadata.targets().stream().map(Target::key).toList();
        try {
            return json.writeValueAsString(Map.of("columns", columns, "targets", targets));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot build the AI mapping request", e);
        }
    }

    static String mask(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder shape = new StringBuilder();
        value.codePoints().forEach(c -> {
            if (Character.isDigit(c)) {
                shape.append('9');
            } else if (Character.isUpperCase(c)) {
                shape.append('A');
            } else if (Character.isLetter(c)) {
                shape.append('a');
            } else {
                shape.appendCodePoint(c);
            }
        });
        return shape.toString();
    }

    record AiSuggestion(String column, String target, double confidence, String reason) {
    }

    private List<AiSuggestion> parse(String answer) {
        try {
            String body = answer == null ? "" : answer.strip();
            if (body.startsWith("```")) {
                body = body.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
            }
            JsonNode items = json.readTree(body).path("suggestions");
            if (!items.isArray()) {
                throw new IllegalArgumentException("no suggestions array");
            }
            List<AiSuggestion> out = new ArrayList<>();
            for (JsonNode item : items) {
                double confidence = item.path("confidence").asDouble(0);
                out.add(new AiSuggestion(item.path("column").asText(""), item.path("target").asText(""),
                        Math.max(0, Math.min(confidence, 1)), item.path("reason").asText("")));
            }
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("unusable AI output: " + e.getMessage(), e);
        }
    }

    private Suggestions merge(Profile profile, Suggestions learned, List<AiSuggestion> proposals) {
        Map<String, ColumnProfile> columns = new LinkedHashMap<>();
        profile.columns().forEach(c -> columns.put(c.name(), c));
        Map<String, List<TargetSuggestion>> merged = new LinkedHashMap<>();
        for (ColumnSuggestion column : learned.columns()) {
            merged.put(column.column(), new ArrayList<>(column.targets()));
        }
        for (AiSuggestion proposal : proposals) {
            ColumnProfile column = columns.get(proposal.column());
            Target target = target(proposal.target());
            if (column == null || target == null || proposal.confidence() <= 0) {
                continue;
            }
            List<TargetSuggestion> targets = merged.computeIfAbsent(column.name(), k -> new ArrayList<>());
            int existing = indexOf(targets, target);
            String why = proposal.reason().isBlank() ? "" : ": " + proposal.reason();
            if (existing >= 0) {
                TargetSuggestion d1 = targets.get(existing);
                double confidence = Math.min(1.0, d1.confidence() + AGREEMENT_BOOST * proposal.confidence());
                targets.set(existing, new TargetSuggestion(d1.entity(), d1.target(), d1.conversion(), d1.crosswalk(),
                        round(confidence), d1.basis() + ", model agrees (" + Math.round(proposal.confidence() * 100)
                        + "%" + why + ")", d1.accepted()));
            } else {
                String conversion = "date".equals(target.type()) && column.dateFormat() != null
                        ? "date:" + column.dateFormat() : null;
                String crosswalk = target.codeSet() == null ? null : SuggestionEngine.crosswalkId(target.codeSet());
                targets.add(new TargetSuggestion(target.entity(), target.target(), conversion, crosswalk,
                        round(proposal.confidence()), "model only, review" + why, false));
            }
        }
        List<ColumnSuggestion> out = new ArrayList<>();
        merged.forEach((name, targets) -> out.add(new ColumnSuggestion(name, List.copyOf(targets))));
        return new Suggestions(out, learned.crosswalks(), learned.recordIdField(), learned.recordKeyFields());
    }

    private Target target(String key) {
        int colon = key.indexOf(':');
        return colon <= 0 ? null : metadata.target(key.substring(0, colon), key.substring(colon + 1)).orElse(null);
    }

    private static int indexOf(List<TargetSuggestion> targets, Target target) {
        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i).entity().equals(target.entity()) && targets.get(i).target().equals(target.target())) {
                return i;
            }
        }
        return -1;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
