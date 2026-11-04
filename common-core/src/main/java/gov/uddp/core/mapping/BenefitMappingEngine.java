package gov.uddp.core.mapping;

import gov.uddp.core.scheme.SchemeCatalogue;
import gov.uddp.core.scheme.SchemeCatalogue.Scheme;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.shaping.api.RecordShaper;
import gov.uddp.core.statepack.StatePackAssets.CrosswalkDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Applies a State Pack's declarative {@code Benefit} mapping rules to one raw record (M15, docs/benefit-domain.md):
 * which benefit the row says the resident holds, its scheme (crosswalked onto the platform {@link SchemeCatalogue}),
 * status, entitlement and the disbursement it reports. Nothing here knows a state, a source schema or a scheme.
 * Resident and Family mapping are unaffected.
 */
@Service
public class BenefitMappingEngine {
    public static final String ENTITY = "Benefit";
    /** The scheme of a benefit whose source code the pack's crosswalk does not map yet; kept, with a warning. */
    public static final String UNMAPPED = "UNMAPPED";
    public static final Set<String> STATUSES = Set.of("ACTIVE", "SUSPENDED", "CLOSED");

    private static final String EXTENSIONS = "extensions.";
    private static final Set<String> TARGETS = Set.of("schemeCode", "sourceBenefitId", "sourceFamilyId", "status",
            "startDate", "entitlement.amount", "entitlement.quantity", "entitlement.unit", "entitlement.frequency",
            "disbursement.date", "disbursement.amount", "disbursement.quantity", "disbursement.reference");

    private final RecordShaper shaping;
    private final SchemeCatalogue catalogue;

    public BenefitMappingEngine(RawCsvRecordParser parser, SchemeCatalogue catalogue) {
        this(RecordShaper.passthrough(parser), catalogue);
    }

    @Autowired
    public BenefitMappingEngine(RecordShaper shaping, SchemeCatalogue catalogue) {
        this.shaping = shaping;
        this.catalogue = catalogue;
    }

    /** A disbursement (payment, ration lifting) the row reports. */
    public record Disbursement(LocalDate date, BigDecimal amount, BigDecimal quantity, String unit, String reference) {
    }

    /** The benefit one delivery names; {@code scheme} is the platform catalogue entry. */
    public record MappedBenefit(String sourceSchemeCode, Scheme scheme, String sourceBenefitId, String sourceFamilyId,
                                String status, LocalDate startDate, BigDecimal amount, BigDecimal quantity,
                                String unit, String frequency, Disbursement disbursement,
                                Map<String, Object> extensions) {
    }

    /** What one delivery says about the resident's benefit; {@code benefit} is empty when it names none. */
    public record BenefitMapping(String mappingVersion, String catalogueVersion, Optional<MappedBenefit> benefit,
                                 List<String> warnings) {
        public BenefitMapping(String mappingVersion, String catalogueVersion, Optional<MappedBenefit> benefit) {
            this(mappingVersion, catalogueVersion, benefit, List.of());
        }
    }

    public String catalogueVersion() {
        return catalogue.version();
    }

