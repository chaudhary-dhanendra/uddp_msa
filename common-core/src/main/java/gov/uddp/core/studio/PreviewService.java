package gov.uddp.core.studio;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import gov.uddp.core.mapping.BenefitMappingEngine;
import gov.uddp.core.mapping.FamilyMappingEngine;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.mapping.ResidentMappingEngine;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.studio.StudioModels.Preview;
import gov.uddp.core.studio.StudioModels.PreviewIssue;
import gov.uddp.core.studio.StudioModels.PreviewRow;
import gov.uddp.core.held.FixTarget;
import gov.uddp.core.validation.PlatformReferenceData;
import gov.uddp.core.validation.ReasonCodes;
import gov.uddp.core.validation.ResidentValidator;
import gov.uddp.core.validation.ValidationIssue;
import gov.uddp.core.geography.CanonicalGeographyResolver;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Dry run: maps and validates the first rows of the draft's source with the EXISTING generic engines (Resident,
 * Family and Benefit mapping, Resident validation with platform reference data), exactly as canonical processing
 * would, and writes nothing.
 */
@Service
public class PreviewService {
    private final ResidentMappingEngine residents;
    private final FamilyMappingEngine families;
    private final BenefitMappingEngine benefits;
    private final ResidentValidator validator;
    private final PlatformReferenceData references;
    private gov.uddp.core.reference.ReferenceDataService referenceData;
    private FixSuggestions suggestions = new FixSuggestions();
    private final ObjectMapper json;

