package gov.uddp.core.database;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hands the platform database settings to a child process without putting them on a command line or in an echoed
 * option.
 *
 * <p>Hop resolves {@code ${UDDP_POSTGRES_*}} in its connection metadata from Java system properties. The JVM prints
 * {@code JDK_JAVA_OPTIONS} when it starts, and Hop's output is stored as a failure message, so the properties go in
 * a temporary JVM argument file and {@code JDK_JAVA_OPTIONS} names only that file (the same mechanism as the e2e
 * scripts). {@link #close()} deletes the file once the process has exited.
 *
 * <p>Python matching reads the same names from its environment, which is never echoed.
 */
public final class ChildProcessDatabaseSettings implements AutoCloseable {
    private static final String JDK_JAVA_OPTIONS = "JDK_JAVA_OPTIONS";

    private final Path argumentFile;

    private ChildProcessDatabaseSettings(Path argumentFile) {
        this.argumentFile = argumentFile;
    }

    /** Writes the settings as JVM system properties to a temporary argument file named by JDK_JAVA_OPTIONS. */
    public static ChildProcessDatabaseSettings forJvm(ProcessBuilder builder, Map<String, String> variables) {
        Path file = createPrivateTemporaryFile();
        try {
            List<String> lines = new ArrayList<>();
            variables.forEach((name, value) -> lines.add(quote("-D" + name + "=" + value)));
            Files.write(file, lines, StandardCharsets.UTF_8);
            Map<String, String> environment = builder.environment();
            String existing = environment.get(JDK_JAVA_OPTIONS);
            String reference = "@" + file.toAbsolutePath();
            environment.put(JDK_JAVA_OPTIONS,
                    existing == null || existing.isBlank() ? reference : reference + " " + existing);
            return new ChildProcessDatabaseSettings(file);
        } catch (IOException e) {
            deleteQuietly(file);
            throw new UncheckedIOException("Cannot write the temporary database settings file", e);
        }
    }

    /** Puts the settings in the child's environment (for processes, such as Python, that never echo it). */
    public static void forEnvironment(ProcessBuilder builder, Map<String, String> variables) {
        builder.environment().putAll(variables);
    }

    /** The argument file, for tests that check it is removed. */
    Path argumentFile() {
        return argumentFile;
    }

    @Override
    public void close() {
        deleteQuietly(argumentFile);
    }

    /** JVM argument files treat backslashes and quotes specially inside quotes; escape both. */
    private static String quote(String argument) {
        return "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Path createPrivateTemporaryFile() {
        try {
            try {
                return Files.createTempFile("uddp-db-", ".args",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException windows) {
                // The per-user temporary directory already restricts access on Windows.
                return Files.createTempFile("uddp-db-", ".args");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the temporary database settings file", e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover file sits in the per-user temporary directory; nothing else can be done here.
        }
    }
}
