package gov.uddp.core.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import gov.uddp.core.config.StatePackProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Reads generic platform reference YAML; no state-specific lookup exists in Common Core. */
@Component
public class PlatformReferenceData {
    public record Value(String code, String displayName) { }
    public record Dataset(String referenceSet, String version, String description, java.util.List<Value> values) { }
    private final Path root;
    public PlatformReferenceData(StatePackProperties properties) { root = Path.of(properties.getPlatformDirectory(), "reference-data"); }
    public Map<String, Set<String>> values() {
        try (var files = Files.list(root)) {
            var yaml = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
            return files.filter(p -> p.toString().endsWith(".yaml")).map(p -> read(yaml, p)).collect(Collectors.toMap(
                    Dataset::referenceSet, d -> d.values().stream().map(Value::code).collect(Collectors.toUnmodifiableSet())));
        } catch (Exception e) { throw new IllegalStateException("Cannot load platform reference data", e); }
    }
    /** Every dataset with its display names (Track F2: the static reference data provider). */
    public java.util.List<Dataset> datasets() {
        try (var files = Files.list(root)) {
            var yaml = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
            return files.filter(p -> p.toString().endsWith(".yaml")).sorted().map(p -> read(yaml, p)).toList();
        } catch (Exception e) { throw new IllegalStateException("Cannot load platform reference data", e); }
    }
    private static Dataset read(ObjectMapper yaml, Path file) { try { return yaml.readValue(file.toFile(), Dataset.class); } catch (Exception e) { throw new IllegalStateException("Invalid reference data " + file, e); } }
}