    public PreviewService(ResidentMappingEngine residents, FamilyMappingEngine families, BenefitMappingEngine benefits,
                          ResidentValidator validator, PlatformReferenceData references, ObjectMapper json) {
        this.residents = residents;
        this.families = families;
        this.benefits = benefits;
        this.validator = validator;
        this.references = references;
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** Live reference sets (Track F2): previews validate {@code lgd-*} sets against the LGD master once loaded. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReferenceData(gov.uddp.core.reference.ReferenceDataService referenceData) {
        this.referenceData = referenceData;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSuggestions(FixSuggestions suggestions) {
        this.suggestions = suggestions;
    }

    /** A mapping failure as an issue with its reason code, the pack part that fixes it and a suggested fix. */
    private PreviewIssue failure(String entity, RuntimeException e, LoadedStatePack pack, String sourceId,
                                 Map<String, String> row) {
        String code = ReasonCodes.of(e);
        Map<String, String> context = ReasonCodes.contextOf(e);
        return new PreviewIssue(context.get("target"), "error", entity + ": " + e.getMessage(), code,
                FixTarget.of(code, context.get("target"), context, pack, sourceId),
                suggestions.suggest(code, context, pack, row));
    }

    public Preview preview(LoadedStatePack pack, String sourceId, List<String> header, List<Map<String, String>> rows) {
        String stateCode = pack.manifest().stateCode();
        UUID batch = UUID.randomUUID();
        List<PreviewRow> previewed = new ArrayList<>();
        List<JsonNode> mapped = new ArrayList<>();
        List<Integer> mappedRows = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            RawRecord raw = new RawRecord(UUID.randomUUID(), batch, stateCode, sourceId, OffsetDateTime.now(),
                    payload(header, row), Map.of("sourceEntity", sourceId), pack.manifest().version(), "1.0",
                    "preview");
            List<PreviewIssue> issues = new ArrayList<>();
            Map<String, Object> resident = null;
            Map<String, Object> family = null;
            Map<String, Object> benefit = null;
            try {
                JsonNode node = json.valueToTree(residents.map(raw, pack, sourceId));
                resident = json.convertValue(node, new TypeReference<>() { });
                mapped.add(node);
                mappedRows.add(i);
                unresolvedGeography(resident, issues, pack, sourceId);
            } catch (RuntimeException e) {
                issues.add(failure("Resident", e, pack, sourceId, row));
            }
            try {
                var familyMapping = families.map(raw, pack, sourceId);
                if (familyMapping.isPresent()) {
                    family = json.convertValue(familyMapping.get(), new TypeReference<>() { });
                }
            } catch (RuntimeException e) {
                issues.add(failure("Family", e, pack, sourceId, row));
            }
            try {
                var benefitMapping = benefits.map(raw, pack, sourceId);
                if (benefitMapping.isPresent() && benefitMapping.get().benefit().isPresent()) {
                    benefit = json.convertValue(benefitMapping.get().benefit().get(), new TypeReference<>() { });
                }
            } catch (RuntimeException e) {
                issues.add(failure("Benefit", e, pack, sourceId, row));
            }
            previewed.add(new PreviewRow(i + 1, row, resident, family, benefit, issues));
        }
        Map<String, Set<String>> referenceValues = referenceData == null ? references.values()
                : referenceData.resolve(references.values());
        List<List<ValidationIssue>> validation = mapped.isEmpty()
                ? List.of()
                : validator.validateBatch(mapped, pack, referenceValues, Clock.systemUTC());
        for (int k = 0; k < validation.size(); k++) {
            PreviewRow row = previewed.get(mappedRows.get(k));
            for (ValidationIssue issue : validation.get(k)) {
                String code = ReasonCodes.ruleFailed(issue.rule());
                row.issues().add(new PreviewIssue(issue.field(), issue.severity().name().toLowerCase(Locale.ROOT),
                        issue.message(), code, FixTarget.of(code, issue.field(), Map.of(), pack, sourceId), null));
            }
        }
        int errors = 0;
        int warnings = 0;
        Map<String, Integer> byCode = new java.util.TreeMap<>();
        for (PreviewRow row : previewed) {
            for (PreviewIssue issue : row.issues()) {
                byCode.merge(issue.code(), 1, Integer::sum);
                if ("error".equals(issue.severity())) {
                    errors++;
                } else {
                    warnings++;
                }
            }
        }
        return new Preview(previewed.size(), errors, warnings, null, previewed, byCode);
    }

    @SuppressWarnings("unchecked")
    private void unresolvedGeography(Map<String, Object> resident, List<PreviewIssue> issues, LoadedStatePack pack,
                                     String sourceId) {
        Object extensions = resident.get("extensions");
        if (!(extensions instanceof Map<?, ?> extension)) return;
        Object geography = extension.get(CanonicalGeographyResolver.EXTENSION);
        if (!(geography instanceof Map<?, ?> levels)) return;
        levels.forEach((level, value) -> {
            if (value instanceof Map<?, ?> entry && "UNKNOWN".equals(entry.get("status"))
                    && entry.get("raw") != null) {
                String field = "geography." + level + ".name";
                String message = "Geography: " + level + " \"" + entry.get("raw")
                        + "\" could not be resolved to an active LGD entity";
                issues.add(new PreviewIssue(field, "error", message, ReasonCodes.UNMAPPED_CODE,
                        FixTarget.of(ReasonCodes.UNMAPPED_CODE, field,
                                Map.of("target", field, "value", String.valueOf(entry.get("raw"))), pack, sourceId), null));
            }
        });
    }

    /** The raw payload the generic Hop template lands: the CSV header and the row as one CSV line. */
    private String payload(List<String> header, Map<String, String> row) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("csvHeader", csvLine(header));
        payload.put("rawLine", csvLine(header.stream().map(column -> row.getOrDefault(column, "")).toList()));
        try {
            return json.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot build the preview payload", e);
        }
    }

    private static String csvLine(List<String> values) {
        List<String> cells = new ArrayList<>();
        for (String value : values) {
            String cell = value == null ? "" : value;
            if (cell.contains(",") || cell.contains("\"") || cell.contains("\n")) {
                cell = "\"" + cell.replace("\"", "\"\"") + "\"";
            }
            cells.add(cell);
        }
        return String.join(",", cells);
    }
}
