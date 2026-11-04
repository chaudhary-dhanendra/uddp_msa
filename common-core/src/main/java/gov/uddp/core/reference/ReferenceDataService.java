package gov.uddp.core.reference;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Reference data for validation and the Studio (Track F2): the LGD provider answers the {@code lgd-*} sets once the
 * geography master is loaded; the static provider answers everything else (and {@code lgd-district} until then).
 * REFERENCE_LIST validation resolves its sets here.
 */
@Service
public class ReferenceDataService {
    /** One set and the provider that currently answers it. */
    public record ReferenceSet(String referenceSet, String provider, int size) {
    }

    private final List<ReferenceDataProvider> providers;

    /** {@code lgd} first: a live set wins over a static file of the same name. */
    public ReferenceDataService(LgdReferenceDataProvider lgd, StaticReferenceDataProvider files) {
        this.providers = List.of(lgd, files);
    }

    public List<ReferenceSet> sets() {
        Map<String, ReferenceSet> sets = new LinkedHashMap<>();
        for (ReferenceDataProvider provider : providers) {
            for (String set : provider.referenceSets()) {
                if (!sets.containsKey(set)) {
                    sets.put(set, new ReferenceSet(set, provider.name(), provider.codes(set).map(Set::size).orElse(0)));
                }
            }
        }
        return List.copyOf(sets.values());
    }

    public Optional<Set<String>> codes(String referenceSet) {
        for (ReferenceDataProvider provider : providers) {
            Optional<Set<String>> codes = provider.codes(referenceSet);
            if (codes.isPresent()) {
                return codes;
            }
        }
        return Optional.empty();
    }

    /** Every value of the set from the first provider that answers it (empty when that provider offers none). */
    public List<ReferenceDataProvider.ReferenceValue> values(String referenceSet) {
        for (ReferenceDataProvider provider : providers) {
            if (provider.answers(referenceSet)) {
                return provider.values(referenceSet);
            }
        }
        return List.of();
    }

    public List<ReferenceDataProvider.ReferenceValue> lookup(String referenceSet, String text, int limit) {
        for (ReferenceDataProvider provider : providers) {
            if (provider.answers(referenceSet)) {
                return provider.lookup(referenceSet, text, limit);
            }
        }
        return List.of();
    }

    /** {@code values} with every set a live provider answers replaced by the live codes (for REFERENCE_LIST rules). */
    public Map<String, Set<String>> resolve(Map<String, Set<String>> values) {
        Map<String, Set<String>> resolved = new LinkedHashMap<>(values);
        for (ReferenceDataProvider provider : providers) {
            if (provider instanceof StaticReferenceDataProvider) {
                continue;
            }
            for (String set : provider.referenceSets()) {
                provider.codes(set).ifPresent(codes -> resolved.put(set, codes));
            }
        }
        return resolved;
    }

    /** Every set, live sets winning. */
    public Map<String, Set<String>> values() {
        Map<String, Set<String>> base = new LinkedHashMap<>();
        providers.getLast().referenceSets().forEach(set -> providers.getLast().codes(set).ifPresent(c -> base.put(set, c)));
        return resolve(base);
    }
}
