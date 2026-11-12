package gov.uddp.core.statepack;

import java.util.List;
import java.util.Map;

public final class StatePackAssets {
    private StatePackAssets() { }
    /**
     * {@code recordKeyFields} (optional, M11) are the source fields that identify one source record across
     * re-deliveries; without them the key is {@code recordIdField}. A {@code database} source (Onboarding Studio)
     * reads {@code table} or a read-only {@code query} through the named connection {@code connectionRef}, whose
     * credentials live only in the encrypted local settings store, never in the pack. A database source may name a
     * {@code changeColumn} (an update timestamp or an ever-increasing id): each delivery then reads only the rows past
     * the last value loaded (delta load).
     */
    public record SourceDefinition(String sourceId, String sourceType, String hopTemplate, String connectionRef,
                                   String location, String recordIdField, String sourceSchemaVersion,
                                   List<String> recordKeyFields, String table, String query,
                                   String format, Map<String, Object> sheets,
                                   List<Map<String, Object>> layout, List<Map<String, Object>> parse,
                                   String asOfDate, String changeColumn) {
        /** Source-compatible v1.1 constructor: no {@code changeColumn}. */
        public SourceDefinition(String sourceId, String sourceType, String hopTemplate, String connectionRef,
                                String location, String recordIdField, String sourceSchemaVersion,
                                List<String> recordKeyFields, String table, String query,
                                String format, Map<String, Object> sheets,
                                List<Map<String, Object>> layout, List<Map<String, Object>> parse,
                                String asOfDate) {
            this(sourceId, sourceType, hopTemplate, connectionRef, location, recordIdField, sourceSchemaVersion,
                    recordKeyFields, table, query, format, sheets, layout, parse, asOfDate, null);
        }

        /** Source-compatible pre-v1.1 constructor: no {@code asOfDate}. */
        public SourceDefinition(String sourceId, String sourceType, String hopTemplate, String connectionRef,
                                String location, String recordIdField, String sourceSchemaVersion,
                                List<String> recordKeyFields, String table, String query,
                                String format, Map<String, Object> sheets,
                                List<Map<String, Object>> layout, List<Map<String, Object>> parse) {
            this(sourceId, sourceType, hopTemplate, connectionRef, location, recordIdField, sourceSchemaVersion,
                    recordKeyFields, table, query, format, sheets, layout, parse, null);
        }

        /** Source-compatible Studio constructor: no shaping (plain CSV passthrough). */
        public SourceDefinition(String sourceId, String sourceType, String hopTemplate, String connectionRef,
                                String location, String recordIdField, String sourceSchemaVersion,
                                List<String> recordKeyFields, String table, String query) {
            this(sourceId, sourceType, hopTemplate, connectionRef, location, recordIdField, sourceSchemaVersion,
                    recordKeyFields, table, query, null, null, null, null);
        }

        /** Source-compatible M11 constructor (file sources). */
        public SourceDefinition(String sourceId, String sourceType, String hopTemplate, String connectionRef,
                                String location, String recordIdField, String sourceSchemaVersion,
                                List<String> recordKeyFields) {
            this(sourceId, sourceType, hopTemplate, connectionRef, location, recordIdField, sourceSchemaVersion,
                    recordKeyFields, null, null);
        }

        /** Source-compatible pre-M11 constructor. */
        public SourceDefinition(String sourceId, String sourceType, String hopTemplate, String connectionRef,
                                String location, String recordIdField, String sourceSchemaVersion) {
            this(sourceId, sourceType, hopTemplate, connectionRef, location, recordIdField, sourceSchemaVersion, null);
        }

        /** {@code csv} unless the source declares another format. */
        public String formatOrDefault() {
            return format == null || format.isBlank() ? "csv" : format;
        }

        /** Whether the shaping layer does more than pass the CSV fields through. */
        public boolean shaped() {
            return (layout != null && !layout.isEmpty()) || (parse != null && !parse.isEmpty());
        }

        public boolean database() {
            return "database".equals(sourceType);
        }

        public List<String> keyFields() {
            return recordKeyFields == null || recordKeyFields.isEmpty() ? List.of(recordIdField) : recordKeyFields;
        }
    }
    public record MappingDefinition(String mappingVersion, String sourceId, Map<String, List<MappingRule>> entities) { }
    /** {@code onInvalid} (identifier targets only): {@code reject} (default) fails the record, {@code flag} keeps it. */
    public record MappingRule(String sourceField, String target, String conversion, String crosswalk, Boolean required,
                              String onInvalid) {
        public MappingRule {
            if (onInvalid != null && !onInvalid.equalsIgnoreCase("reject") && !onInvalid.equalsIgnoreCase("flag")) {
                throw new IllegalArgumentException("Mapping rule for " + sourceField + ": onInvalid must be reject or flag");
            }
        }

        public MappingRule(String sourceField, String target, String conversion, String crosswalk, Boolean required) {
            this(sourceField, target, conversion, crosswalk, required, null);
        }

        public boolean flagsInvalid() {
            return "flag".equalsIgnoreCase(onInvalid);
        }
    }
    public record CrosswalkDefinition(String id, Map<String, String> values) { }
    public record ValidationDefinition(String entity, List<ValidationRule> rules) { }
    /** M7 fields remain optional and retain their original semantics when type is absent. */
    public record ValidationRule(String id, String type, String field, List<String> fields, Boolean required,
                                 List<String> allowedValues, String pattern, String min, String max,
                                 Integer minAgeYears, Integer maxAgeYears, String referenceSet,
                                 String operator, String severity) {
        /** Source-compatible M7 constructor: field, required, allowedValues, severity. */
        public ValidationRule(String field, Boolean required, List<String> allowedValues, String severity) {
            this(null, null, field, null, required, allowedValues, null, null, null,
                    null, null, null, null, severity);
        }
    }
    public record MatchingDefinition(String entity, List<String> fields, Thresholds thresholds) { }
    public record Thresholds(Double autoMatch, Double possibleMatch) { }
    public record SurvivorshipDefinition(String entity, Map<String, List<String>> fieldPriority) { }

    /**
     * A pack's state fields file ({@code extensions/resident.yaml}, listed under the manifest's {@code extensions}):
     * source values with no Common Model field that this pack still wants typed, checked and, when {@code golden},
     * carried to the Golden Resident under {@code stateFields.<stateCode>.<key>}.
     */
    public record StateFieldsDefinition(String entity, List<StateField> fields) { }

    /**
     * One declared state field. A mapping rule stores it with {@code target: extensions.<key>}; {@code type} is
     * {@code text}, {@code number}, {@code integer}, {@code boolean} or {@code date} (ISO); {@code golden} carries
     * the value to the Golden Resident.
     */
    public record StateField(String key, String label, String type, Boolean golden) {
        public static final java.util.Set<String> TYPES = java.util.Set.of("text", "number", "integer", "boolean", "date");
        public static final String KEY_PATTERN = "[a-z][A-Za-z0-9_]{0,62}";

        public String typeOrDefault() {
            return type == null || type.isBlank() ? "text" : type;
        }

        public boolean toGolden() {
            return Boolean.TRUE.equals(golden);
        }

        public String labelOrKey() {
            return label == null || label.isBlank() ? key : label;
        }
    }
}
