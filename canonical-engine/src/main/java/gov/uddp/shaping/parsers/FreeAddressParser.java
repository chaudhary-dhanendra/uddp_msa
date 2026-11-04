package gov.uddp.shaping.parsers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import gov.uddp.core.geography.GeographyService;
import gov.uddp.core.geography.GeographyService.GeoEntity;
import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.ParseResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code free-address@1.0} reads a free-text address ("Sakti / Kharu / Leh", "Vill Choglamsar, Leh") and names the
 * LGD village, block and district it points at. Prefixes and suffixes come from
 * {@code reference-data/shaping/address-tokens.yaml}, spelling variants from {@code geo-aliases.yaml} (a pack may add
 * {@code aliases} in the parse config: LGD spelling to variants). Matching reads the geography master only; the
 * {@code geography.*.name} outputs then go through the ordinary canonical geography resolver.
 *
 * <p>Confidence: 1.0 when every level of the chosen chain is named in the text, 0.8 when the chain is the single
 * best one but some level is only implied by the master, 0.5 when several chains tie (names are then left out and
 * {@code geography.suggestions} lists them).
 */
@Component
public class FreeAddressParser implements FieldParser, gov.uddp.shaping.admin.WordListConsumer {

    static final String VILLAGE_NAME = "geography.village.name";
    static final String BLOCK_NAME = "geography.block.name";
    static final String DISTRICT_NAME = "geography.district.name";
    static final String SUGGESTIONS = "geography.suggestions";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern SEPARATORS = Pattern.compile("[,/;\\-]");
    private static final int MAX_PHRASE_WORDS = 3;
    private static final int PAGE = 200;

    private record Chain(GeoEntity village, GeoEntity block, GeoEntity district) {
        List<GeoEntity> levels() {
            List<GeoEntity> out = new ArrayList<>();
            for (GeoEntity e : new GeoEntity[] {village, block, district}) {
                if (e != null) {
                    out.add(e);
                }
            }
            return out;
        }

        String label() {
            return levels().stream().map(GeoEntity::name).reduce((a, b) -> a + ", " + b).orElse("");
        }

        String key() {
            return levels().stream().map(e -> e.entityType() + ":" + e.code()).reduce((a, b) -> a + "|" + b).orElse("");
        }
    }

    private final GeographyService geography;
    private final Path dir;
    private volatile List<String> prefixes = List.of();
    private volatile List<Pattern> suffixes = List.of();
    private volatile Map<String, String> aliases = Map.of();

    @Autowired
    public FreeAddressParser(GeographyService geography,
                             @Value("${uddp.platform-directory:../../platform}") String platformDirectory) {
        this(geography, Path.of(platformDirectory));
    }

    public FreeAddressParser(GeographyService geography, Path platformDirectory) {
        this.geography = geography;
        this.dir = platformDirectory.resolve("reference-data").resolve("shaping");
        reloadWordLists();
    }

    @Override
    public void reloadWordLists() {
        JsonNode tokens = read(dir.resolve("address-tokens.yaml"));
        List<String> newPrefixes = new ArrayList<>();
        List<Pattern> newSuffixes = new ArrayList<>();
        tokens.path("prefixes").forEach(n -> newPrefixes.add(fold(n.asText())));
        tokens.path("suffixes").forEach(n -> newSuffixes.add(Pattern.compile(n.asText(), Pattern.CASE_INSENSITIVE)));
        Map<String, String> newAliases = new HashMap<>();
        addAliases(read(dir.resolve("geo-aliases.yaml")).path("aliases"), newAliases);
        prefixes = List.copyOf(newPrefixes);
        suffixes = List.copyOf(newSuffixes);
        aliases = newAliases;
    }

