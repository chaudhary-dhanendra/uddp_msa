package gov.uddp.core.mapping;

import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.shaping.api.RecordShaper;
import gov.uddp.core.statepack.StatePackAssets.CrosswalkDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingDefinition;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Applies a State Pack's declarative {@code Family} mapping rules to one raw record (M14, docs/family-domain.md).
 * The rules say which source fields name the resident's households and their role in them; nothing here knows a
 * state or a source schema. Resident mapping is unaffected.
 */
@Service
public class FamilyMappingEngine {
    public static final String ENTITY = "Family";
    public static final String FAMILY_IDS = "sourceFamilyIds[]";
    public static final String RELATIONSHIP = "membership.relationshipType";
    public static final String UNKNOWN = "UNKNOWN";
    /** The platform's closed list of relationship types; packs crosswalk source codes onto it. */
    public static final Set<String> RELATIONSHIP_TYPES =
            Set.of("HEAD", "SPOUSE", "CHILD", "PARENT", "SIBLING", "OTHER", UNKNOWN);

    private static final String EXTENSIONS = "extensions.";

    private final RecordShaper shaping;

    public FamilyMappingEngine(RawCsvRecordParser parser) {
        this(RecordShaper.passthrough(parser));
    }

    @Autowired
    public FamilyMappingEngine(RecordShaper shaping) {
        this.shaping = shaping;
    }

    /** What one delivery says about the households of the resident it describes. */
    public record FamilyMapping(String mappingVersion, List<String> sourceFamilyIds, String relationshipType,
                                Map<String, Object> extensions) {
    }

    /** Whether the source's mapping declares Family rules. */
    public boolean hasFamilyRules(LoadedStatePack pack, String sourceId) {
        return mapping(pack, sourceId).map(m -> m.entities() != null && m.entities().get(ENTITY) != null).orElse(false);
    }

    /**
     * Maps the record's Family rules; empty when the source has none. A record whose household fields are blank
     * maps to no household (an empty ID list) unless a rule is {@code required}.
     */
    public Optional<FamilyMapping> map(RawRecord raw, LoadedStatePack pack, String sourceId) {
        Optional<MappingDefinition> mapping = mapping(pack, sourceId);
        if (mapping.isEmpty() || mapping.get().entities() == null) {
            return Optional.empty();
        }
        List<MappingRule> rules = mapping.get().entities().get(ENTITY);
        if (rules == null) {
            return Optional.empty();
        }
        Map<String, Object> values = shaping.shape(raw, pack, sourceId).fields();
        Set<String> ids = new LinkedHashSet<>();
        String relationship = UNKNOWN;
        Map<String, Object> extensions = new LinkedHashMap<>();
        for (MappingRule rule : rules) {
            Object value = values.get(rule.sourceField());
            if (value == null || value.toString().isBlank()) {
                if (Boolean.TRUE.equals(rule.required())) {
                    throw new IllegalArgumentException("Required Family source field is blank: " + rule.sourceField());
                }
                continue;
            }
            String mapped = crosswalk(value.toString().strip(), rule, pack);
            String target = rule.target();
            if (FAMILY_IDS.equals(target)) {
                ids.add(mapped);
            } else if (RELATIONSHIP.equals(target)) {
                if (!RELATIONSHIP_TYPES.contains(mapped)) {
                    throw new IllegalArgumentException("Unsupported relationship type '" + mapped + "'; allowed: "
                            + String.join(", ", new java.util.TreeSet<>(RELATIONSHIP_TYPES)));
                }
                relationship = mapped;
            } else if (target != null && target.startsWith(EXTENSIONS) && target.length() > EXTENSIONS.length()) {
                extensions.put(target.substring(EXTENSIONS.length()), mapped);
            } else {
                throw new IllegalArgumentException("Unsupported Family mapping target: " + target);
            }
        }
        return Optional.of(new FamilyMapping(mapping.get().mappingVersion(), new ArrayList<>(ids), relationship,
                Map.copyOf(extensions)));
    }

    private static Optional<MappingDefinition> mapping(LoadedStatePack pack, String sourceId) {
        return pack.mappings().stream().filter(m -> m.sourceId().equals(sourceId)).findFirst();
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
