package gov.uddp.core.mapping;

import gov.uddp.core.model.Address;
import gov.uddp.core.model.Gender;
import gov.uddp.core.model.RecordMetadata;
import gov.uddp.core.model.Resident;
import gov.uddp.core.model.SourceReference;
import gov.uddp.core.identity.IdentifierProtection;
import gov.uddp.core.identity.ProtectedIdentifier;
import gov.uddp.core.geography.CanonicalGeographyResolver;
import gov.uddp.core.privacy.NationalIdProtector;
import gov.uddp.core.statepack.StatePackAssets.MappingDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.shaping.api.Provenance;
import gov.uddp.shaping.api.RecordShaper;
import gov.uddp.shaping.api.TidyRecord;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Applies declarative Resident mappings; State Packs contain all source-schema knowledge. Unmapped source fields are
 * kept as Resident extensions, except fields that only the mapping's {@code Benefit} rules read (M15): those are benefit
 * data, observed by {@link BenefitMappingEngine}, and a monthly payment must not become a new Resident version.
 * Identifier columns ({@code identifiers.<TYPE>}, or the P1 alias {@code nationalIds.<TYPE>}) are validated, normalised
 * and protected by their registry type (Track E2) and kept only in the {@link IdentifierProtection#EXTENSION} extension.
 */
@Service
public class ResidentMappingEngine {
    /** Mapping target prefix of an identifier column: {@code identifiers.RATION_CARD} (Track E2). */
    public static final String IDENTIFIER_PREFIX = "identifiers.";
    /** The P1 Part 5b alias of {@link #IDENTIFIER_PREFIX}: {@code nationalIds.AADHAAR} keeps working. */
    public static final String NATIONAL_ID_PREFIX = "nationalIds.";
    /** A rule {@code conversion} of {@code issuer:<CODE>} names the issuer of an ISSUER-scoped identifier. */
    public static final String ISSUER_CONVERSION = "issuer:";
    /** Target suffix marking an old-scheme ID of the same subject: {@code identifiers.AADHAAR.alias}. */
    public static final String ALIAS_SUFFIX = ".alias";
    /** Aliases by type code; kept apart from {@link IdentifierProtection#EXTENSION}, which holds one current ID per type. */
    public static final String ALIAS_EXTENSION = "uddp.identifierAliases";
    /** Per-target confidence of parsed values, read by survivorship: {@code {target: {confidence, origin}}}. */
    public static final String PROVENANCE_EXTENSION = "uddp.fieldProvenance";
    /** A relation rule's {@code conversion} of {@code relation:<TYPE>} fixes the relation type of its group. */
    public static final String RELATION_CONVERSION = "relation:";
    private static final String RELATION_PREFIX = "relations[].";
    private static final Set<String> RELATION_TYPES = Set.of("FATHER", "MOTHER", "HUSBAND", "WIFE", "GUARDIAN");

    /** The {@code status} of an identifier kept under {@code onInvalid: flag}; it never carries a value. */
    public static final String INVALID_STATUS = "INVALID";

    private final RecordShaper shaping;
    private IdentifierProtection identifiers;
    private CanonicalGeographyResolver geography;

    public ResidentMappingEngine(RawCsvRecordParser parser) {
        this(RecordShaper.passthrough(parser));
    }

    @Autowired
    public ResidentMappingEngine(RecordShaper shaping) {
        this.shaping = shaping;
    }

    public RecordShaper shaping() {
        return shaping;
    }

    /** P1 wiring: protection over the platform seed types with this keyed-hash key. */
    public void setNationalIds(NationalIdProtector nationalIds) {
        this.identifiers = nationalIds == null ? null : IdentifierProtection.withSeed(nationalIds);
    }

    @Autowired(required = false)
    public void setIdentifiers(IdentifierProtection identifiers) {
        this.identifiers = identifiers;
    }

    public IdentifierProtection identifiers() {
        return identifiers;
    }

    /** Optional F4 resolver; old fixture/unit mappings retain their pre-LGD behaviour until it is wired. */
    @Autowired(required = false)
    public void setGeographyService(gov.uddp.core.geography.GeographyService service) {
        this.geography = service == null ? null : new CanonicalGeographyResolver(service);
    }

    /** The identifier type a mapping target names ({@code identifiers.X} or {@code nationalIds.X}), else null. */
    public static String identifierType(String target) {
        if (target == null) {
            return null;
        }
        String type = null;
        if (target.startsWith(IDENTIFIER_PREFIX)) {
            type = target.substring(IDENTIFIER_PREFIX.length());
        } else if (target.startsWith(NATIONAL_ID_PREFIX)) {
            type = target.substring(NATIONAL_ID_PREFIX.length());
        }
        return type != null && type.endsWith(ALIAS_SUFFIX)
                ? type.substring(0, type.length() - ALIAS_SUFFIX.length()) : type;
    }

    /** True for an {@code identifiers.<TYPE>.alias} target. */
    public static boolean isAlias(String target) {
        return identifierType(target) != null && target.endsWith(ALIAS_SUFFIX);
    }

    /** The issuer of an identifier rule's values: {@code conversion: issuer:<CODE>}, else the record's state code. */
    public static String issuer(MappingRule rule, String stateCode) {
        return rule.conversion() != null && rule.conversion().startsWith(ISSUER_CONVERSION)
                ? rule.conversion().substring(ISSUER_CONVERSION.length()) : stateCode;
    }

    public Resident map(RawRecord rawRecord, LoadedStatePack pack, String sourceId) {
        SourceDefinition source = pack.sources().stream()
                .filter(item -> item.sourceId().equals(sourceId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("State Pack has no source: " + sourceId));
        MappingDefinition mapping = pack.mappings().stream()
                .filter(item -> item.sourceId().equals(sourceId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("State Pack has no mapping for source: " + sourceId));
        List<MappingRule> rules = mapping.entities().get("Resident");
        if (rules == null) {
            throw new IllegalArgumentException("Mapping has no Resident rules");
        }

        TidyRecord tidy = shaping.shape(rawRecord, pack, sourceId);
        Map<String, Object> values = tidy.fields();
        Set<String> mappedFields = new LinkedHashSet<>(benefitOnlyFields(mapping));
        Map<String, Object> extensions = new LinkedHashMap<>();
        Map<String, Map<String, String>> suppliedGeography = new LinkedHashMap<>();
        MutableResident resident = new MutableResident();
        for (MappingRule rule : rules) {
            Object sourceValue = values.get(rule.sourceField());
            if (sourceValue == null || sourceValue.toString().isBlank()) {
                if (Boolean.TRUE.equals(rule.required())) {
                    String message = "Required source field is blank: " + rule.sourceField();
                    throw "name".equals(rule.target())
                            ? new gov.uddp.core.validation.ReasonCodes.ReasonedException(
                                    gov.uddp.core.validation.ReasonCodes.MISSING_NAME, message)
                                    .with("sourceField", rule.sourceField()).with("target", rule.target())
                            : new IllegalArgumentException(message);
                }
                continue;
            }
            mappedFields.add(rule.sourceField());
            String identifierType = identifierType(rule.target());
            if (identifierType != null) {
                protectIdentifier(extensions, rule, identifierType, issuer(rule, rawRecord.stateCode()),
                        sourceValue.toString());
                continue;
            }
            if (geographyTarget(rule.target())) {
                // For geography.* targets the source-owned crosswalk is one rung of the resolution ladder: a value it
                // does not list goes on to name matching instead of failing the record. address.districtLGD keeps its
                // strict pre-Track F crosswalk.
                String value = sourceValue.toString();
                boolean ladder = rule.target().startsWith("geography.");
                String crosswalked = !ladder || rule.crosswalk() == null || pack.crosswalks().get(rule.crosswalk()) == null
                        ? null : pack.crosswalks().get(rule.crosswalk()).values().get(value);
                Object transformed = !ladder ? transform(value, rule, pack) : crosswalked != null ? crosswalked
                        : transform(value, new MappingRule(rule.sourceField(), rule.target(), rule.conversion(), null,
                                rule.required()), pack);
                String target = crosswalked != null && rule.target().endsWith(".name")
                        ? rule.target().substring(0, rule.target().length() - "name".length()) + "lgd"
                        : rule.target();
                collectGeography(suppliedGeography, target, value, transformed.toString());
                continue;
            }
            boolean relation = rule.target().startsWith(RELATION_PREFIX);
            Object transformed = relation && rule.crosswalk() == null ? sourceValue.toString().trim() : transform(sourceValue.toString(), rule, pack);
            Provenance provenance = tidy.provenance() == null ? null : tidy.provenance().get(rule.sourceField());
            Double confidence = provenance == null ? null : provenance.confidence();
            if (!relation) {
                recordConfidence(extensions, rule.target(), confidence);
            }
            apply(resident, extensions, rule, transformed, confidence);
        }
        applyGeography(resident, extensions, suppliedGeography);
        finishV11(resident, source, rawRecord);
        values.forEach((field, value) -> {
            // A parser output with no value (an address it could not resolve) is a null field: nothing to keep.
            if (!mappedFields.contains(field) && value != null) {
                extensions.put(field, value);
            }
        });
        extensions.put("uddp.sourceEntity", rawRecord.sourceEntity());
        extensions.put("uddp.rawRecordId", rawRecord.rawRecordId().toString());

        String recordId = stringValue(values.get(source.recordIdField()), source.recordIdField());
        return new Resident(null, requiredName(resident.name), resident.dateOfBirth, resident.gender, resident.mobile,
                new Address(pack.manifest().stateCode(), null, resident.districtLgd, null, null, null),
                List.of(new SourceReference(rawRecord.stateCode(), rawRecord.sourceSystem(), recordId)),
                Map.copyOf(extensions),
                new RecordMetadata(rawRecord.stateCode(), rawRecord.sourceSystem(), recordId,
                        rawRecord.batchId().toString(), rawRecord.ingestedAt(), pack.manifest().version(),
                        mapping.mappingVersion(), rawRecord.rawRecordId().toString()),
                resident.nameNormalised, resident.relationList(), resident.age(), resident.birthYear,
                resident.dobPrecision, null, resident.socialCategory,
                resident.freeText == null ? null : new Resident.Geography(resident.freeText));
    }

    /** Age date, birth year and DOB precision, derived once all rules have run. */
    private static void finishV11(MutableResident resident, SourceDefinition source, RawRecord rawRecord) {
        if (resident.ageYears != null) {
            LocalDate asOf = source.asOfDate() != null && !source.asOfDate().isBlank()
                    ? LocalDate.parse(source.asOfDate().trim()) : rawRecord.ingestedAt().toLocalDate();
            resident.ageAsOf = asOf;
            resident.birthYear = asOf.getYear() - resident.ageYears;
        }
        if (resident.dateOfBirth != null && resident.dobPrecision == null && resident.dateOfBirth.getMonthValue() == 1
                && resident.dateOfBirth.getDayOfMonth() == 1) {
            resident.dobPrecision = "YEAR";
            resident.birthYear = resident.dateOfBirth.getYear();
        }
    }

    @SuppressWarnings("unchecked")
    private static void recordConfidence(Map<String, Object> extensions, String target, Double confidence) {
        if (confidence == null) {
            return;
        }
        Map<String, Object> entries = (Map<String, Object>) extensions.computeIfAbsent(PROVENANCE_EXTENSION,
                key -> new LinkedHashMap<String, Object>());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("confidence", confidence);
        entry.put("origin", "PARSED");
        entries.put(target, entry);
    }

    /** Source fields read by Benefit, Application or Asset rules and by no rule of another entity. */
    private static Set<String> benefitOnlyFields(MappingDefinition mapping) {
        Set<String> fields = new LinkedHashSet<>();
        Set<String> sideEntities = Set.of(BenefitMappingEngine.ENTITY, ApplicationMappingEngine.ENTITY,
                AssetMappingEngine.ENTITY);
        sideEntities.forEach(entity -> mapping.entities().getOrDefault(entity, List.of())
                .forEach(rule -> fields.add(rule.sourceField())));
        mapping.entities().forEach((entity, rules) -> {
            if (!sideEntities.contains(entity)) {
                rules.forEach(rule -> fields.remove(rule.sourceField()));
            }
        });
        return fields;
    }

    private Object transform(String value, MappingRule rule, LoadedStatePack pack) {
        String transformed = value;
        if (rule.crosswalk() != null) {
            Map<String, String> values = pack.crosswalks().get(rule.crosswalk()).values();
            transformed = values.get(value);
            if (transformed == null) {
                throw new gov.uddp.core.validation.ReasonCodes.ReasonedException(
                        gov.uddp.core.validation.ReasonCodes.UNMAPPED_CODE,
                        "No crosswalk value for " + rule.crosswalk() + ": " + value)
                        .with("sourceField", rule.sourceField()).with("target", rule.target())
                        .with("crosswalk", rule.crosswalk()).with("value", value);
            }
        }
        if (rule.conversion() != null && rule.conversion().startsWith("date:")) {
            String configuredFormat = rule.conversion().substring("date:".length());
            try {
                return parseDate(transformed, configuredFormat);
            } catch (DateTimeParseException error) {
                throw new gov.uddp.core.validation.ReasonCodes.ReasonedException(
                        gov.uddp.core.validation.ReasonCodes.UNREADABLE_DATE,
                        "Invalid date for configured format " + configuredFormat + ": " + value, error)
                        .with("sourceField", rule.sourceField()).with("target", rule.target())
                        .with("format", configuredFormat).with("value", value);
            }
        }
        return transformed;
    }

    private void apply(MutableResident resident, Map<String, Object> extensions, MappingRule rule, Object value,
                       Double confidence) {
        String target = rule.target();
        if (target.startsWith(RELATION_PREFIX)) {
            resident.relation(target.substring(RELATION_PREFIX.length()), rule, value.toString(), confidence);
            return;
        }
        switch (target) {
            case "nameNormalised" -> resident.nameNormalised = value.toString();
            case "age.years" -> {
                try {
                    resident.ageYears = Integer.parseInt(value.toString().trim());
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("Invalid age for source field " + rule.sourceField(), error);
                }
            }
            case "socialCategory" -> resident.socialCategory = value.toString();
            case "geography.freeText" -> resident.freeText = value.toString();
            case "name" -> resident.name = value.toString();
            case "dateOfBirth" -> {
                if (value instanceof LocalDate date) {
                    resident.dateOfBirth = date;
                } else {
                    // No date: conversion on the rule: the value came from a parser (date-text, a calendar plug-in)
                    // as an ISO date of day, month or year precision.
                    IsoDate iso = isoDate(value.toString(), rule);
                    resident.dateOfBirth = iso.date();
                    if (!"DAY".equals(iso.precision())) {
                        resident.dobPrecision = iso.precision();
                        resident.birthYear = iso.date().getYear();
                    }
                }
            }
            case "gender" -> resident.gender = Gender.valueOf(value.toString());
            case "mobile" -> resident.mobile = value.toString();
            case "address.districtLGD" -> resident.districtLgd = value.toString();
            default -> {
                if (!target.startsWith("extensions.") || target.length() == "extensions.".length()) {
                    throw new IllegalArgumentException("Unsupported Resident mapping target: " + target);
                }
                extensions.put(target.substring("extensions.".length()), value);
            }
        }
    }

    private static boolean geographyTarget(String target) {
        return target != null && !"geography.freeText".equals(target) && (target.startsWith("geography.") || "address.districtLGD".equals(target));
    }

    private static void collectGeography(Map<String, Map<String, String>> supplied, String target, String raw,
                                         String transformed) {
        if ("address.districtLGD".equals(target)) {
            supplied.computeIfAbsent("district", ignored -> new LinkedHashMap<>()).put("lgd", transformed);
            supplied.get("district").put("raw", raw);
            return;
        }
        String[] parts = target.split("\\.");
        boolean levelTarget = parts.length == 3 && ("lgd".equals(parts[2]) || "name".equals(parts[2]));
        boolean pincodeTarget = parts.length == 2 && "pincode".equals(parts[1]);
        if (!levelTarget && !pincodeTarget) {
            throw new IllegalArgumentException("Unsupported geography mapping target: " + target);
        }
        if (parts.length == 2) {
            supplied.computeIfAbsent("pincode", ignored -> new LinkedHashMap<>()).put("value", transformed);
            supplied.get("pincode").put("raw", raw);
        } else {
            supplied.computeIfAbsent(parts[1], ignored -> new LinkedHashMap<>()).put(parts[2], transformed);
            supplied.get(parts[1]).put("raw", raw);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyGeography(MutableResident resident, Map<String, Object> extensions,
                                Map<String, Map<String, String>> supplied) {
        if (supplied.isEmpty()) return;
        if (geography == null) {
            Map<String, String> district = supplied.get("district");
            if (district != null) resident.districtLgd = district.get("lgd");
            return;
        }
        Map<String, Object> resolved = geography.resolve(supplied);
        extensions.put(CanonicalGeographyResolver.EXTENSION, resolved);
        if (!geography.masterLoaded()) {
            // Before the first LGD import a mapped district code passes through exactly as before Track F.
            Map<String, String> district = supplied.get("district");
            if (district != null) resident.districtLgd = district.get("lgd");
            return;
        }
        Object district = resolved.get("district");
        if (district instanceof Map<?, ?> entry && entry.get("lgd") != null
                && !"UNKNOWN".equals(entry.get("status"))) {
            resident.districtLgd = entry.get("lgd").toString();
        }
    }

    /**
     * An identifier is kept only in its protected form (keyed hash, vault reference or, for PLAIN types, the normalised
     * value) in the {@link IdentifierProtection#EXTENSION} extension; the clear value never reaches the canonical
     * record, and an error names the source field and the rule, never the value.
     */
    @SuppressWarnings("unchecked")
    private void protectIdentifier(Map<String, Object> extensions, MappingRule rule, String type, String issuer,
                                   String value) {
        if (identifiers == null || type.isBlank()) {
            throw new IllegalArgumentException("Identifier protection is not configured for target " + rule.target());
        }
        if (identifiers.registry().find(type).isEmpty()) {
            throw new IllegalArgumentException("Source field " + rule.sourceField() + " maps to " + rule.target()
                    + ", which is not a registered identifier type");
        }
        String code = identifiers.registry().require(type).code();
        Map<String, Object> entry;
        try {
            entry = identifiers.protect(type, issuer, value).extensionEntry();
        } catch (IllegalArgumentException rejected) {
            if (!rule.flagsInvalid()) {
                throw new gov.uddp.core.validation.ReasonCodes.ReasonedException(
                        gov.uddp.core.validation.ReasonCodes.ID_FORMAT,
                        "Source field " + rule.sourceField() + ": " + rejected.getMessage())
                        .with("sourceField", rule.sourceField()).with("target", rule.target())
                        .with("identifierType", type);
            }
            // The reason names the rule, never the value; the value itself is dropped.
            entry = new LinkedHashMap<>();
            entry.put("type", code);
            entry.put("status", INVALID_STATUS);
            entry.put("reason", rejected.getMessage());
        }
        if (isAlias(rule.target())) {
            Map<String, Object> aliases = (Map<String, Object>) extensions.computeIfAbsent(ALIAS_EXTENSION,
                    key -> new LinkedHashMap<String, Object>());
            ((List<Object>) aliases.computeIfAbsent(code, key -> new java.util.ArrayList<Object>())).add(entry);
            return;
        }
        Map<String, Object> ids = (Map<String, Object>) extensions.computeIfAbsent(IdentifierProtection.EXTENSION,
                key -> new LinkedHashMap<String, Object>());
        if (INVALID_STATUS.equals(entry.get("status")) && ids.containsKey(code)) {
            return;
        }
        ids.put(code, entry);
    }

    /**
     * Parses a date with a configured format such as {@code DD-MM-YYYY} or {@code D-MMM-YY;pivot=1930}. Letters are
     * case-insensitive: D/DD day, M/MM month number, MMM/MMMM English month name, YYYY year, YY two-digit year.
     * A two-digit year resolves within {@code pivot .. pivot+99} (so {@code pivot=1930}: YY below 30 is 20YY, otherwise
     * 19YY); without a pivot it is 20YY.
     */
    /** A date written as ISO yyyy-MM-dd, yyyy-MM or yyyy, with its precision (DAY, MONTH or YEAR). */
    record IsoDate(LocalDate date, String precision) {
    }

    private static final java.util.regex.Pattern ISO_DATE =
            java.util.regex.Pattern.compile("(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?");

    /**
     * Reads a date of birth that has no {@code date:} conversion: the ISO date a Tier-2/Tier-3 parser outputs
     * ("1958-03-12", "1958-03" or "1958"). A month or a year alone becomes the first day of it, with its precision kept.
     * Anything else is UNREADABLE_DATE (the rule needs a conversion).
     */
    static IsoDate isoDate(String text, MappingRule rule) {
        java.util.regex.Matcher m = ISO_DATE.matcher(text == null ? "" : text.trim());
        try {
            if (m.matches()) {
                int year = Integer.parseInt(m.group(1));
                if (m.group(2) == null) {
                    return new IsoDate(LocalDate.of(year, 1, 1), "YEAR");
                }
                int month = Integer.parseInt(m.group(2));
                if (m.group(3) == null) {
                    return new IsoDate(LocalDate.of(year, month, 1), "MONTH");
                }
                return new IsoDate(LocalDate.of(year, month, Integer.parseInt(m.group(3))), "DAY");
            }
        } catch (java.time.DateTimeException invalid) {
            // falls through to the reasoned error
        }
        throw new gov.uddp.core.validation.ReasonCodes.ReasonedException(
                gov.uddp.core.validation.ReasonCodes.UNREADABLE_DATE,
                "Date of birth is not an ISO date (yyyy-MM-dd, yyyy-MM or yyyy) and the rule has no date: conversion")
                .with("sourceField", rule.sourceField()).with("target", rule.target()).with("value", text);
    }

    static LocalDate parseDate(String text, String configured) {
        return LocalDate.parse(text.trim(), dateFormatter(configured));
    }

    static DateTimeFormatter dateFormatter(String configured) {
        String format = configured;
        int pivot = 2000;
        int options = configured.indexOf(';');
        if (options >= 0) {
            format = configured.substring(0, options);
            for (String option : configured.substring(options + 1).split(";")) {
                String[] pair = option.split("=", 2);
                if (pair.length == 2 && "pivot".equals(pair[0].trim())) {
                    pivot = Integer.parseInt(pair[1].trim());
                }
            }
        }
        DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder().parseCaseInsensitive();
        int index = 0;
        while (index < format.length()) {
            char letter = Character.toLowerCase(format.charAt(index));
            int end = index;
            while (end < format.length() && Character.toLowerCase(format.charAt(end)) == letter) {
                end++;
            }
            int length = end - index;
            switch (letter) {
                case 'd' -> builder.appendPattern(length == 1 ? "d" : "dd");
                case 'm' -> builder.appendPattern("M".repeat(Math.min(length, 4)));
                case 'y' -> {
                    if (length == 2) {
                        builder.appendValueReduced(ChronoField.YEAR, 2, 2, pivot);
                    } else {
                        builder.appendPattern("uuuu");
                    }
                }
                default -> builder.appendLiteral(format.substring(index, end));
            }
            index = end;
        }
        return builder.toFormatter(Locale.ENGLISH);
    }

    private static String requiredName(String value) {
        if (value == null || value.isBlank()) {
            throw new gov.uddp.core.validation.ReasonCodes.ReasonedException(
                    gov.uddp.core.validation.ReasonCodes.MISSING_NAME, "Resident name is required");
        }
        return value;
    }

    private static String stringValue(Object value, String field) {
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Record identifier is blank: " + field);
        }
        return value.toString();
    }

    private static final class MutableResident {
        String name;
        LocalDate dateOfBirth;
        Gender gender;
        String mobile;
        String districtLgd;
        String nameNormalised;
        Integer ageYears;
        LocalDate ageAsOf;
        Integer birthYear;
        String dobPrecision;
        String socialCategory;
        String freeText;
        final List<Relation> relations = new ArrayList<>();

        /** A rule whose property is already set on the open relation starts the next relation. */
        void relation(String property, MappingRule rule, String value, Double confidence) {
            Relation open = relations.isEmpty() ? null : relations.get(relations.size() - 1);
            if (open == null || open.has(property)) {
                open = new Relation();
                relations.add(open);
            }
            if (rule.conversion() != null && rule.conversion().startsWith(RELATION_CONVERSION)) {
                open.type = rule.conversion().substring(RELATION_CONVERSION.length()).toUpperCase(Locale.ROOT);
            }
            switch (property) {
                case "type" -> open.type = value.toUpperCase(Locale.ROOT);
                case "name" -> open.name = value;
                case "nameNormalised" -> open.nameNormalised = value;
                case "deceased" -> open.deceased = Boolean.parseBoolean(value);
                default -> throw new IllegalArgumentException("Unsupported Resident mapping target: relations[]."
                        + property);
            }
            if (confidence != null) {
                open.confidence = confidence;
            }
            if (open.type != null && !RELATION_TYPES.contains(open.type)) {
                throw new IllegalArgumentException("Unknown relation type for source field " + rule.sourceField());
            }
        }

        Resident.Age age() {
            return ageYears == null ? null : new Resident.Age(ageYears, ageAsOf);
        }

        List<Resident.Relation> relationList() {
            return relations.isEmpty() ? null : relations.stream().map(item -> new Resident.Relation(item.type,
                    item.name, item.nameNormalised, item.deceased, item.confidence)).toList();
        }
    }

    private static final class Relation {
        String type;
        String name;
        String nameNormalised;
        Boolean deceased;
        Double confidence;

        boolean has(String property) {
            return switch (property) {
                case "type" -> type != null;
                case "name" -> name != null;
                case "nameNormalised" -> nameNormalised != null;
                default -> deceased != null;
            };
        }
    }
}