    @Override
    public String name() {
        return "free-address";
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public ParseResult parse(String value, Map<String, Object> config) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> allAliases = aliases;
        if (config != null && config.get("aliases") instanceof Map<?, ?> extra) {
            allAliases = new HashMap<>(aliases);
            for (Map.Entry<?, ?> e : extra.entrySet()) {
                for (Object spelling : e.getValue() instanceof Iterable<?> list ? list : List.of(e.getValue())) {
                    allAliases.put(fold(String.valueOf(spelling)), fold(String.valueOf(e.getKey())));
                }
            }
        }
        Set<String> phrases = phrases(value, allAliases);
        if (phrases.isEmpty()) {
            warnings.add("empty value");
            return new ParseResult(out, 0.0, warnings);
        }

        Map<String, Chain> chains = new LinkedHashMap<>();
        Map<String, GeoEntity> districts = new HashMap<>();
        for (GeoEntity d : all("DISTRICT", null)) {
            districts.put(fold(d.name()), d);
        }
        for (String phrase : phrases) {
            for (GeoEntity village : exact("VILLAGE", phrase)) {
                add(chains, chainOf(village));
            }
            for (String type : List.of("BLOCK", "SUB_DISTRICT")) {
                for (GeoEntity block : exact(type, phrase)) {
                    add(chains, chainOf(block));
                }
            }
            GeoEntity district = districts.get(phrase);
            if (district != null) {
                add(chains, new Chain(null, null, district));
            }
        }
        if (chains.isEmpty()) {
            warnings.add("no geography name found in address");
            return new ParseResult(out, 0.0, warnings);
        }

        int best = -1;
        List<Chain> top = new ArrayList<>();
        for (Chain chain : chains.values()) {
            int score = score(chain, phrases);
            if (score > best) {
                best = score;
                top.clear();
            }
            if (score == best) {
                top.add(chain);
            }
        }
        if (top.size() > 1) {
            out.put(SUGGESTIONS, top.stream().map(Chain::label).toList());
            warnings.add("address is ambiguous: " + top.size() + " candidate places");
            return new ParseResult(out, 0.5, warnings);
        }
        Chain chosen = top.get(0);
        if (chosen.village() != null) {
            out.put(VILLAGE_NAME, chosen.village().name());
        }
        if (chosen.block() != null) {
            out.put(BLOCK_NAME, chosen.block().name());
        }
        if (chosen.district() != null) {
            out.put(DISTRICT_NAME, chosen.district().name());
        }
        boolean allNamed = chosen.levels().stream().allMatch(e -> phrases.contains(fold(e.name())));
        return new ParseResult(out, allNamed ? 1.0 : 0.8, warnings);
    }

    private static int score(Chain chain, Set<String> phrases) {
        int named = (int) chain.levels().stream().filter(e -> phrases.contains(fold(e.name()))).count();
        return named * 10 + chain.levels().size();
    }

    private static void add(Map<String, Chain> chains, Chain chain) {
        chains.putIfAbsent(chain.key(), chain);
    }

    /** The place with its ancestors folded into village / block (or sub-district) / district slots. */
    private Chain chainOf(GeoEntity entity) {
        GeoEntity village = null;
        GeoEntity block = null;
        GeoEntity district = null;
        List<GeoEntity> all = new ArrayList<>(geography.ancestors(entity.entityType(), entity.code()));
        all.add(entity);
        for (GeoEntity e : all) {
            switch (e.entityType()) {
                case "VILLAGE" -> village = e;
                case "BLOCK" -> block = e;
                case "SUB_DISTRICT" -> block = block == null ? e : block;
                case "DISTRICT" -> district = e;
                default -> { }
            }
        }
        return new Chain(village, block, district);
    }

    private List<GeoEntity> exact(String type, String phrase) {
        return all(type, phrase).stream().filter(e -> fold(e.name()).equals(phrase)).toList();
    }

    private List<GeoEntity> all(String type, String text) {
        List<GeoEntity> items = new ArrayList<>();
        for (int page = 0;; page++) {
            var result = geography.search(type, text, null, null, "ACTIVE", page, PAGE);
            items.addAll(result.items());
            if (page + 1 >= result.totalPages()) {
                return items;
            }
        }
    }

    /** Folded candidate phrases: each segment, and every run of up to three words inside it, after clean-up. */
    private Set<String> phrases(String value, Map<String, String> allAliases) {
        Set<String> out = new LinkedHashSet<>();
        if (value == null) {
            return out;
        }
        String text = value;
        for (Pattern suffix : suffixes) {
            text = suffix.matcher(text).replaceAll(" ");
        }
        for (String segment : SEPARATORS.split(text)) {
            List<String> words = new ArrayList<>(List.of(segment.strip().split("\\s+")));
            while (!words.isEmpty() && (words.get(0).isEmpty() || prefixes.contains(fold(words.get(0))))) {
                words.remove(0);
            }
            for (int from = 0; from < words.size(); from++) {
                for (int to = from + 1; to <= Math.min(words.size(), from + MAX_PHRASE_WORDS); to++) {
                    String phrase = fold(String.join(" ", words.subList(from, to)));
                    if (!phrase.isEmpty()) {
                        out.add(allAliases.getOrDefault(phrase, phrase));
                    }
                }
            }
        }
        return out;
    }

    private static void addAliases(JsonNode node, Map<String, String> into) {
        node.fields().forEachRemaining(e ->
                e.getValue().forEach(spelling -> into.put(fold(spelling.asText()), fold(e.getKey()))));
    }

    private static JsonNode read(Path file) {
        try {
            return YAML.readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read shaping reference data " + file, e);
        }
    }

    private static String fold(String token) {
        return token.replace(".", "").strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
