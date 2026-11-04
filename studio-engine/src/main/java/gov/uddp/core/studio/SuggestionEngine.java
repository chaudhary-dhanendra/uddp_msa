package gov.uddp.core.studio;

import gov.uddp.core.scheme.SchemeCatalogue.Scheme;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import gov.uddp.core.studio.StudioMetadata.Target;
import gov.uddp.core.studio.StudioModels.CodeValue;
import gov.uddp.core.studio.StudioModels.ColumnProfile;
import gov.uddp.core.studio.StudioModels.ColumnSuggestion;
import gov.uddp.core.studio.StudioModels.Crosswalk;
import gov.uddp.core.studio.StudioModels.LayoutProposal;
import gov.uddp.shaping.api.RawRow;
import gov.uddp.shaping.api.RelationMarkers;
import gov.uddp.core.studio.StudioModels.CrosswalkEntry;
import gov.uddp.core.studio.StudioModels.Profile;
import gov.uddp.core.studio.StudioModels.Suggestions;
import gov.uddp.core.studio.StudioModels.TargetSuggestion;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import gov.uddp.core.config.StudioProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Suggests, for each profiled column, canonical targets with a confidence and the reason, and for coded columns a
 * crosswalk of their distinct values onto platform codes. Every signal is generic (ADR-0015):
 * <ul>
 *   <li>column-name words against the keyword groups in {@code platform/studio/mapping-targets.yaml};</li>
 *   <li>value patterns found by the profiler (dates, mobile numbers, numbers);</li>
 *   <li>mappings and crosswalks of previously approved packs (Track D1): a column of the same or a similar name, the
 *       same date pattern, or values those packs already crosswalked onto the same code set; every pack that agrees
 *       raises the confidence, and the reason names the source it was learned from;</li>
 *   <li>platform reference data: geography values at any LGD level matched to the LGD master (live lookup, Track
 *       F5; the static list until a master is loaded), scheme codes to the scheme catalogue.</li>
 * </ul>
 * Suggestions at or above {@code uddp.studio.suggestion-accept-confidence} are pre-accepted; the rest are offered for
 * review (and listed in the exception inbox until the draft's mappings are saved).
 */
@Component
public class SuggestionEngine {
    static final double THRESHOLD = 0.5;
    private static final Set<String> SHARED_TARGETS = Set.of("Resident:extensions.familyReference",
            "Family:sourceFamilyIds[]", "Benefit:sourceFamilyId");
    private static final Map<String, String> CROSSWALK_IDS = Map.of("gender", "gender", "lgd-district", "district",
            "scheme", "scheme", "benefit-status", "benefit-status", "relationship", "relationship");

    static final double DEFAULT_ACCEPT_CONFIDENCE = 0.7;

    private final StudioMetadata metadata;
    private final double acceptConfidence;
    private final LayoutProfiler layout;

    @Autowired
    public SuggestionEngine(StudioMetadata metadata, StudioProperties studio, RelationMarkers relations) {
        this(metadata, studio.getSuggestionAcceptConfidence(), relations);
    }

    public SuggestionEngine(StudioMetadata metadata, StudioProperties studio) {
        this(metadata, studio.getSuggestionAcceptConfidence());
    }

    public SuggestionEngine(StudioMetadata metadata) {
        this(metadata, DEFAULT_ACCEPT_CONFIDENCE);
    }

    public SuggestionEngine(StudioMetadata metadata, double acceptConfidence) {
        this(metadata, acceptConfidence, null);
    }

    public SuggestionEngine(StudioMetadata metadata, double acceptConfidence, RelationMarkers relations) {
        this.metadata = metadata;
        this.acceptConfidence = acceptConfidence;
        this.layout = new LayoutProfiler(relations);
    }

    /**
     * Profiles the raw rows of an uploaded file (all sheets) for messy-layout patterns and returns the layout steps,
     * parsers and mapping settings that would tidy them, each with its evidence count. Nothing is applied here.
     */
    public List<LayoutProposal> layoutProposals(List<RawRow> rows) {
        return layout.profile(rows);
    }

    public double acceptConfidence() {
        return acceptConfidence;
    }

    public static String crosswalkId(String codeSet) {
        return CROSSWALK_IDS.getOrDefault(codeSet, codeSet);
    }

    public Suggestions suggest(Profile profile, Collection<LoadedStatePack> published) {
        Learned learned = learn(published);
        Map<String, ColumnProfile> columns = new LinkedHashMap<>();
        profile.columns().forEach(c -> columns.put(c.name(), c));

        String schemeColumn = best(columns.values(), metadata.target("Benefit", "schemeCode").orElseThrow(), learned, null)
                .filter(s -> s.score() >= THRESHOLD)
                .map(Scored::column)
                .orElse(null);
        String benefitPrefix = schemeColumn == null ? null : tokens(schemeColumn).getFirst();

        List<Scored> candidates = new ArrayList<>();
        for (Target target : metadata.targets()) {
            if ("Benefit".equals(target.entity()) && schemeColumn == null) {
                continue;
            }
            for (ColumnProfile column : columns.values()) {
                Scored scored = score(column, target, learned, benefitPrefix);
                if (scored.score() >= THRESHOLD) {
                    candidates.add(scored);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(s -> columnOrder(columns, s.column())));

        Map<String, List<Scored>> assigned = new LinkedHashMap<>();
        columns.keySet().forEach(name -> assigned.put(name, new ArrayList<>()));
        Set<String> usedTargets = new HashSet<>();
        for (Scored candidate : candidates) {
            String key = candidate.target().key();
            boolean shared = SHARED_TARGETS.contains(key);
            if (usedTargets.contains(key)) {
                continue;
            }
            if (!shared && !assigned.get(candidate.column()).isEmpty()) {
                continue;
            }
            if (shared && !assigned.get(candidate.column()).isEmpty()
                    && assigned.get(candidate.column()).stream().noneMatch(s -> SHARED_TARGETS.contains(s.target().key()))) {
                continue;
            }
            usedTargets.add(key);
            assigned.get(candidate.column()).add(candidate);
        }

        String familyColumn = columnOf(assigned, "Family:sourceFamilyIds[]");
        // A benefit with no identifier column of its own is identified by its household (for example a ration card).
        if (schemeColumn != null && familyColumn != null && !usedTargets.contains("Benefit:sourceBenefitId")) {
            assigned.get(familyColumn).add(new Scored(familyColumn,
                    metadata.target("Benefit", "sourceBenefitId").orElseThrow(), 0.6,
                    "no benefit ID column: the household ID identifies the benefit"));
            assigned.get(familyColumn).add(new Scored(familyColumn,
                    metadata.target("Benefit", "sourceFamilyId").orElseThrow(), 0.6,
                    "the benefit is held by the household"));
        }

        List<ColumnSuggestion> suggestions = new ArrayList<>();
        List<Crosswalk> crosswalks = new ArrayList<>();
        for (var entry : assigned.entrySet()) {
            ColumnProfile column = columns.get(entry.getKey());
            List<TargetSuggestion> targets = new ArrayList<>();
            for (Scored scored : entry.getValue()) {
                Target target = scored.target();
                String conversion = "date".equals(target.type()) && column.dateFormat() != null
                        ? "date:" + column.dateFormat() : null;
                String crosswalk = null;
                if (target.codeSet() != null) {
                    Crosswalk values = crosswalk(column, target.codeSet(), learned);
                    if (values != null) {
                        crosswalk = values.id();
                        crosswalks.add(values);
                    }
                }
                targets.add(new TargetSuggestion(target.entity(), target.target(), conversion, crosswalk,
                        round(scored.score()), scored.basis(), scored.score() >= acceptConfidence));
            }
            suggestions.add(new ColumnSuggestion(entry.getKey(), targets));
        }

        String nameColumn = columnOf(assigned, "Resident:name");
        String recordId = familyColumn;
        if (recordId == null) {
            recordId = columns.values().stream()
                    .filter(ColumnProfile::unique)
                    .filter(c -> tokens(c.name()).stream().anyMatch(t -> Set.of("id", "no", "ref", "number", "token").contains(t)))
                    .map(ColumnProfile::name)
                    .findFirst()
                    .orElse(columns.keySet().iterator().next());
        }
        List<String> keyFields = new ArrayList<>();
        if (!columns.get(recordId).unique() && nameColumn != null) {
            // The identifier is shared by several rows (a household number): the name tells its residents apart.
            keyFields.add(recordId);
            keyFields.add(nameColumn);
        }
        return new Suggestions(suggestions, crosswalks, recordId, keyFields);
    }

    /**
     * Suggested entries for a column whose coded target was chosen by hand: the crosswalk the suggestions would have
     * offered, or one entry per value mapping it to itself when the values are already platform codes.
     */
    public Crosswalk crosswalkFor(ColumnProfile column, String codeSet, String id, Collection<LoadedStatePack> published) {
        Crosswalk suggested = crosswalk(column, codeSet, learn(published));
        if (suggested != null) {
            return new Crosswalk(id, codeSet, suggested.entries());
        }
        List<CrosswalkEntry> same = column.topValues().stream()
                .map(v -> new CrosswalkEntry(v.value(), v.value(), "already a platform code", 1.0, true))
                .toList();
        return new Crosswalk(id, codeSet, same);
    }

    /** Distinct values of a coded column with suggested platform codes; null when the values are already codes. */
    Crosswalk crosswalk(ColumnProfile column, String codeSet, Learned learned) {
        boolean live = metadata.liveCodeSet(codeSet);
        List<CodeValue> codes = live ? List.of() : metadata.codes(codeSet);
        Set<String> codeValues = new HashSet<>();
        codes.forEach(c -> codeValues.add(c.code()));
        List<String> distinct = column.topValues().stream().map(v -> v.value()).toList();
        if (!distinct.isEmpty() && (live ? allLiveCodes(column, codeSet) : codeValues.containsAll(distinct))) {
            return null;
        }
        List<CrosswalkEntry> entries = new ArrayList<>();
        for (String value : distinct) {
            CrosswalkEntry entry = suggestValue(value, codeSet, live ? metadata.codes(codeSet, value) : codes, learned);
            entries.add(new CrosswalkEntry(entry.sourceValue(), entry.targetValue(), entry.basis(), entry.confidence(),
                    entry.targetValue() != null && entry.confidence() != null && entry.confidence() >= acceptConfidence));
        }
        return new Crosswalk(crosswalkId(codeSet), codeSet, entries);
    }

    private CrosswalkEntry suggestValue(String value, String codeSet, List<CodeValue> codes, Learned learned) {
        String normalised = normalise(value);
        for (CodeValue code : codes) {
            if (code.code().equalsIgnoreCase(value)) {
                return new CrosswalkEntry(value, code.code(), "already a platform code", 1.0);
            }
        }
        var known = learned.values(codeSet).get(valueKey(value));
        if (known != null) {
            return new CrosswalkEntry(value, known.code(), "value used by " + String.join(", ", known.sources())
                    + " source", Math.min(0.99, 0.85 + 0.05 * known.sources().size()));
        }
        for (CodeValue code : codes) {
            if (normalise(code.label()).equals(normalised)) {
                return new CrosswalkEntry(value, code.code(), "same name as " + code.label(), 0.95);
            }
        }
        List<CodeValue> prefixedNames = codes.stream()
                .filter(c -> codeSet.startsWith("lgd-") && normalise(value).length() >= 3)
                .filter(c -> normalise(c.label()).startsWith(normalised)).toList();
        if (prefixedNames.size() == 1) {
            CodeValue only = prefixedNames.getFirst();
            return new CrosswalkEntry(value, only.code(), "starts with " + only.label(), 0.6);
        }
        if ("scheme".equals(codeSet)) {
            for (Scheme scheme : metadata.catalogue().schemes()) {
                if (acronym(scheme.name()).equalsIgnoreCase(value)) {
                    return new CrosswalkEntry(value, scheme.schemeId(), "initials of " + scheme.name(), 0.8);
                }
            }
        }
        List<CodeValue> fuzzy = codes.stream()
                .filter(c -> normalise(c.label()).length() >= 3)
                .filter(c -> normalised.contains(normalise(c.label())) || similarity(normalised, normalise(c.label())) >= 0.8)
                .toList();
        if (fuzzy.size() == 1) {
            CodeValue only = fuzzy.getFirst();
            boolean qualified = normalised.contains(normalise(only.label()));
            // extra words around a master name are a guess a person confirms, so it stays under the pre-accept level
            return new CrosswalkEntry(value, only.code(), (qualified ? "contains " : "similar to ") + only.label(),
                    qualified ? 0.6 : 0.75);
        }
        List<CodeValue> prefixed = codes.stream()
                .filter(c -> !value.isEmpty() && Character.isLetter(value.charAt(0)))
                .filter(c -> c.code().toUpperCase(Locale.ROOT).startsWith(value.toUpperCase(Locale.ROOT)))
                .toList();
        if (prefixed.size() == 1) {
            return new CrosswalkEntry(value, prefixed.getFirst().code(), "abbreviation of " + prefixed.getFirst().code(), 0.7);
        }
        return new CrosswalkEntry(value, null, "no match: choose a platform code", 0.0);
    }

    private Optional<Scored> best(Collection<ColumnProfile> columns, Target target, Learned learned, String prefix) {
        return columns.stream()
                .map(c -> score(c, target, learned, prefix))
                .max(Comparator.comparingDouble(Scored::score));
    }

    Scored score(ColumnProfile column, Target target, Learned learned, String benefitPrefix) {
        List<String> words = tokens(column.name());
        if (target.excludes() != null && target.excludes().stream().anyMatch(words::contains)) {
            return new Scored(column.name(), target, 0, "excluded word");
        }
        if ("benefit".equals(target.group()) && (benefitPrefix == null || !words.getFirst().equals(benefitPrefix))) {
            return new Scored(column.name(), target, 0, "not a column of the scheme's group");
        }
        if ("empty".equals(column.inferredType())) {
            return new Scored(column.name(), target, 0, "no values");
        }
        if (target.target().endsWith(".name") && "integer".equals(column.inferredType())) {
            return new Scored(column.name(), target, 0, "values are numbers, not names");
        }
        double score = 0;
        List<String> reasons = new ArrayList<>();
        boolean keywords = target.keywords() != null && !target.keywords().isEmpty()
                && target.keywords().stream().allMatch(group -> group.stream().anyMatch(k -> matches(words, k)));
        if (keywords) {
            score += 0.6 + 0.05 * (target.keywords().size() - 1);
            reasons.add("column name");
        }
        String kind = target.valueKind();
        if ("date".equals(kind)) {
            if (!"date".equals(column.inferredType())) {
                return new Scored(column.name(), target, 0, "values are not dates");
            }
            score += 0.15;
            reasons.add("dates in " + column.dateFormat());
        } else if ("number".equals(kind)) {
            if (!Set.of("integer", "decimal").contains(column.inferredType())) {
                return new Scored(column.name(), target, 0, "values are not numbers");
            }
            score += 0.1;
        } else if ("national-id".equals(kind)) {
            if (!"national-id".equals(column.inferredType()) && !keywords) {
                return new Scored(column.name(), target, 0, "values are not national IDs");
            }
            if ("national-id".equals(column.inferredType())) {
                score += keywords ? 0.3 : 0.8;
                reasons.add("12-digit national ID numbers: kept only as a keyed hash and last 4 digits");
            }
        } else if ("mobile".equals(kind) && "mobile".equals(column.inferredType())) {
            score += keywords ? 0.3 : 0.75;
            reasons.add("10-digit mobile numbers");
        }
        if (target.codeSet() != null && column.distinct() > 0) {
            double coverage = coverage(column, target.codeSet(), learned);
            if (coverage >= 0.999) {
                score += keywords ? 0.3 : 0.7;
                reasons.add("values match " + target.codeSet() + " codes");
            } else if (coverage >= 0.5) {
                score += 0.15;
            } else if (!keywords) {
                score = 0;
            }
        }
        List<LearnedMapping> sameTarget = learned.mappings().stream()
                .filter(m -> m.targetKey().equals(target.key()))
                .toList();
        List<String> sameName = sources(sameTarget.stream().filter(m -> m.sourceField().equals(column.name())));
        if (!sameName.isEmpty()) {
            score += 0.3 + 0.05 * (sameName.size() - 1);
            reasons.add("same name used by " + String.join(", ", sameName) + " source");
        } else {
            List<LearnedMapping> similar = sameTarget.stream()
                    .filter(m -> similarName(column.name(), m.sourceField()))
                    .toList();
            List<String> similarSources = sources(similar.stream());
            if (!similarSources.isEmpty()) {
                score += 0.2 + 0.05 * (similarSources.size() - 1);
                reasons.add("similar name " + similar.getFirst().sourceField() + " used by "
                        + String.join(", ", similarSources) + " source");
            }
        }
        if (column.dateFormat() != null && "date".equals(target.valueKind())) {
            List<String> samePattern = sources(sameTarget.stream()
                    .filter(m -> ("date:" + column.dateFormat()).equalsIgnoreCase(m.conversion())));
            if (!samePattern.isEmpty()) {
                score += 0.05;
                reasons.add("date pattern " + column.dateFormat() + " as in " + String.join(", ", samePattern) + " source");
            }
        }
        // A file with both a village name and a village code column: the code column takes the code target, whatever
        // the names could be crosswalked to or earlier sources did. The name column then falls to the name target.
        if (target.codeSet() != null && column.distinct() > 0 && metadata.liveCodeSet(target.codeSet())) {
            score = allLiveCodes(column, target.codeSet()) ? score + 0.05 : Math.min(score, 0.9);
        }
        return new Scored(column.name(), target, Math.min(score, 1.0),
                reasons.isEmpty() ? "weak signal" : String.join(", ", reasons));
    }

    private boolean allLiveCodes(ColumnProfile column, String codeSet) {
        return !column.topValues().isEmpty() && column.topValues().stream()
                .allMatch(v -> metadata.codes(codeSet, v.value()).stream().anyMatch(c -> c.code().equals(v.value())));
    }

    /** Share of the column's distinct values a crosswalk suggestion can map. */
    private double coverage(ColumnProfile column, String codeSet, Learned learned) {
        boolean live = metadata.liveCodeSet(codeSet);
        List<CodeValue> codes = live ? List.of() : metadata.codes(codeSet);
        long mapped = column.topValues().stream()
                .filter(v -> suggestValue(v.value(), codeSet, live ? metadata.codes(codeSet, v.value()) : codes,
                        learned).targetValue() != null)
                .count();
        return column.topValues().isEmpty() ? 0 : (double) mapped / column.topValues().size();
    }

    // ---- learning from approved packs ------------------------------------------------------------------------

    /** One mapping rule of an approved pack, with the source it belongs to. */
    record LearnedMapping(String sourceField, String targetKey, String conversion, String source) {
    }

    /** A code value approved packs crosswalked onto {@code code}, and the sources that agree on it. */
    record KnownValue(String code, List<String> sources) {
    }

    record Learned(List<LearnedMapping> mappings, Map<String, Map<String, KnownValue>> crosswalkValues) {
        Map<String, KnownValue> values(String codeSet) {
            return crosswalkValues.getOrDefault(codeSet, Map.of());
        }
    }

    /** {@code packs} are the approved (active) packs; a pending or rejected pack is never learned from. */
    Learned learn(Collection<LoadedStatePack> packs) {
        List<LearnedMapping> mappings = new ArrayList<>();
        Map<String, Map<String, KnownValue>> values = new HashMap<>();
        for (LoadedStatePack pack : packs) {
            String packId = pack.manifest().packId();
            for (var mapping : pack.mappings()) {
                if (mapping.entities() == null) {
                    continue;
                }
                String source = mapping.sourceId() == null || mapping.sourceId().isBlank() ? packId : mapping.sourceId();
                mapping.entities().forEach((entity, rules) -> {
                    for (MappingRule rule : rules) {
                        mappings.add(new LearnedMapping(rule.sourceField(), entity + ":" + rule.target(),
                                rule.conversion(), source));
                        var target = metadata.target(entity, rule.target());
                        if (rule.crosswalk() != null && target.isPresent() && target.get().codeSet() != null) {
                            var crosswalk = pack.crosswalks().get(rule.crosswalk());
                            if (crosswalk != null && crosswalk.values() != null) {
                                var known = values.computeIfAbsent(target.get().codeSet(), k -> new HashMap<>());
                                crosswalk.values().forEach((from, to) -> known.merge(valueKey(String.valueOf(from)),
                                        new KnownValue(String.valueOf(to), List.of(source)), SuggestionEngine::agree));
                            }
                        }
                    }
                });
            }
        }
        return new Learned(List.copyOf(mappings), values);
    }

    /** The first code learned wins; another source counts only when it agrees on the same code. */
    private static KnownValue agree(KnownValue first, KnownValue next) {
        if (!first.code().equals(next.code()) || first.sources().containsAll(next.sources())) {
            return first;
        }
        List<String> sources = new ArrayList<>(first.sources());
        sources.addAll(next.sources());
        return new KnownValue(first.code(), List.copyOf(sources));
    }

    private static List<String> sources(java.util.stream.Stream<LearnedMapping> mappings) {
        return mappings.map(LearnedMapping::source).distinct().sorted().toList();
    }

    private static String valueKey(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    /** A column-name variant: same words in another spelling, abbreviated words, or a near-identical name. */
    static boolean similarName(String a, String b) {
        if (a.equals(b)) {
            return false;
        }
        List<String> left = tokens(a);
        List<String> right = tokens(b);
        String joinedLeft = String.join("", left);
        String joinedRight = String.join("", right);
        if (joinedLeft.equals(joinedRight)) {
            return true;
        }
        if (left.size() == right.size()) {
            boolean abbreviated = true;
            for (int i = 0; i < left.size(); i++) {
                String x = left.get(i);
                String y = right.get(i);
                if (!x.equals(y) && !(Math.min(x.length(), y.length()) >= 2 && (x.startsWith(y) || y.startsWith(x)))) {
                    abbreviated = false;
                    break;
                }
            }
            if (abbreviated) {
                return true;
            }
        }
        return similarity(joinedLeft, joinedRight) >= 0.75;
    }

    // ---- text helpers -------------------------------------------------------------------------------------------

    record Scored(String column, Target target, double score, String basis) {
    }

    /** Column-name words: split at non-alphanumerics and camelCase, lower case. */
    static List<String> tokens(String column) {
        String spaced = column.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        List<String> words = new ArrayList<>();
        for (String word : spaced.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words.isEmpty() ? List.of(column.toLowerCase(Locale.ROOT)) : words;
    }

    private static boolean matches(List<String> words, String keyword) {
        return words.stream().anyMatch(w -> w.equals(keyword) || (keyword.length() >= 4 && w.startsWith(keyword)));
    }

    private static String normalise(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String acronym(String name) {
        StringBuilder initials = new StringBuilder();
        for (String word : name.split("[^A-Za-z0-9]+")) {
            if (!word.isEmpty()) {
                initials.append(Character.toUpperCase(word.charAt(0)));
            }
        }
        return initials.toString();
    }

    /** 1 - normalised Levenshtein distance. */
    static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return 1.0 - (double) previous[b.length()] / Math.max(a.length(), b.length());
    }

    private static String columnOf(Map<String, List<Scored>> assigned, String targetKey) {
        return assigned.entrySet().stream()
                .filter(e -> e.getValue().stream().anyMatch(s -> s.target().key().equals(targetKey)))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private static int columnOrder(Map<String, ColumnProfile> columns, String column) {
        return new ArrayList<>(columns.keySet()).indexOf(column);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
