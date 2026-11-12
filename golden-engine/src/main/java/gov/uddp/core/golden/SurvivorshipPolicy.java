package gov.uddp.core.golden;

import gov.uddp.core.statepack.LoadedStatePack;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Generic, inspectable policy. Per-field source priority is State Pack configuration, never code branches.
 * {@code fieldPriority} maps a field path to a rank (0 = most trusted) per qualified source {@code stateCode:sourceId}.
 */
public record SurvivorshipPolicy(String version, Map<String, Map<String, Integer>> fieldPriority) {
    public static final String RESIDENT_V2 = "resident-survivorship-v2";
    public static final SurvivorshipPolicy V1 = new SurvivorshipPolicy("resident-survivorship-v1", Map.of());

    public SurvivorshipPolicy {
        fieldPriority = Map.copyOf(fieldPriority);
    }

    /** Builds the Resident policy from every pack's survivorship fieldPriority; each pack ranks its own sources. */
    public static SurvivorshipPolicy fromStatePacks(Collection<LoadedStatePack> packs) {
        Map<String, Map<String, Integer>> ranks = new LinkedHashMap<>();
        for (LoadedStatePack pack : packs) {
            String stateCode = pack.manifest().stateCode();
            for (var definition : pack.survivorship()) {
                if (!"Resident".equals(definition.entity()) || definition.fieldPriority() == null) {
                    continue;
                }
                for (var entry : definition.fieldPriority().entrySet()) {
                    if (!supported(entry.getKey())) {
                        throw new IllegalArgumentException("State Pack " + pack.manifest().packId()
                                + " sets survivorship priority for unsupported field " + entry.getKey());
                    }
                    var fieldRanks = ranks.computeIfAbsent(entry.getKey(), field -> new LinkedHashMap<>());
                    List<String> sources = entry.getValue();
                    for (int rank = 0; rank < sources.size(); rank++) {
                        fieldRanks.putIfAbsent(sourceKey(stateCode, sources.get(rank)), rank);
                    }
                }
            }
        }
        ranks.replaceAll((field, fieldRanks) -> Map.copyOf(fieldRanks));
        return new SurvivorshipPolicy(RESIDENT_V2, ranks);
    }

    /** A core survived field, or a declared state field ({@code stateFields.<stateCode>.<key>}). */
    static boolean supported(String path) {
        return SurvivorshipEngine.PATHS.contains(path)
                || path.matches(SurvivorshipEngine.STATE_FIELDS + "\\.[^.]+\\.[^.]+");
    }

    /** State code to the keys of the state fields its packs carry to the Golden Resident. */
    public static Map<String, List<String>> goldenStateFields(Collection<LoadedStatePack> packs) {
        Map<String, List<String>> out = new java.util.TreeMap<>();
        for (LoadedStatePack pack : packs) {
            for (var field : pack.stateFields()) {
                if (field.toGolden()) {
                    var keys = out.computeIfAbsent(pack.manifest().stateCode(), code -> new java.util.ArrayList<>());
                    if (!keys.contains(field.key())) {
                        keys.add(field.key());
                    }
                }
            }
        }
        out.replaceAll((code, keys) -> List.copyOf(keys));
        return out;
    }

    public static String sourceKey(String stateCode, String sourceId) {
        return stateCode + ":" + sourceId;
    }

    public static SurvivorshipPolicy fromPlatform(PlatformSurvivorshipPolicy platform) {
        Map<String, Map<String, Integer>> ranks = new LinkedHashMap<>();
        for (var entry : platform.fields().entrySet()) {
            if (!supported(entry.getKey())) throw new IllegalArgumentException("Unsupported platform field " + entry.getKey());
            var field = entry.getValue();
            if (!"SOURCE_TRUST".equals(field.strategy()) && !"DETERMINISTIC_FALLBACK".equals(field.strategy()))
                throw new IllegalArgumentException("Unsupported platform strategy " + field.strategy());
            if ("SOURCE_TRUST".equals(field.strategy()) && field.sourcePriority() != null) {
                Map<String, Integer> priority = new LinkedHashMap<>();
                for (int i = 0; i < field.sourcePriority().size(); i++) priority.put(field.sourcePriority().get(i), i);
                ranks.put(entry.getKey(), Map.copyOf(priority));
            }
        }
        return new SurvivorshipPolicy(platform.version(), ranks);
    }

    /** The configured rank of an observation's source for a field, if the field's priority lists that source. */
    OptionalInt rank(String field, CanonicalObservation observation) {
        Map<String, Integer> ranks = fieldPriority.getOrDefault(field, Map.of());
        Integer rank = ranks.get(sourceKey(observation.stateCode(), observation.source()));
        if (rank == null) rank = ranks.get(observation.source());
        return rank == null ? OptionalInt.empty() : OptionalInt.of(rank);
    }
}
