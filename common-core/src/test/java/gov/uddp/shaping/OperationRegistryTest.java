package gov.uddp.shaping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.LayoutOperation;
import gov.uddp.shaping.api.ParseResult;
import gov.uddp.shaping.api.RawRow;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationRegistryTest {

    @TempDir
    Path tmp;

    private static final LayoutOperation BUILT_IN_OP = new LayoutOperation() {
        public String name() { return "skip-banner"; }
        public String version() { return "1"; }
        public List<RawRow> apply(List<RawRow> rows, Map<String, Object> config) { return rows; }
    };

    private static final FieldParser BUILT_IN_PARSER = new FieldParser() {
        public String name() { return "name-split"; }
        public String version() { return "2"; }
        public ParseResult parse(String value, Map<String, Object> config) {
            return new ParseResult(Map.of("full", value), 1.0, List.of());
        }
    };

    private OperationRegistry registry(Path pluginsDir, Path approved) {
        return new OperationRegistry(List.of(BUILT_IN_OP), List.of(BUILT_IN_PARSER), pluginsDir.toString(),
                approved.toString());
    }

    @Test
    void looksUpBuiltIns() {
        OperationRegistry registry = registry(tmp.resolve("none"), tmp.resolve("none.yaml"));
        assertThat(registry.operation("skip-banner@1")).isSameAs(BUILT_IN_OP);
        assertThat(registry.parser("name-split@2")).isSameAs(BUILT_IN_PARSER);
    }

    @Test
    void unknownNameIsRejectedWithItsName() {
        OperationRegistry registry = registry(tmp.resolve("none"), tmp.resolve("none.yaml"));
        assertThatThrownBy(() -> registry.operation("nope@1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope@1");
        assertThatThrownBy(() -> registry.parser("skip-banner@2"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("skip-banner@2");
    }

    @Test
    void pluginWithUnapprovedHashIsNotLoaded() throws Exception {
        Path plugins = Files.createDirectory(tmp.resolve("plugins"));
        buildPlugin(plugins.resolve("tiny.jar"), "1.0");
        Path approved = approvedFile("0".repeat(64));

        try (OperationRegistry registry = registry(plugins, approved)) {
            assertThatThrownBy(() -> registry.operation("tiny-op@1.0"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tiny-op@1.0");
            var status = registry.plugins().getFirst();
            assertThat(status.status()).isEqualTo("NOT_APPROVED");
            assertThat(status.fileName()).isEqualTo("tiny.jar");
            assertThat(status.sha256()).isEqualTo(sha256(plugins.resolve("tiny.jar")));
            assertThat(status.reason()).contains("approved-plugins.yaml");
        }
    }

    @Test
    void approvedPluginIsLoaded() throws Exception {
        Path plugins = Files.createDirectory(tmp.resolve("plugins"));
        Path jar = buildPlugin(plugins.resolve("tiny.jar"), "1.0");

        try (OperationRegistry registry = registry(plugins, approvedFile(sha256(jar)))) {
            LayoutOperation operation = registry.operation("tiny-op@1.0");
            assertThat(operation.apply(List.of(), Map.of())).isEmpty();
            var status = registry.plugins().getFirst();
            assertThat(status.status()).isEqualTo("LOADED");
            assertThat(status.name()).isEqualTo("tiny");
            assertThat(status.version()).isEqualTo("1.0.0");
            assertThat(status.provides()).containsExactly("tiny-op@1.0");
            assertThat(registry.builtInOperations()).containsExactly("skip-banner@1");
            assertThat(registry.builtInParsers()).containsExactly("name-split@2");
        }
    }

    @Test
    void approvedPluginWithOtherApiMajorIsNotLoaded() throws Exception {
        Path plugins = Files.createDirectory(tmp.resolve("plugins"));
        Path jar = buildPlugin(plugins.resolve("tiny.jar"), "2.0");

        try (OperationRegistry registry = registry(plugins, approvedFile(sha256(jar)))) {
            assertThatThrownBy(() -> registry.operation("tiny-op@1.0")).isInstanceOf(IllegalArgumentException.class);
            var status = registry.plugins().getFirst();
            assertThat(status.status()).isEqualTo("REJECTED");
            assertThat(status.reason()).contains("apiVersion 2.0");
        }
    }

    @Test
    void pluginWithOneDuplicateNameRegistersNothing() throws Exception {
        Path plugins = Files.createDirectory(tmp.resolve("plugins"));
        Path jar = buildPlugin(plugins.resolve("tiny.jar"), "1.0", Map.of("TinyOp", "tiny-op", "ClashOp", "skip-banner"),
                "1");

        try (OperationRegistry registry = registry(plugins, approvedFile(sha256(jar)))) {
            var status = registry.plugins().getFirst();
            assertThat(status.status()).isEqualTo("REJECTED");
            assertThat(status.reason()).contains("skip-banner@1");
            assertThatThrownBy(() -> registry.operation("tiny-op@1")).isInstanceOf(IllegalArgumentException.class);
            assertThat(registry.operation("skip-banner@1")).isSameAs(BUILT_IN_OP);
        }
    }

    private Path approvedFile(String hash) throws IOException {
        Path file = tmp.resolve("approved-plugins.yaml");
        Files.writeString(file, "approved:\n  - sha256: " + hash + "\n    name: tiny\n");
        return file;
    }

    /** Compiles one LayoutOperation against the test classpath and packs it with plugin.yaml and a service file. */
    private Path buildPlugin(Path jar, String apiVersion) throws Exception {
        return buildPlugin(jar, apiVersion, Map.of("TinyOp", "tiny-op"), "1.0");
    }

    /** Compiles one LayoutOperation per entry (class name -> operation name) and packs them as one plugin. */
    private Path buildPlugin(Path jar, String apiVersion, Map<String, String> classes, String opVersion)
            throws Exception {
        Path src = Files.createDirectories(tmp.resolve("src/tinyplugin"));
        Path out = Files.createDirectories(tmp.resolve("classes"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        for (var entry : classes.entrySet()) {
            Path file = src.resolve(entry.getKey() + ".java");
            Files.writeString(file, """
                    package tinyplugin;
                    import gov.uddp.shaping.api.*;
                    import java.util.*;
                    public class %s implements LayoutOperation {
                        public String name() { return "%s"; }
                        public String version() { return "%s"; }
                        public List<RawRow> apply(List<RawRow> rows, Map<String, Object> config) { return rows; }
                    }
                    """.formatted(entry.getKey(), entry.getValue(), opVersion));
            int status = compiler.run(null, null, null, "-cp", System.getProperty("java.class.path"),
                    "-d", out.toString(), file.toString());
            assertThat(status).isZero();
        }

        try (OutputStream stream = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(stream)) {
            put(jos, "plugin.yaml", "name: tiny\nversion: 1.0.0\napiVersion: " + apiVersion + "\n");
            StringBuilder services = new StringBuilder();
            classes.keySet().forEach(name -> services.append("tinyplugin.").append(name).append('\n'));
            put(jos, "META-INF/services/gov.uddp.shaping.api.LayoutOperation", services.toString());
            for (String name : classes.keySet()) {
                jos.putNextEntry(new JarEntry("tinyplugin/" + name + ".class"));
                jos.write(Files.readAllBytes(out.resolve("tinyplugin/" + name + ".class")));
                jos.closeEntry();
            }
        }
        return jar;
    }

    private static void put(JarOutputStream jos, String name, String content) throws IOException {
        jos.putNextEntry(new JarEntry(name));
        jos.write(content.getBytes(StandardCharsets.UTF_8));
        jos.closeEntry();
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
