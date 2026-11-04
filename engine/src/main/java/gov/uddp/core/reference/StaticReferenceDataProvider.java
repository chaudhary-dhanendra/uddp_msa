package gov.uddp.core.reference;

import gov.uddp.core.validation.PlatformReferenceData;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Small code sets from {@code <platform>/reference-data/*.yaml} (the M9 platform reference data). */
@Component
public class StaticReferenceDataProvider implements ReferenceDataProvider {
    private final PlatformReferenceData files;

    public StaticReferenceDataProvider(PlatformReferenceData files) {
        this.files = files;
    }

    @Override
    public String name() {
        return "static";
    }

    @Override
    public Set<String> referenceSets() {
        return files.values().keySet();
    }

    @Override
    public Optional<Set<String>> codes(String referenceSet) {
        return Optional.ofNullable(files.values().get(referenceSet));
    }

    @Override
    public List<ReferenceValue> lookup(String referenceSet, String text, int limit) {
        String needle = text == null ? null : text.strip().toLowerCase(Locale.ROOT);
        return files.datasets().stream().filter(d -> d.referenceSet().equals(referenceSet))
                .flatMap(d -> d.values().stream())
                .filter(v -> needle == null || needle.isEmpty() || v.code().equals(text.strip())
                        || (v.displayName() != null && v.displayName().toLowerCase(Locale.ROOT).contains(needle)))
                .limit(limit)
                .map(v -> new ReferenceValue(v.code(), v.displayName(), "ACTIVE"))
                .toList();
    }
}