    /**
     * Maps the record's Benefit rules; empty when the source has none. A row whose scheme code is blank names no
     * benefit (unless the rule is {@code required}). A scheme code the crosswalk does not map is kept as
     * {@link #UNMAPPED} with a warning; any other problem is an {@link IllegalArgumentException}.
     */
    public Optional<BenefitMapping> map(RawRecord raw, LoadedStatePack pack, String sourceId) {
        Optional<MappingDefinition> mapping = pack.mappings().stream()
                .filter(m -> m.sourceId().equals(sourceId))
                .findFirst();
        if (mapping.isEmpty() || mapping.get().entities() == null || mapping.get().entities().get(ENTITY) == null) {
            return Optional.empty();
        }
        Map<String, Object> values = shaping.shape(raw, pack, sourceId).fields();
        Map<String, Object> mapped = new LinkedHashMap<>();
        Map<String, Object> extensions = new LinkedHashMap<>();
        String sourceSchemeCode = null;
        List<String> warnings = new java.util.ArrayList<>();
        for (MappingRule rule : mapping.get().entities().get(ENTITY)) {
            String target = rule.target();
            boolean extension = target != null && target.startsWith(EXTENSIONS) && target.length() > EXTENSIONS.length();
            if (!extension && !TARGETS.contains(target)) {
                throw new IllegalArgumentException("Unsupported Benefit mapping target: " + target);
            }
            Object value = values.get(rule.sourceField());
            if (value == null || value.toString().isBlank()) {
                if (Boolean.TRUE.equals(rule.required())) {
                    throw new IllegalArgumentException("Required Benefit source field is blank: " + rule.sourceField());
                }
                continue;
            }
            String sourceValue = value.toString().strip();
            Object converted;
            if ("schemeCode".equals(target) && isUnmapped(sourceValue, rule, pack)) {
                converted = UNMAPPED;
                warnings.add("No crosswalk value for " + rule.crosswalk() + ": " + sourceValue);
            } else {
                converted = convert(crosswalk(sourceValue, rule, pack), rule);
            }
            if ("schemeCode".equals(target)) {
                sourceSchemeCode = sourceValue;
            }
            if (extension) {
                extensions.put(target.substring(EXTENSIONS.length()), converted);
            } else {
                mapped.put(target, converted);
            }
        }
        String version = mapping.get().mappingVersion();
        if (!mapped.containsKey("schemeCode")) {
            return Optional.of(new BenefitMapping(version, catalogue.version(), Optional.empty()));
        }
        String schemeId = text(mapped, "schemeCode");
        Scheme scheme = UNMAPPED.equals(schemeId)
                ? new Scheme(UNMAPPED, "Unmapped scheme code", null, UNMAPPED, UNMAPPED, "RESIDENT", null, null, null, null)
                : catalogue.scheme(schemeId).orElseThrow(() -> new IllegalArgumentException(
                        "Scheme " + schemeId + " is not in scheme catalogue " + catalogue.version()));
        String benefitId = text(mapped, "sourceBenefitId");
        if (benefitId == null) {
            throw new IllegalArgumentException("A benefit of scheme " + schemeId + " needs a sourceBenefitId");
        }
        String familyId = text(mapped, "sourceFamilyId");
        if ("FAMILY".equals(scheme.beneficiaryLevel()) && familyId == null) {
            throw new IllegalArgumentException("Scheme " + schemeId + " is held by a household: sourceFamilyId is required");
        }
        String status = Optional.ofNullable(text(mapped, "status")).orElse("ACTIVE");
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("Unsupported benefit status '" + status + "'; allowed: "
                    + String.join(", ", new TreeSet<>(STATUSES)));
        }
        String frequency = Optional.ofNullable(text(mapped, "entitlement.frequency")).orElse(scheme.frequency());
        if (frequency != null && !SchemeCatalogue.FREQUENCIES.contains(frequency)) {
            throw new IllegalArgumentException("Unsupported entitlement frequency '" + frequency + "'");
        }
        String unit = Optional.ofNullable(text(mapped, "entitlement.unit")).orElse(scheme.unit());
        LocalDate paidOn = date(mapped, "disbursement.date");
        Disbursement disbursement = paidOn == null ? null : new Disbursement(paidOn,
                decimal(mapped, "disbursement.amount"), decimal(mapped, "disbursement.quantity"), unit,
                text(mapped, "disbursement.reference"));
        if (paidOn == null && (mapped.containsKey("disbursement.amount") || mapped.containsKey("disbursement.quantity"))) {
            throw new IllegalArgumentException("A disbursement amount or quantity needs a disbursement.date");
        }
        var benefit = new MappedBenefit(sourceSchemeCode,
                scheme, benefitId, familyId, status, date(mapped, "startDate"), decimal(mapped, "entitlement.amount"),
                decimal(mapped, "entitlement.quantity"), unit, frequency, disbursement, Map.copyOf(extensions));
        return Optional.of(new BenefitMapping(version, catalogue.version(), Optional.of(benefit), List.copyOf(warnings)));
    }

    private static boolean isUnmapped(String value, MappingRule rule, LoadedStatePack pack) {
        CrosswalkDefinition crosswalk = rule.crosswalk() == null ? null : pack.crosswalks().get(rule.crosswalk());
        return crosswalk != null && crosswalk.values().get(value) == null;
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

    private static BigDecimal decimal(Map<String, Object> mapped, String target) {
        Object value = mapped.get(target);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number for " + target + ": " + value, e);
        }
    }

    private static Object convert(String value, MappingRule rule) {
        if (rule.conversion() == null) {
            return value;
        }
        if (!rule.conversion().startsWith("date:")) {
            throw new IllegalArgumentException("Unsupported Benefit conversion: " + rule.conversion());
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
