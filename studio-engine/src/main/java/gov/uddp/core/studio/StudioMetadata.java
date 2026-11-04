package gov.uddp.core.studio;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.scheme.SchemeCatalogue;
import gov.uddp.core.studio.StudioModels.CodeValue;
import gov.uddp.core.studio.StudioModels.MappingTarget;
import gov.uddp.core.validation.PlatformReferenceData.Dataset;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Platform metadata the Studio works from: mapping targets ({@code platform/studio/mapping-targets.yaml}), rule
 * defaults ({@code platform/studio/rule-defaults.yaml}), the LGD district reference list and the scheme catalogue.
 */
@Component
public class StudioMetadata {
    public static final List<String> DATE_CONVERSIONS = List.of("date:DD-MM-YYYY", "date:YYYY-MM-DD", "date:YYYYMMDD",
            "date:YYYY/MM/DD", "date:DD/MM/YYYY", "date:MM/DD/YYYY", "date:DDMMYYYY");

    public record Target(String entity, String target, String label, String type, String codeSet, String valueKind,
                         String group, List<List<String>> keywords, List<String> excludes) {
        public String key() {
            return entity + ":" + target;
        }
    }

    public record CodeSet(List<String> values, String referenceSet, Boolean catalogue) {
    }

    public record TargetFile(String version, Map<String, CodeSet> codeSets, List<Target> targets) {
    }

    public record DefaultValidation(String id, String type, String field, Boolean required, List<String> allowedValues,
                                    String pattern, String referenceSet, String severity) {
    }

    public record DefaultMatching(List<String> fields, Map<String, Double> thresholds) {
    }

    public record DefaultSurvivorship(Map<String, List<String>> fieldPriority) {
    }

    public record RuleDefaults(String version, List<DefaultValidation> validation, DefaultMatching matching,
                               DefaultSurvivorship survivorship) {
    }

    private final TargetFile targets;
    private final RuleDefaults defaults;
    private final Map<String, Dataset> references;
    private final SchemeCatalogue catalogue;

    @org.springframework.beans.factory.annotation.Autowired
    public StudioMetadata(StatePackProperties properties, SchemeCatalogue catalogue) {
        this(Path.of(properties.getPlatformDirectory()), catalogue);
    }

    /** {@code platformDirectory} holds {@code studio/} and {@code reference-data/}. */
    public StudioMetadata(Path platformDirectory, SchemeCatalogue catalogue) {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        Path studio = platformDirectory.resolve("studio");
        try {
            this.targets = yaml.readValue(studio.resolve("mapping-targets.yaml").toFile(), TargetFile.class);
            this.defaults = yaml.readValue(studio.resolve("rule-defaults.yaml").toFile(), RuleDefaults.class);
            Map<String, Dataset> datasets = new LinkedHashMap<>();
            try (var files = Files.list(platformDirectory.resolve("reference-data"))) {
                for (Path file : files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                    Dataset dataset = yaml.readValue(file.toFile(), Dataset.class);
                    datasets.put(dataset.referenceSet(), dataset);
                }
            }
            this.references = Map.copyOf(datasets);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the Studio metadata in " + platformDirectory, e);
        }
        this.catalogue = catalogue;
    }

    private gov.uddp.core.reference.ReferenceDataService referenceData;

    /** Answers about the live (LGD) code sets, kept for 5 seconds: one suggestions request asks them many times. */
    private final Expiring<Boolean> LIVE = new Expiring<>(5_000, 64);
    private final Expiring<List<gov.uddp.core.reference.ReferenceDataProvider.ReferenceValue>> LOOKUPS =
            new Expiring<>(5_000, 10_000);

    /** A small time-limited memo. */
    static final class Expiring<V> {
        private record Entry<V>(V value, long at) {
        }

        private final long ttlMillis;
        private final int maxEntries;
        private final java.util.concurrent.ConcurrentHashMap<String, Entry<V>> entries =
                new java.util.concurrent.ConcurrentHashMap<>();

        Expiring(long ttlMillis, int maxEntries) {
            this.ttlMillis = ttlMillis;
            this.maxEntries = maxEntries;
        }

        V get(String key, java.util.function.Supplier<V> load) {
            long now = System.currentTimeMillis();
            Entry<V> entry = entries.get(key);
            if (entry != null && now - entry.at() < ttlMillis) {
                return entry.value();
            }
            if (entries.size() >= maxEntries) {
                entries.clear();
            }
            V value = load.get();
            // An empty answer is not kept: a master loaded a moment later must be seen at once.
            if (value != null && !Boolean.FALSE.equals(value) && !(value instanceof java.util.Collection<?> c && c.isEmpty())) {
                entries.put(key, new Entry<>(value, now));
            }
            return value;
        }

        void clear() {
            entries.clear();
        }
    }

