package gov.uddp.shaping.parsers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import gov.uddp.shaping.api.ParseResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Shared logic of {@code name-relation@1.0} and {@code name-relation-age@1.0}: splits "Name S/O Relation Name Age 67"
 * cells. All word lists come from {@code <platform>/reference-data/shaping/*.yaml}; a pack may add relation markers
 * through the parse config {@code extraMarkers} (marker to relation type).
 */
@Component
public class NameRelationParsing implements gov.uddp.shaping.admin.WordListConsumer, gov.uddp.shaping.api.RelationMarkers {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern AGE_LABELLED = Pattern.compile(
            "(?i)\\(?\\s*\\bage\\s*[-:.]?\\s*(\\d{1,3})(?:\\s*(?:yrs?|years?|y)\\b)?\\s*\\)?");
    private static final Pattern AGE_SUFFIXED = Pattern.compile("(?i)\\(?\\s*\\b(\\d{1,3})\\s*(?:yrs?|years?|y)\\b\\s*\\)?");
    private static final String EDGE = "[\\s,;]+";

    private record Marker(String form, String relation, String gender, boolean clean, Pattern pattern) {
        static Marker of(String form, String relation, String gender) {
            Pattern pattern = Pattern.compile(
                    "(?<![\\p{L}\\p{N}])" + Pattern.quote(form) + "(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
            return new Marker(form, relation, gender, !form.chars().allMatch(Character::isLetter), pattern);
        }
    }

    private final Path dir;
    private volatile List<Marker> markers = List.of();
    private volatile Set<String> deceased = Set.of();
    private volatile Set<String> honorifics = Set.of();
    private volatile Map<String, String> variants = Map.of();

    @org.springframework.beans.factory.annotation.Autowired
    public NameRelationParsing(@Value("${uddp.platform-directory:../../platform}") String platformDirectory) {
        this(Path.of(platformDirectory));
    }

    public NameRelationParsing(Path platformDirectory) {
        this.dir = platformDirectory.resolve("reference-data").resolve("shaping");
        reloadWordLists();
    }

    @Override
    public void reloadWordLists() {
        List<Marker> newMarkers = new ArrayList<>();
        for (JsonNode entry : read(dir, "relation-markers.yaml").path("markers")) {
            String gender = entry.hasNonNull("gender") ? entry.get("gender").asText() : null;
            for (JsonNode form : entry.path("forms")) {
                newMarkers.add(Marker.of(form.asText(), entry.get("relation").asText(), gender));
            }
        }
        Set<String> newDeceased = new HashSet<>();
        read(dir, "deceased-markers.yaml").path("markers").forEach(n -> newDeceased.add(fold(n.asText())));
        Set<String> newHonorifics = new HashSet<>();
        read(dir, "honorifics.yaml").path("honorifics").forEach(n -> newHonorifics.add(fold(n.asText())));
        Map<String, String> newVariants = new HashMap<>();
        read(dir, "name-variants.yaml").path("variants").fields().forEachRemaining(e ->
                e.getValue().forEach(spelling -> newVariants.put(fold(spelling.asText()), e.getKey())));
        markers = List.copyOf(newMarkers);
        deceased = newDeceased;
        honorifics = newHonorifics;
        variants = newVariants;
    }

    private static JsonNode read(Path dir, String file) {
        try {
            return YAML.readTree(dir.resolve(file).toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read shaping reference data " + dir.resolve(file), e);
        }
    }

    /** True when the cell holds a name followed by a relation marker ("Name S/O Father"); used by the Studio profiler. */
    public boolean hasRelationMarker(String value) {
        return parse(value, Map.of(), false).values().containsKey("relation.type");
    }

    ParseResult parse(String value, Map<String, Object> config, boolean withAge) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        String text = value == null ? "" : value;
        if (withAge) {
            text = extractAge(text, out);
        }
        text = trimEdges(text);
        if (text.isEmpty()) {
            warnings.add("empty value");
            return new ParseResult(out, 0.0, warnings);
        }

        List<Marker> candidates = new ArrayList<>(markers);
        if (config != null && config.get("extraMarkers") instanceof Map<?, ?> extra) {
            extra.forEach((form, relation) -> candidates.add(Marker.of(String.valueOf(form), String.valueOf(relation), null)));
        }
        Marker found = null;
        Matcher foundAt = null;
        for (Marker marker : candidates) {
            Matcher m = marker.pattern().matcher(text);
            while (m.find()) {
                if (!acceptable(text, m, marker)) {
                    continue;
                }
                if (foundAt == null || m.start() < foundAt.start()
                        || (m.start() == foundAt.start() && m.end() > foundAt.end())) {
                    found = marker;
                    foundAt = m;
                }
                break;
            }
        }

        if (found == null) {
            putName(out, text);
            warnings.add("no relation marker found");
            return new ParseResult(out, 0.6, warnings);
        }
        putName(out, trimEdges(text.substring(0, foundAt.start())));
        String relationPart = trimEdges(text.substring(foundAt.end()));
        boolean isDeceased = false;
        while (true) {
            String[] parts = relationPart.split("\\s+", 2);
            if (!parts[0].isEmpty() && deceased.contains(fold(parts[0]))) {
                isDeceased = true;
                relationPart = parts.length > 1 ? trimEdges(parts[1]) : "";
            } else {
                break;
            }
        }
        out.put("relation.type", found.relation());
        out.put("relation.deceased", isDeceased);
        if (relationPart.isEmpty()) {
            warnings.add("relation marker without a relation name");
        } else {
            out.put("relation.name", relationPart);
            out.put("relation.nameNormalised", normalise(relationPart));
        }
        if (found.gender() != null) {
            out.put("inferredGender", found.gender());
        }
        return new ParseResult(out, found.clean() ? 1.0 : 0.8, warnings);
    }

    /** A marker is real after some name text, or first in a cell that holds only the relation ("w/o Sonam"). */
    private static boolean acceptable(String text, Matcher m, Marker marker) {
        if (m.start() > 0) {
            return !text.substring(0, m.start()).isBlank();
        }
        return marker.clean() && m.end() < text.length() && Character.isWhitespace(text.charAt(m.end()));
    }

    private static String extractAge(String text, Map<String, Object> out) {
        for (Pattern pattern : List.of(AGE_LABELLED, AGE_SUFFIXED)) {
            Matcher m = pattern.matcher(text);
            if (m.find()) {
                int years = Integer.parseInt(m.group(1));
                if (years <= 120) {
                    out.put("age.years", years);
                    return text.substring(0, m.start()) + " " + text.substring(m.end());
                }
            }
        }
        return text;
    }

    private void putName(Map<String, Object> out, String name) {
        if (!name.isEmpty()) {
            out.put("name", name);
            out.put("nameNormalised", normalise(name));
        }
    }

    String normalise(String name) {
        List<String> tokens = new ArrayList<>();
        for (String token : name.split("\\s+")) {
            String folded = fold(token);
            if (folded.isEmpty() || honorifics.contains(folded)) {
                continue;
            }
            tokens.add(variants.getOrDefault(folded, folded.toUpperCase(Locale.ROOT)));
        }
        return tokens.isEmpty() ? fold(name).toUpperCase(Locale.ROOT) : String.join(" ", tokens);
    }

    private static String fold(String token) {
        return token.replace(".", "").strip().toLowerCase(Locale.ROOT);
    }

    private static String trimEdges(String text) {
        return text.strip().replaceAll("^" + EDGE + "|" + EDGE + "$", "").strip();
    }
}
