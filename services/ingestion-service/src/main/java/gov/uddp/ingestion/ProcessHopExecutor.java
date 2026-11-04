package gov.uddp.ingestion;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

/**
 * Runs {@code hop-run} on a generic template, as the monolith's ProcessHopRunner does, but writing to this service's
 * database and schema ({@code RAW_SCHEMA=ingestion}). The database settings reach Hop's {@code uddp-postgres}
 * connection through a temporary JVM argument file that is deleted afterwards, never on the command line, and the
 * password is redacted from the returned output.
 */
@Component
public class ProcessHopExecutor implements HopExecutor {

    private static final int OUTPUT_LIMIT = 4000;

    private final JdbcConnectionDetails database;
    private final Path repositoryRoot;
    private final String hopHome;
    private final String hopProject;

    public ProcessHopExecutor(JdbcConnectionDetails database,
                              @Value("${uddp.ingestion.repository-root:../..}") String repositoryRoot,
                              @Value("${uddp.ingestion.hop-home:}") String hopHome,
                              @Value("${uddp.ingestion.hop-project:}") String hopProject) {
        this.database = database;
        this.repositoryRoot = Path.of(repositoryRoot).toAbsolutePath().normalize();
        this.hopHome = hopHome;
        this.hopProject = hopProject;
    }

    @Override
    public Result run(String template, Map<String, String> parameters) {
        return run(template, parameters, Map.of());
    }

    @Override
    public Result run(String template, Map<String, String> parameters, Map<String, String> secretSettings) {
        if (hopHome == null || hopHome.isBlank()) {
            throw new IllegalStateException("HOP_HOME is required for ingestion");
        }
        String password = database.getPassword();
        Path argumentFile = null;
        try {
            Path pipeline = repositoryRoot.resolve("hop").resolve("pipelines").resolve(template).normalize();
            List<String> values = new ArrayList<>();
            parameters.forEach((name, value) -> values.add(name + "=" + (value == null ? "" : value)));
            values.add("RAW_SCHEMA=ingestion");
            String launcher = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "hop-run.bat" : "hop-run.sh";
            List<String> command = new ArrayList<>(List.of(Path.of(hopHome, launcher).toString()));
            if (hopProject != null && !hopProject.isBlank()) {
                command.addAll(List.of("-j", hopProject));
            }
            command.addAll(List.of("-r", "local", "-f", pipeline.toString(), "-ps", "@@", "-p",
                    String.join("@@", values)));
            var builder = new ProcessBuilder(command).redirectErrorStream(true);
            if (hopProject == null || hopProject.isBlank()) {
                // No registered Hop project: the connections uddp-postgres and uddp-source come from this
                // repository's metadata folder, so the service needs no Hop configuration of its own.
                builder.environment().put("HOP_METADATA_FOLDER", repositoryRoot.resolve("metadata").toString());
            }
            Map<String, String> settings = new LinkedHashMap<>(connectionVariables(password));
            settings.putAll(secretSettings);
            argumentFile = jvmSettings(builder, settings);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            String redacted = redact(tail(output), password);
            String sourcePassword = secretSettings.get("UDDP_SOURCE_PASSWORD");
            return new Result(exit, redact(redacted, sourcePassword));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Generic Hop execution was interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Generic Hop execution failed: " + redact(e.getMessage(), password), e);
        } finally {
            if (argumentFile != null) {
                try {
                    Files.deleteIfExists(argumentFile);
                } catch (IOException ignored) {
                    // a leftover private temp file is reported by nothing; the OS cleans the temp folder
                }
            }
        }
    }

    private Map<String, String> connectionVariables(String password) {
        URI uri = URI.create(database.getJdbcUrl().substring("jdbc:".length()));
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("UDDP_POSTGRES_HOST", uri.getHost());
        variables.put("UDDP_POSTGRES_PORT", String.valueOf(uri.getPort() < 0 ? 5432 : uri.getPort()));
        variables.put("UDDP_POSTGRES_DB", uri.getPath().substring(1));
        variables.put("UDDP_POSTGRES_USER", database.getUsername());
        variables.put("UDDP_POSTGRES_PASSWORD", password == null ? "" : password);
        return variables;
    }

    private static Path jvmSettings(ProcessBuilder builder, Map<String, String> variables) throws IOException {
        Path file;
        try {
            file = Files.createTempFile("uddp-hop-", ".args",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException windows) {
            file = Files.createTempFile("uddp-hop-", ".args");
        }
        List<String> lines = new ArrayList<>();
        variables.forEach((name, value) -> lines.add("\"-D" + name + "=" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\""));
        Files.write(file, lines, StandardCharsets.UTF_8);
        String existing = builder.environment().get("JDK_JAVA_OPTIONS");
        String reference = "@" + file.toAbsolutePath();
        builder.environment().put("JDK_JAVA_OPTIONS", existing == null || existing.isBlank() ? reference
                : reference + " " + existing);
        return file;
    }

    private static String redact(String text, String secret) {
        return text == null || secret == null || secret.isEmpty() ? text : text.replace(secret, "********");
    }

    private static String tail(String output) {
        return output.length() > OUTPUT_LIMIT ? output.substring(output.length() - OUTPUT_LIMIT) : output;
    }
}