    /** Live reference sets (Track F5): lgd-* code sets are looked up in the LGD master, value by value. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReferenceData(gov.uddp.core.reference.ReferenceDataService referenceData) {
        this.referenceData = referenceData;
    }

    /** True when a code set is answered live by the LGD master rather than listed in full. */
    public boolean liveCodeSet(String codeSet) {
        CodeSet set = targets.codeSets().get(codeSet);
        if (referenceData == null || set == null || set.referenceSet() == null
                || !set.referenceSet().startsWith(gov.uddp.core.reference.LgdReferenceDataProvider.PREFIX)) {
            return false;
        }
        // Asked once per column value while suggestions are scored; reading every code of the master each time made
        // the Map step take minutes once a full LGD master was loaded.
        return LIVE.get(set.referenceSet(), () -> referenceData.codes(set.referenceSet()).isPresent());
    }

    /**
     * Candidate codes for one source value: for a live set the master's entities whose code equals the value or whose
     * name contains it (or its first letters); otherwise the whole list.
     */
    public List<CodeValue> codes(String codeSet, String value) {
        if (!liveCodeSet(codeSet)) {
            return codes(codeSet);
        }
        String set = targets.codeSets().get(codeSet).referenceSet();
        Map<String, CodeValue> found = new LinkedHashMap<>();
        String text = value == null ? "" : value.strip();
        // The whole set is read once (and kept for a few seconds) and every probe is matched in memory, as the master's
        // search would: the code exactly, or a name containing the probe, ignoring case. One database query per probe made
        // the Map step take minutes with a full LGD master.
        List<gov.uddp.core.reference.ReferenceDataProvider.ReferenceValue> all =
                LOOKUPS.get(set, () -> referenceData.values(set));
        for (String probe : probes(text)) {
            if (probe.isBlank()) {
                continue;
            }
            if (all.isEmpty()) {
                // A provider that cannot list its whole set (a reference file) is asked probe by probe.
                referenceData.lookup(set, probe, 25).forEach(v -> found.putIfAbsent(v.code(),
                        new CodeValue(v.code(), v.displayName())));
                continue;
            }
            String needle = probe.toLowerCase(java.util.Locale.ROOT);
            all.stream().filter(v -> v.code().equals(probe)
                            || (v.displayName() != null && v.displayName().toLowerCase(java.util.Locale.ROOT).contains(needle)))
                    .limit(25)
                    .forEach(v -> found.putIfAbsent(v.code(), new CodeValue(v.code(), v.displayName())));
        }
        return List.copyOf(found.values());
    }

    /** What to look up in the master for one source value: the value, its first letters and each of its words. */
    static Set<String> probes(String text) {
        Set<String> probes = new LinkedHashSet<>();
        probes.add(text);
        if (text.length() > 4) {
            probes.add(text.substring(0, 4));
        }
        // a qualifier word around the master name ("North-X", "X (Rural)") still finds X through its own word
        for (String word : text.split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3) {
                probes.add(word);
            }
        }
        return probes;
    }

    /** A platform reference list (for example lgd-district), if present. */
    public Optional<Dataset> reference(String referenceSet) {
        return Optional.ofNullable(references.get(referenceSet));
    }

    public List<Target> targets() {
        return targets.targets();
    }

    public Optional<Target> target(String entity, String target) {
        return targets().stream().filter(t -> t.entity().equals(entity) && t.target().equals(target)).findFirst();
    }

    public RuleDefaults defaults() {
        return defaults;
    }

    /** Every platform code a coded target can be crosswalked onto, by code set name. */
    public Map<String, List<CodeValue>> codeSets() {
        Map<String, List<CodeValue>> result = new LinkedHashMap<>();
        targets.codeSets().forEach((name, set) -> result.put(name, codes(set)));
        return result;
    }

    public List<CodeValue> codes(String codeSet) {
        CodeSet set = targets.codeSets().get(codeSet);
        return set == null ? List.of() : codes(set);
    }

    public List<MappingTarget> mappingTargets() {
        return targets().stream()
                .map(t -> new MappingTarget(t.entity(), t.target(), t.label(), t.type(), t.codeSet(),
                        t.codeSet() == null ? null : SuggestionEngine.crosswalkId(t.codeSet())))
                .toList();
    }

    public SchemeCatalogue catalogue() {
        return catalogue;
    }

    private List<CodeValue> codes(CodeSet set) {
        List<CodeValue> codes = new ArrayList<>();
        if (set.values() != null) {
            set.values().forEach(value -> codes.add(new CodeValue(value, value)));
        }
        if (set.referenceSet() != null) {
            reference(set.referenceSet()).ifPresent(dataset ->
                    dataset.values().forEach(v -> codes.add(new CodeValue(v.code(), v.displayName()))));
        }
        if (Boolean.TRUE.equals(set.catalogue())) {
            catalogue.schemes().forEach(s -> codes.add(new CodeValue(s.schemeId(), s.name())));
        }
        return codes;
    }
}
