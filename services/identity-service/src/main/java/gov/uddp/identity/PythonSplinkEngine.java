package gov.uddp.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

/**
 * Runs the unchanged Python Splink adapter ({@code python -m matching.run}) against identity-service's own database.
 * The adapter reads the compatibility views of V2 and writes matching_run, resident_match_pair and
 * resident_match_cluster_member; {@code PGOPTIONS} points its unqualified SQL at the identity schema. The password
 * is passed only in the child's environment and redacted from any error.
 */
@Component
public class PythonSplinkEngine implements MatchingEngine {

    private final JdbcConnectionDetails database;
    private final ObjectMapper json = new ObjectMapper();
    private final Path repositoryRoot;
    private final String statePackDirectory;
    private final String python;
    private final String statePackUrl;

    public PythonSplinkEngine(JdbcConnectionDetails database,
                              @Value("${uddp.identity.repository-root:../..}") String repositoryRoot,
                              @Value("${uddp.identity.state-pack-directory:}") String statePackDirectory,
                              @Value("${uddp.identity.python:python}") String python,
                              @Value("${uddp.identity.statepack-url:}") String statePackUrl) {
        this.database = database;
        this.repositoryRoot = Path.of(repositoryRoot).toAbsolutePath().normalize();
        this.statePackDirectory = statePackDirectory;
        this.python = python;
        this.statePackUrl = statePackUrl;
    }

    /**
     * The directory the adapter reads thresholds from: with statepack-service configured, a fresh directory holding
     * exactly the ACTIVE pack versions, each verified by digest; otherwise the configured directory.
     */
    private String packDirectory() throws java.io.IOException {
        if (statePackUrl == null || statePackUrl.isBlank()) {
            return statePackDirectory;
        }
        Path dir = java.nio.file.Files.createTempDirectory("uddp-identity-packs-");
        for (var pack : new gov.uddp.contracts.StatePackClient(statePackUrl).active()) {
            gov.uddp.contracts.StatePackClient.materialize(pack, dir);
        }
        return dir.toString();
    }

    @Override
    public UUID run(boolean incremental) {
        String password = database.getPassword();
        try {
            List<String> command = new ArrayList<>(List.of(python, "-m", "matching.run"));
            if (incremental) {
                command.add("--incremental");
            }
            var builder = new ProcessBuilder(command).directory(repositoryRoot.toFile()).redirectErrorStream(true);
            builder.environment().put("PYTHONPATH",
                    repositoryRoot.resolve("services").resolve("identity-service").toString());
            var env = builder.environment();
            env.keySet().removeIf(name -> name.startsWith("UDDP_POSTGRES_"));
            URI uri = URI.create(database.getJdbcUrl().substring("jdbc:".length()));
            env.put("UDDP_POSTGRES_HOST", uri.getHost());
            env.put("UDDP_POSTGRES_PORT", String.valueOf(uri.getPort() < 0 ? 5432 : uri.getPort()));
            env.put("UDDP_POSTGRES_DB", uri.getPath().substring(1));
            env.put("UDDP_POSTGRES_USER", database.getUsername());
            env.put("UDDP_POSTGRES_PASSWORD", password == null ? "" : password);
            env.put("PGOPTIONS", "-c search_path=identity");
            String packs = packDirectory();
            if (packs != null && !packs.isBlank()) {
                env.put("UDDP_STATE_PACK_DIRECTORY", packs);
            }
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("Python matching failed: " + redact(tail(output), password));
            }
            String[] lines = output.strip().split("\\R");
            return UUID.fromString(json.readTree(lines[lines.length - 1]).path("run_id").asText());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Python matching execution failed: " + redact(e.getMessage(), password), e);
        }
    }

    private static String redact(String text, String secret) {
        return text == null || secret == null || secret.isEmpty() ? text : text.replace(secret, "********");
    }

    private static String tail(String text) {
        return text.length() > 4000 ? text.substring(text.length() - 4000) : text;
    }
}
