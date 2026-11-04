package gov.uddp.core.reference;

import gov.uddp.core.geography.GeographyService;
import gov.uddp.core.geography.LgdImportService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Reference sets over the LGD geography master (Track F2): {@code lgd-<type>} (for example {@code lgd-block},
 * {@code lgd-village}) lists the ACTIVE codes of that entity type. {@code lgd-district} is therefore a compatibility
 * view over DISTRICT entities. A set is answered only once the master holds entities of its type, so before the first
 * LGD import the static file of the same name keeps working.
 */
@Component
public class LgdReferenceDataProvider implements ReferenceDataProvider {
    public static final String PREFIX = "lgd-";

    private final GeographyService geography;

    public LgdReferenceDataProvider(GeographyService geography) {
        this.geography = geography;
    }

    @Override
    public String name() {
        return "lgd";
    }

    /** The reference set name of an entity type: DISTRICT -> lgd-district, SUB_DISTRICT -> lgd-sub-district. */
    public static String referenceSet(String entityType) {
        return PREFIX + entityType.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The entity type of a reference set, or null when the set is not an LGD set. */
    public static String entityType(String referenceSet) {
        return referenceSet != null && referenceSet.startsWith(PREFIX)
                ? referenceSet.substring(PREFIX.length()).toUpperCase(Locale.ROOT).replace('-', '_') : null;
    }

    @Override
    public Set<String> referenceSets() {
        if (!geography.loaded()) {
            return Set.of();
        }
        Set<String> sets = new LinkedHashSet<>();
        for (var type : geography.entityTypes()) {
            if (!geography.codes(type.entityType(), LgdImportService.ACTIVE).isEmpty()) {
                sets.add(referenceSet(type.entityType()));
            }
        }
        return sets;
    }

    @Override
    public Optional<Set<String>> codes(String referenceSet) {
        String type = entityType(referenceSet);
        if (type == null || !geography.loaded()) {
            return Optional.empty();
        }
        List<String> codes = geography.codes(type, LgdImportService.ACTIVE);
        return codes.isEmpty() ? Optional.empty() : Optional.of(Set.copyOf(codes));
    }

    @Override
    public List<ReferenceValue> values(String referenceSet) {
        String type = entityType(referenceSet);
        if (type == null) {
            return List.of();
        }
        return geography.names(type).stream().map(e -> new ReferenceValue(e[0], e[1], e[2])).toList();
    }

    /** A set of an LGD entity type is answered once a master is loaded, without reading all its codes. */
    @Override
    public boolean answers(String referenceSet) {
        return entityType(referenceSet) != null && geography.loaded();
    }

    @Override
    public List<ReferenceValue> lookup(String referenceSet, String text, int limit) {
        String type = entityType(referenceSet);
        if (type == null) {
            return List.of();
        }
        return geography.search(type, text, null, null, null, 0, Math.max(1, Math.min(limit, 200))).items().stream()
                .map(e -> new ReferenceValue(e.code(), e.name(), e.status()))
                .toList();
    }
}
