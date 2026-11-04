package gov.uddp.core.geography;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the geography portion of a canonical record.  Source Packs own the values supplied here (including any
 * mapping crosswalk); the LGD master is deliberately read-only.  A non-exact name produces review evidence, never a
 * selected entity.
 */
public final class CanonicalGeographyResolver {
    public static final String EXTENSION = "uddp.geography";
    private final GeographyService geography;

    public CanonicalGeographyResolver(GeographyService geography) {
        this.geography = geography;
    }

    /** True once an LGD snapshot is active; before that mapped codes cannot be checked. */
    public boolean masterLoaded() {
        return geography.loaded();
    }

    /** Resolves supplied level values and returns a JSON-safe geography extension. */
    public Map<String, Object> resolve(Map<String, Map<String, String>> supplied) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        supplied.forEach((level, values) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            values.forEach((key, value) -> entry.put(key, value));
            result.put(level, entry);
        });
        Map<String, Object> pincode = result.remove("pincode");
        if (!geography.loaded()) {
            result.values().forEach(entry -> entry.put("status", "UNKNOWN"));
            if (pincode != null) result.put("pincode", pincode);
            return Map.copyOf(result);
        }

        // Codes (including source-owned crosswalk output) are always considered before names.
        result.forEach((level, entry) -> {
            String code = string(entry.get("lgd"));
            if (code != null) {
                resolveCode(level, code, entry);
            }
        });
        List<String> names = new ArrayList<>(result.keySet());
        names.sort(Comparator.comparingInt(this::levelOrder));
        for (String level : names) {
            Map<String, Object> entry = result.get(level);
            if (entry.get("lgd") != null || string(entry.get("name")) == null) {
                continue;
            }
            resolveName(level, string(entry.get("name")), result, entry);
        }
        enforceConsistency(result);
        deriveAncestors(result);
        if (pincode != null) result.put("pincode", pincode);
        return Map.copyOf(result);
    }

    private void resolveCode(String level, String code, Map<String, Object> entry) {
        var validation = geography.validate(type(level), code, null, null);
        entry.put("status", validation.status().name());
        if (validation.entity() != null) {
            entry.put("lgd", validation.entity().code());
            entry.putIfAbsent("name", validation.entity().name());
        } else {
            // The source may have called this a code, but an unknown value must not become a canonical LGD code.
            entry.remove("lgd");
        }
    }

    private void resolveName(String level, String name, Map<String, Map<String, Object>> all,
                             Map<String, Object> entry) {
        GeographyService.GeoEntity parent = parentFor(level, all);
        var candidates = geography.search(type(level), name,
                parent == null ? null : parent.entityType(), parent == null ? null : parent.code(), "ACTIVE", 0, 25)
                .items().stream().filter(item -> item.name().equalsIgnoreCase(name.strip())).toList();
        if (candidates.size() == 1) {
            entry.put("lgd", candidates.getFirst().code());
            entry.put("name", candidates.getFirst().name());
            entry.put("status", GeographyService.CodeStatus.ACTIVE_VALID.name());
            return;
        }
        entry.put("status", GeographyService.CodeStatus.UNKNOWN.name());
        var suggestions = geography.search(type(level), name,
                parent == null ? null : parent.entityType(), parent == null ? null : parent.code(), "ACTIVE", 0, 5)
                .items().stream().map(item -> Map.of("lgd", item.code(), "name", item.name())).toList();
        if (!suggestions.isEmpty()) {
            entry.put("suggestions", suggestions);
        }
    }

    private GeographyService.GeoEntity parentFor(String level, Map<String, Map<String, Object>> all) {
        // The closest supplied higher level is the known context.  Consistency is checked after resolution, so an
        // accidentally incompatible combination is rejected rather than corrected.
        GeographyService.GeoEntity closest = null;
        for (var candidate : all.entrySet()) {
            String code = string(candidate.getValue().get("lgd"));
            if (code == null) continue;
            var entity = geography.find(type(candidate.getKey()), code);
            if (entity.isPresent() && levelOrder(candidate.getKey()) < levelOrder(level)
                    && (closest == null || levelOrder(candidate.getKey()) > levelOrder(level(closest.entityType())))) {
                closest = entity.get();
            }
        }
        return closest;
    }

    private void enforceConsistency(Map<String, Map<String, Object>> all) {
        for (var child : all.entrySet()) {
            String childCode = string(child.getValue().get("lgd"));
            if (childCode == null) continue;
            for (var parent : all.entrySet()) {
                String parentCode = string(parent.getValue().get("lgd"));
                if (parentCode == null || levelOrder(parent.getKey()) >= levelOrder(child.getKey())) continue;
                var validation = geography.validate(type(child.getKey()), childCode, type(parent.getKey()), parentCode);
                if (Boolean.FALSE.equals(validation.parentConsistent())) {
                    throw new IllegalArgumentException("Inconsistent geography: " + child.getKey() + " " + childCode
                            + " is not within " + parent.getKey() + " " + parentCode);
                }
            }
        }
    }

    private void deriveAncestors(Map<String, Map<String, Object>> all) {
        List<Map.Entry<String, Map<String, Object>>> resolved = all.entrySet().stream()
                .filter(e -> string(e.getValue().get("lgd")) != null).toList();
        for (var item : resolved) {
            for (var ancestor : geography.ancestors(type(item.getKey()), string(item.getValue().get("lgd")))) {
                String level = level(ancestor.entityType());
                all.computeIfAbsent(level, ignored -> new LinkedHashMap<>()).putIfAbsent("lgd", ancestor.code());
                Map<String, Object> target = all.get(level);
                target.putIfAbsent("name", ancestor.name());
                target.putIfAbsent("status", ancestor.status().equals("ACTIVE") ? "ACTIVE_VALID" : "KNOWN_INACTIVE");
            }
        }
    }

    private int levelOrder(String level) {
        return geography.entityTypes().stream().filter(item -> item.entityType().equals(type(level)))
                .mapToInt(GeographyService.EntityType::levelOrder).findFirst().orElse(999);
    }

    public static String type(String level) {
        return level.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    public static String level(String type) {
        String[] words = type.toLowerCase(Locale.ROOT).split("_");
        StringBuilder value = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) value.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        return value.toString();
    }

    private static String string(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().strip();
    }
}
