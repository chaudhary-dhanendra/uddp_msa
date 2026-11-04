package gov.uddp.core.reference;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A source of reference code sets (Track F2). Small, static sets come from platform files; large, living sets (the LGD
 * geography master) come from the database. {@link ReferenceDataService} combines the providers.
 */
public interface ReferenceDataProvider {
    /** One code of a set, with the name shown to people. */
    record ReferenceValue(String code, String displayName, String status) {
    }

    /** Short provider name, for display. */
    String name();

    /** The sets this provider currently answers for. */
    Set<String> referenceSets();

    /** The valid codes of a set, or empty when this provider does not answer for it. */
    Optional<Set<String>> codes(String referenceSet);

    /** Every value of the set with its name, for matching many values in memory; empty when not offered. */
    default List<ReferenceValue> values(String referenceSet) {
        return List.of();
    }

    /** Whether this provider answers the set; cheaper than {@link #codes} for a provider whose sets are large. */
    default boolean answers(String referenceSet) {
        return codes(referenceSet).isPresent();
    }

    /** Values of a set whose code equals or whose name contains {@code text} (null for all), at most {@code limit}. */
    List<ReferenceValue> lookup(String referenceSet, String text, int limit);
}
