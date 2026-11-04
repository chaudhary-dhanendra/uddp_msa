package gov.uddp.core.read.statepack;

import java.time.OffsetDateTime;
import java.util.List;

/** Read models of a State Pack, independent of where packs are stored. */
public final class StatePackModels {
    private StatePackModels() {
    }

    /**
     * @param manifestStatus  the {@code status} written in the pack's manifest
     * @param lifecycleStatus the platform lifecycle status of this version (DRAFT, VALIDATED, ACTIVE, RETIRED), or
     *                        UNREGISTERED when the version was never registered for onboarding
     */
    public record StatePackListItem(String packId, String stateCode, String version, String commonModelVersion,
                                   String manifestStatus, String lifecycleStatus, int sourceCount,
                                   List<String> sourceIds, List<String> entities) {
    }

    public record StatePackView(String packId, String stateCode, String version, String commonModelVersion,
                                  String manifestStatus, String lifecycleStatus, List<PackSourceView> sources,
                                  List<MappingView> mappings, List<CrosswalkView> crosswalks,
                                  List<ValidationRuleView> validationRules, List<MatchingView> matching,
                                  List<SurvivorshipView> survivorship, List<PackVersionView> versions,
                                  List<String> extensions) {
    }

    public record PackSourceView(String sourceId, String sourceType, String hopTemplate, String connectionRef,
                             String location, String recordIdField, List<String> recordKeyFields,
                             String sourceSchemaVersion) {
    }

    public record MappingView(String mappingVersion, String sourceId, String entity, List<MappingRuleView> rules) {
    }

    public record MappingRuleView(String sourceField, String target, String conversion, String crosswalk,
                                  boolean required) {
    }

    public record CrosswalkView(String id, List<CrosswalkEntry> entries) {
    }

    public record CrosswalkEntry(String sourceValue, String canonicalValue) {
    }

    /** {@code type} is null for M7-style rules (required / allowedValues). */
    public record ValidationRuleView(String entity, String id, String type, String field, List<String> fields,
                                     boolean required, List<String> allowedValues, String pattern, String min,
                                     String max, Integer minAgeYears, Integer maxAgeYears, String referenceSet,
                                     String operator, String severity) {
    }

    public record MatchingView(String entity, List<String> fields, Double autoMatch, Double possibleMatch) {
    }

    public record SurvivorshipView(String entity, List<FieldPriority> fieldPriority) {
    }

    public record FieldPriority(String field, List<String> sources) {
    }

    /** One registered version of the pack in the platform lifecycle. */
    public record PackVersionView(String version, String lifecycleStatus, boolean loaded, OffsetDateTime registeredAt,
                                  OffsetDateTime validatedAt, OffsetDateTime activatedAt, OffsetDateTime retiredAt) {
    }
}
