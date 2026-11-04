package gov.uddp.core.held;

import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import gov.uddp.core.validation.ReasonCodes;
import java.util.List;
import java.util.Map;

/**
 * Where in the State Pack a held record's or rejected row's cause is fixed: the kind of pack part and its key (a rule
 * id, a source column or a crosswalk name). Derived from the reason code alone, so it is the same for every state;
 * the pack only names the exact column or crosswalk behind a canonical field.
 *
 * <ul>
 *   <li>{@code RULE_FAILED:<id>} - RULE, the rule id;</li>
 *   <li>UNMAPPED_CODE - CROSSWALK, the crosswalk of the field's mapping rule;</li>
 *   <li>UNREADABLE_DATE, MISSING_NAME, ID_FORMAT - MAPPING, the source column mapped to the field;</li>
 *   <li>LOW_CONFIDENCE - SHAPING, the field whose parser was unsure;</li>
 *   <li>anything else (a duplicate key, OTHER) - PACK_SOURCE, the source.</li>
 * </ul>
 */
public record FixTarget(String kind, String key) {
    public static final String MAPPING = "MAPPING";
    public static final String CROSSWALK = "CROSSWALK";
    public static final String RULE = "RULE";
    public static final String SHAPING = "SHAPING";
    public static final String PACK_SOURCE = "PACK_SOURCE";

    /** The target without a pack: the key is the rule id or the canonical field as reported. */
    public static FixTarget of(String code, String field) {
        return of(code, field, Map.of(), null, null);
    }

    /**
     * @param context the raise point's context ({@link ReasonCodes#contextOf}): {@code sourceField}, {@code crosswalk}
     * @param pack the pack the row was mapped with, or null
     * @param sourceId the pack source of the row, or null
     */
    public static FixTarget of(String code, String field, Map<String, String> context, LoadedStatePack pack,
                               String sourceId) {
        String reason = code == null ? ReasonCodes.OTHER : code;
        if (reason.startsWith(ReasonCodes.RULE_FAILED_PREFIX)) {
            return new FixTarget(RULE, reason.substring(ReasonCodes.RULE_FAILED_PREFIX.length()));
        }
        MappingRule rule = rule(pack, sourceId, field);
        return switch (reason) {
            case ReasonCodes.UNMAPPED_CODE -> new FixTarget(CROSSWALK, first(context.get("crosswalk"),
                    rule == null ? null : rule.crosswalk(), field));
            case ReasonCodes.UNREADABLE_DATE, ReasonCodes.MISSING_NAME, ReasonCodes.ID_FORMAT ->
                    new FixTarget(MAPPING, first(context.get("sourceField"), rule == null ? null : rule.sourceField(),
                            field));
            case ReasonCodes.LOW_CONFIDENCE -> new FixTarget(SHAPING, field);
            default -> new FixTarget(PACK_SOURCE, sourceId);
        };
    }

    /** The mapping rule of the source whose target (or source column) is the field. */
    private static MappingRule rule(LoadedStatePack pack, String sourceId, String field) {
        if (pack == null || field == null) {
            return null;
        }
        for (var mapping : pack.mappings()) {
            if (sourceId != null && !sourceId.equals(mapping.sourceId())) {
                continue;
            }
            for (List<MappingRule> rules : mapping.entities() == null ? List.<List<MappingRule>>of()
                    : mapping.entities().values()) {
                for (MappingRule rule : rules) {
                    if (field.equals(rule.target()) || field.equals(rule.sourceField())) {
                        return rule;
                    }
                }
            }
        }
        return null;
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
