package gov.uddp.core.mapping;

import gov.uddp.core.scheme.SchemeCatalogue;
import gov.uddp.core.scheme.SchemeCatalogue.Scheme;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.CrosswalkDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import gov.uddp.shaping.api.RecordShaper;
import gov.uddp.shaping.api.TidyRecord;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Applies a State Pack's declarative {@code Application} mapping rules to one tidy record: which application the row
 * reports, its scheme (crosswalked onto the platform {@link SchemeCatalogue}), applicant, stage and status. Nothing
 * here knows a state, a source schema, a portal or a scheme. Resident, Family and Benefit mapping are unaffected.
 */
@Service
public class ApplicationMappingEngine {
    public static final String ENTITY = "Application";

    private static final String EXTENSIONS = "extensions.";
    private static final String ATTRIBUTES = "attributes.";
    private static final Set<String> TARGETS = Set.of("sourceApplicationId", "schemeCode", "applicantSourceRecordKey",
            "submittedOn", "currentStage", "currentStatus", "nextStage", "lastActionDate");

    private final RecordShaper shaping;
    private final SchemeCatalogue catalogue;

    public ApplicationMappingEngine(RawCsvRecordParser parser, SchemeCatalogue catalogue) {
        this(RecordShaper.passthrough(parser), catalogue);
    }

    @Autowired
    public ApplicationMappingEngine(RecordShaper shaping, SchemeCatalogue catalogue) {
        this.shaping = shaping;
        this.catalogue = catalogue;
    }

    /**
     * The application one delivery names. {@code applicantSourceRecordKey} is null when the pack maps none; the
     * caller then uses the Resident source record key of the delivery.
     */
    public record MappedApplication(String sourceSchemeCode, Scheme scheme, String sourceApplicationId,
                                    String applicantSourceRecordKey, LocalDate submittedOn, String currentStage,
                                    String currentStatus, String nextStage, LocalDate lastActionDate,
                                    Map<String, Object> attributes, Map<String, Object> extensions) {
    }

    /** What one delivery says about an application; {@code application} is empty when it names none. */
    public record ApplicationMapping(String mappingVersion, String catalogueVersion,
                                     Optional<MappedApplication> application) {
    }

    /** Maps the raw record's Application rules (shaping it first); empty when the source has none. */
    public Optional<ApplicationMapping> map(RawRecord raw, LoadedStatePack pack, String sourceId) {
        return map(shaping.shape(raw, pack, sourceId), pack, sourceId);
    }

    /**
     * Maps the tidy record's Application rules; empty when the source has none. A row whose application ID is blank
     * names no application (unless the rule is {@code required}); any other problem is an
     * {@link IllegalArgumentException}.
     */
    public Optional<ApplicationMapping> map(TidyRecord tidy, LoadedStatePack pack, String sourceId) {
        Optional<MappingDefinition> mapping = pack.mappings().stream()
                .filter(m -> m.sourceId().equals(sourceId))
                .findFirst();
        if (mapping.isEmpty() || mapping.get().entities() == null || mapping.get().entities().get(ENTITY) == null) {
            return Optional.empty();
        }
        Map<String, Object> values = tidy.fields();
        Map<String, Object> mapped = new LinkedHashMap<>();
        Map<String, Object> attributes = new LinkedHashMap<>();
        Map<String, Object> extensions = new LinkedHashMap<>();
        String sourceSchemeCode = null;
        for (MappingRule rule : mapping.get().entities().get(ENTITY)) {
            String target = rule.target();
            boolean extension = hasPrefix(target, EXTENSIONS);
            boolean attribute = hasPrefix(target, ATTRIBUTES);
            if (!extension && !attribute && !TARGETS.contains(target)) {
                throw new IllegalArgumentException("Unsupported Application mapping target: " + target);
            }
            Object value = values.get(rule.sourceField());
            if (value == null || value.toString().isBlank()) {
                if (Boolean.TRUE.equals(rule.required())) {
                    throw new IllegalArgumentException("Required Application source field is blank: " + rule.sourceField());
                }
                continue;
            }
            Object converted = convert(crosswalk(value.toString().strip(), rule, pack), rule);
            if ("schemeCode".equals(target)) {
                sourceSchemeCode = value.toString().strip();
            }
            if (extension) {
                extensions.put(target.substring(EXTENSIONS.length()), converted);
            } else if (attribute) {
                attributes.put(target.substring(ATTRIBUTES.length()), converted);
            } else {
                mapped.put(target, converted);
            }
        }
        String version = mapping.get().mappingVersion();
        if (!mapped.containsKey("sourceApplicationId")) {
            return Optional.of(new ApplicationMapping(version, catalogue.version(), Optional.empty()));
        }
        String schemeId = text(mapped, "schemeCode");
        if (schemeId == null) {
            throw new IllegalArgumentException("An application needs a schemeCode");
        }
        Scheme scheme = catalogue.scheme(schemeId).orElseThrow(() -> new IllegalArgumentException(
                "Scheme " + schemeId + " is not in scheme catalogue " + catalogue.version()));
        for (String required : new String[] {"currentStage", "currentStatus"}) {
            if (text(mapped, required) == null) {
                throw new IllegalArgumentException("An application needs a " + required);
            }
        }
        var application = new MappedApplication(sourceSchemeCode, scheme, text(mapped, "sourceApplicationId"),
                text(mapped, "applicantSourceRecordKey"), date(mapped, "submittedOn"), text(mapped, "currentStage"),
                text(mapped, "currentStatus"), text(mapped, "nextStage"), date(mapped, "lastActionDate"),
                Map.copyOf(attributes), Map.copyOf(extensions));
        return Optional.of(new ApplicationMapping(version, catalogue.version(), Optional.of(application)));
    }

    private static boolean hasPrefix(String target, String prefix) {
        return target != null && target.startsWith(prefix) && target.length() > prefix.length();
    }

    private static String text(Map<String, Object> mapped, String target) {
        Object value = mapped.get(target);
        return value == null ? null : value.toString();
    }

    private static LocalDate date(Map<String, Object> mapped, String target) {
        Object value = mapped.get(target);
        if (value == null || value instanceof LocalDate) {
            return (LocalDate) value;
        }
        throw new IllegalArgumentException(target + " needs a date conversion (conversion: date:<format>)");
    }

    private static Object convert(String value, MappingRule rule) {
        if (rule.conversion() == null) {
            return value;
        }
        if (!rule.conversion().startsWith("date:")) {
            throw new IllegalArgumentException("Unsupported Application conversion: " + rule.conversion());
        }
        String format = rule.conversion().substring("date:".length());
        String pattern = format.replace("YYYY", "uuuu").replace("DD", "dd").replace("YY", "uu")
                .toLowerCase(Locale.ROOT).replace("mm", "MM");
        try {
            return LocalDate.parse(value, DateTimeFormatter.ofPattern(pattern));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date for configured format " + format + ": " + value, e);
        }
    }

    private static String crosswalk(String value, MappingRule rule, LoadedStatePack pack) {
        if (rule.crosswalk() == null) {
            return value;
        }
        CrosswalkDefinition crosswalk = pack.crosswalks().get(rule.crosswalk());
        if (crosswalk == null) {
            throw new IllegalArgumentException("Unknown crosswalk: " + rule.crosswalk());
        }
        String mapped = crosswalk.values().get(value);
        if (mapped == null) {
            throw new IllegalArgumentException("No crosswalk value for " + rule.crosswalk() + ": " + value);
        }
        return mapped;
    }
}
