package gov.uddp.shaping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import gov.uddp.shaping.admin.PluginStatus;
import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.LayoutOperation;
import gov.uddp.shaping.api.OperationLookup;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Looks up layout operations and field parsers by {@code name@version}. Built-ins are Spring beans; Tier-3 plugins are
 * jars in the plugins directory, loaded only with a plugin.yaml of API major version 1 and an approved SHA-256.
 */
@Service
public class OperationRegistry implements OperationLookup, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OperationRegistry.class);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String SUPPORTED_API_MAJOR = "1";

    private final Map<String, LayoutOperation> operations = new LinkedHashMap<>();
    private final Map<String, FieldParser> parsers = new LinkedHashMap<>();
    private final List<URLClassLoader> pluginLoaders = new ArrayList<>();
    private final List<PluginStatus> plugins = new ArrayList<>();
    private final Set<String> builtInKeys = new java.util.LinkedHashSet<>();

    @Autowired
    public OperationRegistry(
            ObjectProvider<LayoutOperation> builtInOperations,
            ObjectProvider<FieldParser> builtInParsers,
            @Value("${uddp.shaping.plugins-dir:plugins}") String pluginsDir,
            @Value("${uddp.shaping.approved-plugins-file:platform/shaping/approved-plugins.yaml}") String approvedFile) {
        this(builtInOperations.orderedStream().toList(), builtInParsers.orderedStream().toList(), pluginsDir,
                approvedFile);
    }

    public OperationRegistry(
            List<LayoutOperation> builtInOperations,
            List<FieldParser> builtInParsers,
            String pluginsDir,
            String approvedFile) {
        builtInOperations.forEach(this::register);
        builtInParsers.forEach(this::register);
        builtInKeys.addAll(operations.keySet());
        builtInKeys.addAll(parsers.keySet());
        loadPlugins(Path.of(pluginsDir), Path.of(approvedFile));
    }

    @Override
    public LayoutOperation operation(String nameAtVersion) {
        LayoutOperation operation = operations.get(nameAtVersion);
        if (operation == null) {
            throw new IllegalArgumentException("Unknown layout operation: " + nameAtVersion);
        }
        return operation;
    }

    @Override
    public FieldParser parser(String nameAtVersion) {
        FieldParser parser = parsers.get(nameAtVersion);
        if (parser == null) {
            throw new IllegalArgumentException("Unknown field parser: " + nameAtVersion);
        }
        return parser;
    }

    /** What was found in the plugins directory at startup, in file-name order. */
    public List<PluginStatus> plugins() {
        return List.copyOf(plugins);
    }

    /** Built-in layout operations, as {@code name@version}. */
    public List<String> builtInOperations() {
        return operations.keySet().stream().filter(builtInKeys::contains).toList();
    }

    /** Built-in field parsers, as {@code name@version}. */
    public List<String> builtInParsers() {
        return parsers.keySet().stream().filter(builtInKeys::contains).toList();
    }

    @Override
    @PreDestroy
    public void close() throws IOException {
        for (URLClassLoader loader : pluginLoaders) {
            loader.close();
        }
        pluginLoaders.clear();
    }

    private void register(LayoutOperation operation) {
        String key = operation.name() + "@" + operation.version();
        if (operations.putIfAbsent(key, operation) != null) {
            throw new IllegalStateException("Duplicate layout operation: " + key);
        }
    }

    private void register(FieldParser parser) {
        String key = parser.name() + "@" + parser.version();
        if (parsers.putIfAbsent(key, parser) != null) {
            throw new IllegalStateException("Duplicate field parser: " + key);
        }
    }

    private void loadPlugins(Path pluginsDir, Path approvedFile) {
        if (!Files.isDirectory(pluginsDir)) {
            return;
        }
        Set<String> approved = readApprovedHashes(approvedFile);
        List<Path> jars;
        try (Stream<Path> files = Files.list(pluginsDir)) {
            jars = files.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path jar : jars) {
            String hash = "";
            try {
                hash = sha256(jar);
                plugins.add(loadPlugin(jar, hash, approved));
            } catch (IOException | RuntimeException e) {
                log.warn("Shaping plugin {} rejected: {}", jar.getFileName(), e.getMessage());
                plugins.add(new PluginStatus(jar.getFileName().toString(), hash, null, null, null,
                        e instanceof NotApprovedException ? PluginStatus.NOT_APPROVED : PluginStatus.REJECTED,
                        e.getMessage(), List.of()));
            }
        }
    }

    private static final class NotApprovedException extends IllegalStateException {
        NotApprovedException(String message) {
            super(message);
        }
    }

    private PluginStatus loadPlugin(Path jar, String hash, Set<String> approved) throws IOException {
        if (!approved.contains(hash)) {
            throw new NotApprovedException("SHA-256 " + hash + " is not listed in approved-plugins.yaml");
        }
        JsonNode manifest = readPluginYaml(jar);
        for (String field : List.of("name", "version", "apiVersion")) {
            if (!manifest.hasNonNull(field)) {
                throw new IllegalStateException("plugin.yaml is missing " + field);
            }
        }
        String apiMajor = manifest.get("apiVersion").asText().split("\\.")[0];
        if (!SUPPORTED_API_MAJOR.equals(apiMajor)) {
            throw new IllegalStateException("unsupported apiVersion " + manifest.get("apiVersion").asText());
        }
        URLClassLoader loader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader());
        try {
            List<LayoutOperation> loadedOperations = ServiceLoader.load(LayoutOperation.class, loader).stream()
                    .map(ServiceLoader.Provider::get).toList();
            List<FieldParser> loadedParsers = ServiceLoader.load(FieldParser.class, loader).stream()
                    .map(ServiceLoader.Provider::get).toList();
            List<String> provides = new ArrayList<>();
            loadedOperations.forEach(operation -> provides.add(operation.name() + "@" + operation.version()));
            loadedParsers.forEach(parser -> provides.add(parser.name() + "@" + parser.version()));
            Set<String> seen = new HashSet<>();
            for (String key : provides) {
                if (!seen.add(key) || operations.containsKey(key) || parsers.containsKey(key)) {
                    throw new IllegalStateException("Duplicate plugin operation or parser: " + key);
                }
            }
            loadedOperations.forEach(this::register);
            loadedParsers.forEach(this::register);
            pluginLoaders.add(loader);
            return new PluginStatus(jar.getFileName().toString(), hash, manifest.get("name").asText(),
                    manifest.get("version").asText(), manifest.get("apiVersion").asText(), PluginStatus.LOADED, null,
                    provides);
        } catch (RuntimeException e) {
            loader.close();
            throw e;
        }
    }

    private static JsonNode readPluginYaml(Path jar) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry("plugin.yaml");
            if (entry == null) {
                throw new IllegalStateException("no plugin.yaml");
            }
            try (InputStream in = jarFile.getInputStream(entry)) {
                return YAML.readTree(in);
            }
        }
    }

    private static Set<String> readApprovedHashes(Path approvedFile) {
        Set<String> hashes = new HashSet<>();
        if (!Files.isRegularFile(approvedFile)) {
            return hashes;
        }
        try (InputStream in = Files.newInputStream(approvedFile)) {
            YAML.readTree(in).path("approved")
                    .forEach(item -> hashes.add(item.path("sha256").asText("").toLowerCase()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return hashes;
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
