package gov.uddp.core.statepack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.statepack.StatePackAssets.*;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class StatePackLoader {
    private final ObjectMapper yaml;
    private final Path root;
    private final boolean fixtureStatePacksEnabled;
    private volatile Map<String, LoadedStatePack> fixturePacks = Map.of();
    private DatabaseStatePackRepository database;
    private gov.uddp.shaping.api.OperationLookup operations;

    public StatePackLoader(StatePackProperties properties) {
        this.yaml = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
        this.root = Path.of(properties.getStatePackDirectory()).toAbsolutePath().normalize();
        this.fixtureStatePacksEnabled = properties.isFixtureStatePacksEnabled();
    }

    /**
     * Operational resolution is database-authoritative.  YAML is deliberately only read through the explicit
     * {@link #load(Path)} and {@link #loadAll(Path)} fixture/bootstrap APIs below.
     */
    @PostConstruct
    void initialise() {
        if (fixtureStatePacksEnabled && Files.isDirectory(root)) {
            fixturePacks = loadAll(root);
        }
    }

    /**
     * Retained for setup callers. Database reads are live, so there is no filesystem registry to refresh.
     */
    public synchronized void reload() {
        if (fixtureStatePacksEnabled) {
            fixturePacks = Files.isDirectory(root) ? loadAll(root) : Map.of();
        }
    }

    /** The directory this registry loads from (also handed to the Python matching module). */
    public Path directory() {
        return root;
    }

    public Collection<LoadedStatePack> all() {
        Map<String, LoadedStatePack> packs = new TreeMap<>(fixturePacks);
        if (database != null) {
            database.all().forEach(pack -> packs.put(pack.manifest().packId(), pack));
        }
        return List.copyOf(packs.values());
    }

    /** Layout operations and field parsers a source declares must resolve here when a pack loads. */
    @Autowired(required = false)
    public void setOperations(gov.uddp.shaping.api.OperationLookup operations) { this.operations = operations; }

    @Autowired(required = false)
    void setDatabase(DatabaseStatePackRepository database) { this.database = database; }

    public Optional<LoadedStatePack> find(String state) {
        if (database != null) {
            Optional<LoadedStatePack> stored = database.find(state);
            if (stored.isPresent()) {
                return stored;
            }
        }
        return fixturePacks.values().stream()
                .filter(pack -> pack.manifest().packId().equalsIgnoreCase(state)
                        || pack.manifest().stateCode().equalsIgnoreCase(state))
                .findFirst();
    }

    public Map<String, LoadedStatePack> loadAll(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new StatePackLoadException("State Pack directory does not exist: " + directory);
        }
        try (var entries = Files.list(directory)) {
            Map<String, LoadedStatePack> result = new TreeMap<>();
            for (Path pack : entries.filter(Files::isDirectory).toList()) {
                LoadedStatePack value = load(pack);
                if (result.put(value.manifest().packId(), value) != null) {
                    throw new StatePackLoadException("Duplicate packId: " + value.manifest().packId());
                }
            }
            return Map.copyOf(result);
        } catch (IOException e) {
            throw new StatePackLoadException("Cannot read State Pack directory: " + directory, e);
        }
    }

    public LoadedStatePack load(Path packDirectory) {
        StatePackManifest manifest = read(packDirectory.resolve("state.yaml"), StatePackManifest.class);
        require(manifest.packId(), "packId", packDirectory);
        require(manifest.stateCode(), "stateCode", packDirectory);
        require(manifest.version(), "version", packDirectory);
        require(manifest.commonModelVersion(), "commonModelVersion", packDirectory);
        require(manifest.status(), "status", packDirectory);

        List<SourceDefinition> sources = readAll(packDirectory, manifest.sources(), SourceDefinition.class);
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            SourceDefinition source = sources.get(sourceIndex);
            require(source.sourceId(), "sourceId", packDirectory);
            require(source.sourceType(), "sourceType", packDirectory);
            if (source.hopTemplate() == null || !source.hopTemplate().matches("generic-[a-z0-9-]+\\.hpl")) {
                throw new StatePackLoadException("Source " + source.sourceId() + " must use a generic Hop template");
            }
            if (source.database() && (source.connectionRef() == null || source.connectionRef().isBlank()
                    || (isBlank(source.table()) && isBlank(source.query())))) {
                throw new StatePackLoadException("Database source " + source.sourceId()
                        + " needs a connectionRef and a table or query");
            }
            if (source.changeColumn() != null) {
                if (!source.database()) {
                    throw new StatePackLoadException("Source " + source.sourceId()
                            + ": changeColumn applies to database sources only");
                }
                if (!source.changeColumn().matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
                    throw new StatePackLoadException("Source " + source.sourceId()
                            + ": changeColumn must be a plain column name");
                }
            }
            validateShaping(source, packDirectory.resolve(manifest.sources().get(sourceIndex)));
        }
        Set<String> sourceIds = sources.stream()
                .map(SourceDefinition::sourceId)
                .collect(java.util.stream.Collectors.toSet());

        List<MappingDefinition> mappings = readAll(packDirectory, manifest.mappings(), MappingDefinition.class);
        Map<String, CrosswalkDefinition> crosswalks = new HashMap<>();
        for (CrosswalkDefinition c : readAll(packDirectory, manifest.crosswalks(), CrosswalkDefinition.class)) {
            require(c.id(), "crosswalk id", packDirectory);
            if (crosswalks.put(c.id(), c) != null) {
                throw new StatePackLoadException("Duplicate crosswalk: " + c.id());
            }
        }
        for (MappingDefinition mapping : mappings) {
            if (!sourceIds.contains(mapping.sourceId())) {
                throw new StatePackLoadException("Mapping references unknown source: " + mapping.sourceId());
            }
            for (List<MappingRule> rules : safe(mapping.entities()).values()) {
                for (MappingRule rule : rules) {
                    if (rule.crosswalk() != null && !crosswalks.containsKey(rule.crosswalk())) {
                        throw new StatePackLoadException("Mapping references unknown crosswalk: " + rule.crosswalk());
                    }
                }
            }
        }

        List<ValidationDefinition> validations = readAll(packDirectory, manifest.validation(), ValidationDefinition.class);
        for (ValidationDefinition definition : validations) {
            validateRules(definition, packDirectory);
        }
        List<MatchingDefinition> matching = readAll(packDirectory, manifest.matching(), MatchingDefinition.class);
        for (MatchingDefinition definition : matching) {
            validateThresholds(definition.thresholds());
        }
        List<SurvivorshipDefinition> survivorship =
                readAll(packDirectory, manifest.survivorship(), SurvivorshipDefinition.class);
        List<StateField> stateFields = new ArrayList<>();
        for (StateFieldsDefinition definition
                : readAll(packDirectory, manifest.extensions(), StateFieldsDefinition.class)) {
            if (definition.entity() != null && !"Resident".equals(definition.entity())) {
                throw new StatePackLoadException("State fields are supported for Resident only, not "
                        + definition.entity() + " in " + packDirectory);
            }
            stateFields.addAll(safeList(definition.fields()));
        }
        validateStateFields(stateFields, packDirectory);
        return new LoadedStatePack(manifest, List.copyOf(sources), List.copyOf(mappings), Map.copyOf(crosswalks),
                List.copyOf(validations), List.copyOf(matching), List.copyOf(survivorship), stateFields);
    }

    /** Keys are plain names (no dots: Golden stores them as {@code stateFields.<stateCode>.<key>}), unique, typed. */
    public static void validateStateFields(List<StateField> fields, Path pack) {
        Set<String> keys = new HashSet<>();
        for (StateField field : fields) {
            if (field == null || field.key() == null || !field.key().matches(StateField.KEY_PATTERN)) {
                throw new StatePackLoadException("State field key must start with a lower-case letter and use only"
                        + " letters, digits and _ in " + pack + ": " + (field == null ? null : field.key()));
            }
            if (field.key().startsWith("uddp")) {
                throw new StatePackLoadException("State field key " + field.key() + " is reserved in " + pack);
            }
            if (!StateField.TYPES.contains(field.typeOrDefault())) {
                throw new StatePackLoadException("State field " + field.key() + " has unsupported type "
                        + field.type() + " in " + pack + " (text, number, integer, boolean or date)");
            }
            if (!keys.add(field.key())) {
                throw new StatePackLoadException("Duplicate state field " + field.key() + " in " + pack);
            }
        }
    }

    private void validateShaping(SourceDefinition source, Path file) {
        for (Map<String, Object> step : safeList(source.layout())) {
            String op = String.valueOf(step.get("op"));
            try {
                if (operations == null) {
                    throw new IllegalArgumentException("no operation registry");
                }
                operations.operation(op);
            } catch (IllegalArgumentException e) {
                throw new StatePackLoadException("Unknown layout operation " + op + " in " + file + " (layout): "
                        + e.getMessage());
            }
        }
        for (Map<String, Object> step : safeList(source.parse())) {
            String name = String.valueOf(step.get("parser"));
            try {
                if (operations == null) {
                    throw new IllegalArgumentException("no operation registry");
                }
                operations.parser(name);
            } catch (IllegalArgumentException e) {
                throw new StatePackLoadException("Unknown field parser " + name + " in " + file + " (parse): "
                        + e.getMessage());
            }
        }
    }

    private void validateRules(ValidationDefinition definition, Path pack) {
        for (ValidationRule rule : definition.rules() == null ? List.<ValidationRule>of() : definition.rules()) {
            if (rule.field() == null || rule.field().isBlank()) {
                throw new StatePackLoadException("Validation rule without field in " + pack);
            }
            if (rule.severity() == null || !rule.severity().trim().matches("(?i)error|warning|send_to_steward")) {
                throw new StatePackLoadException("Validation rule for " + rule.field() + " in " + pack
                        + " must have severity error, warning or send_to_steward");
            }
            if (rule.type() != null && !rule.type().matches(
                    "REQUIRED|ALLOWED_VALUES|PATTERN|DATE_RANGE|REFERENCE_LIST|UNIQUE_WITHIN_BATCH|CROSS_FIELD")) {
                throw new StatePackLoadException("Unsupported validation type " + rule.type() + " in " + pack);
            }
        }
    }

    private void validateThresholds(Thresholds t) {
        if (t == null || t.autoMatch() == null || t.possibleMatch() == null || t.autoMatch() > 1
                || t.possibleMatch() < 0 || t.autoMatch() < t.possibleMatch()) {
            throw new StatePackLoadException("Matching thresholds must be within 0..1 and autoMatch >= possibleMatch");
        }
    }

    private <T> List<T> readAll(Path root, List<String> paths, Class<T> type) {
        return safeList(paths).stream().map(p -> read(root.resolve(p), type)).toList();
    }

    private <T> T read(Path path, Class<T> type) {
        requireFile(path.getParent(), path.getFileName().toString());
        try {
            return yaml.readValue(path.toFile(), type);
        } catch (IOException e) {
            throw new StatePackLoadException("Invalid YAML " + path + ": " + e.getMessage(), e);
        }
    }

    private void requireFile(Path parent, String name) {
        if (name == null || !Files.isRegularFile(parent.resolve(name))) {
            throw new StatePackLoadException("Missing referenced State Pack file: " + parent.resolve(name));
        }
    }

    private void require(String value, String field, Path pack) {
        if (value == null || value.isBlank()) {
            throw new StatePackLoadException("Missing " + field + " in " + pack.resolve("state.yaml"));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static <K, V> Map<K, V> safe(Map<K, V> values) {
        return values == null ? Map.of() : values;
    }
}
