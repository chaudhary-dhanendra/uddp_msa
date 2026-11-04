package gov.uddp.shaping.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import gov.uddp.core.rules.ApprovalConflictException;
import gov.uddp.core.studio.StudioNotFoundException;
import gov.uddp.shaping.OperationRegistry;
import gov.uddp.shaping.api.ParseResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The word lists under {@code <platform>/reference-data/shaping/*.yaml}, viewed as groups of values so one editor
 * fits every file: a flat list is one group; a map (name variants, village aliases) is one group per key; a list of
 * objects (relation markers) is one group per object, with its other fields as attributes. A change is proposed as
 * the whole new set of groups and only written, and the parsers told to read the files again, after another user
 * approves it. The comment lines at the top of a file are kept.
 */
@Service
public class WordListService {
    private static final ObjectMapper READER = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper WRITER = new ObjectMapper(new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));
    private static final int MAX_VALUE_LENGTH = 200;

    /** One editable group: {@code section} is the file's top-level key; {@code attrs} are the non-list fields of a marker. */
    public record Group(String section, String key, Map<String, String> attrs, List<String> values) {
    }

    public record WordList(String id, String description, List<Group> groups, UUID pendingChangeId) {
    }

    public record ParserTest(String parser, String value, Map<String, Object> values, double confidence,
                             List<String> warnings) {
    }

    private final Path dir;
    private final ShapingChanges changes;
    private final OperationRegistry registry;
    private final ObjectProvider<WordListConsumer> consumers;

    public WordListService(@Value("${uddp.platform-directory:../../platform}") String platformDirectory,
                           ShapingChanges changes, OperationRegistry registry,
                           ObjectProvider<WordListConsumer> consumers) {
        this.dir = Path.of(platformDirectory, "reference-data", "shaping");
        this.changes = changes;
        this.registry = registry;
        this.consumers = consumers;
    }

    public List<WordList> lists() {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(".yaml")).sorted()
                    .map(f -> list(f.substring(0, f.length() - ".yaml".length()))).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public WordList list(String id) {
        Path file = file(id);
        UUID pending = changes.pending(ShapingChanges.WORD_LIST, id).map(ShapingChanges.Change::changeId).orElse(null);
        return new WordList(id, description(file), groups(readFile(file)), pending);
    }

    /** Proposes the whole new content of one list; nothing is written until someone else approves it. */
    public ShapingChanges.Change propose(String id, List<Group> proposed, String actor) {
        Path file = file(id);
        buildContent(readFile(file), proposed);
        if (changes.pending(ShapingChanges.WORD_LIST, id).isPresent()) {
            throw new ApprovalConflictException("Word list " + id + " already has a change waiting for approval");
        }
        ObjectNode payload = READER.createObjectNode();
        payload.set("groups", READER.valueToTree(proposed));
        return changes.propose(ShapingChanges.WORD_LIST, "UPDATE", id, payload, actor);
    }

    /** Writes an approved change to its file and asks the parsers to read the lists again. */
    void apply(ShapingChanges.Change change) {
        Path file = file(change.target());
        List<Group> proposed = READER.convertValue(change.payload().path("groups"), new TypeReference<List<Group>>() { });
        JsonNode content = buildContent(readFile(file), proposed);
        try {
            Files.writeString(file, leadingComments(file) + WRITER.writeValueAsString(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        consumers.orderedStream().forEach(WordListConsumer::reloadWordLists);
    }

    /** Runs a registered parser on one value with the lists as they are now. */
    public ParserTest test(String parserKey, String value, Map<String, Object> config) {
        if (value == null) {
            throw new IllegalArgumentException("A value is required");
        }
        ParseResult result = registry.parser(parserKey).parse(value, config == null ? Map.of() : config);
        return new ParserTest(parserKey, value, result.values(), result.confidence(),
                result.warnings() == null ? List.of() : result.warnings());
    }

    // ---- file <-> groups ----------------------------------------------------------------------------------------

    private static List<Group> groups(JsonNode root) {
        List<Group> groups = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
            var section = it.next();
            JsonNode value = section.getValue();
            if (value.isObject()) {
                value.fields().forEachRemaining(e -> groups.add(
                        new Group(section.getKey(), e.getKey(), Map.of(), strings(e.getValue()))));
            } else if (isObjectList(value)) {
                int index = 0;
                for (JsonNode item : value) {
                    Map<String, String> attrs = new LinkedHashMap<>();
                    List<String> values = List.of();
                    for (Iterator<Map.Entry<String, JsonNode>> f = item.fields(); f.hasNext(); ) {
                        var field = f.next();
                        if (field.getValue().isArray()) {
                            values = strings(field.getValue());
                        } else {
                            attrs.put(field.getKey(), field.getValue().asText());
                        }
                    }
                    groups.add(new Group(section.getKey(), String.valueOf(++index), attrs, values));
                }
            } else {
                groups.add(new Group(section.getKey(), section.getKey(), Map.of(), strings(value)));
            }
        }
        return groups;
    }

    /** The new file content for the proposed groups, in the shape of the existing file; rejects what does not fit it. */
    private static JsonNode buildContent(JsonNode existing, List<Group> proposed) {
        if (proposed == null || proposed.isEmpty()) {
            throw new IllegalArgumentException("A word list needs at least one group");
        }
        for (Group group : proposed) {
            if (!existing.has(group.section())) {
                throw new IllegalArgumentException("Unknown section: " + group.section());
            }
        }
        ObjectNode content = READER.createObjectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = existing.fields(); it.hasNext(); ) {
            var section = it.next();
            String name = section.getKey();
            List<Group> mine = proposed.stream().filter(g -> name.equals(g.section())).toList();
            if (mine.isEmpty()) {
                throw new IllegalArgumentException("Section " + name + " cannot be removed");
            }
            if (section.getValue().isObject()) {
                ObjectNode map = content.putObject(name);
                for (Group group : mine) {
                    String key = requireText(group.key(), "A group in " + name + " needs a name");
                    if (map.has(key)) {
                        throw new IllegalArgumentException("Duplicate group " + key + " in " + name);
                    }
                    map.set(key, values(name, group.values()));
                }
            } else if (isObjectList(section.getValue())) {
                String valuesField = valuesField(section.getValue());
                ArrayNode list = content.putArray(name);
                for (Group group : mine) {
                    ObjectNode item = list.addObject();
                    (group.attrs() == null ? Map.<String, String>of() : group.attrs()).forEach((k, v) -> {
                        if (v != null && !v.isBlank()) {
                            item.put(k, v.strip());
                        }
                    });
                    if (!item.has("relation")) {
                        throw new IllegalArgumentException("Every marker group needs a relation");
                    }
                    item.set(valuesField, values(name, group.values()));
                }
            } else {
                if (mine.size() != 1) {
                    throw new IllegalArgumentException("Section " + name + " is a single list");
                }
                content.set(name, values(name, mine.getFirst().values()));
            }
        }
        return content;
    }

    private static ArrayNode values(String section, List<String> raw) {
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String value : raw == null ? List.<String>of() : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String text = value.strip();
            if (text.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("A value in " + section + " is longer than " + MAX_VALUE_LENGTH);
            }
            if ("suffixes".equals(section)) {
                try {
                    Pattern.compile(text);
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException("Not a valid regular expression: " + text);
                }
            }
            clean.add(text);
        }
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Every group in " + section + " needs at least one value");
        }
        ArrayNode array = READER.createArrayNode();
        clean.forEach(array::add);
        return array;
    }

    private static String requireText(String text, String message) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return text.strip();
    }

    private static boolean isObjectList(JsonNode node) {
        return node.isArray() && !node.isEmpty() && node.get(0).isObject();
    }

    private static String valuesField(JsonNode objectList) {
        for (Iterator<Map.Entry<String, JsonNode>> f = objectList.get(0).fields(); f.hasNext(); ) {
            var field = f.next();
            if (field.getValue().isArray()) {
                return field.getKey();
            }
        }
        return "forms";
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private Path file(String id) {
        if (id == null || !id.matches("[a-z0-9-]+")) {
            throw new StudioNotFoundException("Unknown word list: " + id);
        }
        Path file = dir.resolve(id + ".yaml");
        if (!Files.isRegularFile(file)) {
            throw new StudioNotFoundException("Unknown word list: " + id);
        }
        return file;
    }

    private static JsonNode readFile(Path file) {
        try {
            return READER.readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** The leading comment lines of a file, {@code #} included, each with its newline. */
    private static String leadingComments(Path file) {
        try {
            StringBuilder out = new StringBuilder();
            for (String line : Files.readAllLines(file)) {
                if (!line.startsWith("#")) {
                    break;
                }
                out.append(line).append('\n');
            }
            return out.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String description(Path file) {
        return leadingComments(file).replaceAll("(?m)^#\\s?", "").strip();
    }
}
