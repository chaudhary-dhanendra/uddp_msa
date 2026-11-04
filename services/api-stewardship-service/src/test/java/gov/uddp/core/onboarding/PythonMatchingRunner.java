package gov.uddp.core.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.database.ChildProcessDatabaseSettings;
import gov.uddp.core.security.SecretRedactor;
import gov.uddp.core.statepack.StatePackLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Replaceable boundary to the existing Python/Splink module; Java does not implement matching. The child process
 * receives the active database settings and State Pack directory in its environment (never on its command line),
 * and its output is redacted before it is stored.
 */
@Component
public class PythonMatchingRunner implements MatchingRunner {
    static final String STATE_PACK_DIRECTORY = "UDDP_STATE_PACK_DIRECTORY";

    private final ObjectMapper json;
    private final PostgresConnectionFactory connections;
    private final StatePackLoader packs;
    private final Path repositoryRoot;

    /** For tests that replace the process boundary entirely. */
    public PythonMatchingRunner(ObjectMapper json) {
        this(json, null, null, Path.of("../.."));
    }

    @Autowired
    public PythonMatchingRunner(ObjectMapper json, PostgresConnectionFactory connections, StatePackLoader packs,
                                StatePackProperties properties) {
        this(json, connections, packs, properties.repositoryRoot());
    }

    private PythonMatchingRunner(ObjectMapper json, PostgresConnectionFactory connections, StatePackLoader packs,
                                 Path repositoryRoot) {
        this.json = json;
        this.connections = connections;
        this.packs = packs;
        this.repositoryRoot = repositoryRoot;
    }

    /** A full matching run over every latest canonical Resident. */
    @Override
    public UUID run() {
        return execute(false);
    }

    /** An incremental run: only pairs touching new or changed canonical Residents (ADR-0004). */
    @Override
    public UUID runIncremental() {
        return execute(true);
    }

    private UUID execute(boolean incremental) {
        List<String> secrets = connections == null ? List.of() : connections.secrets();
        try {
            var command = new java.util.ArrayList<>(java.util.List.of("python", "-m", "matching.run"));
            if (incremental) {
                command.add("--incremental");
            }
            var builder = new ProcessBuilder(command)
                    .directory(repositoryRoot.toFile())
                    .redirectErrorStream(true);
            if (connections != null) {
                ChildProcessDatabaseSettings.forEnvironment(builder, connections.processVariables());
            }
            if (packs != null) {
                builder.environment().put(STATE_PACK_DIRECTORY, packs.directory().toString());
            }
            var p = builder.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                throw new IllegalStateException(SecretRedactor.redact(tail(output), secrets));
            }
            String[] lines = output.strip().split("\\R");
            JsonNode result = json.readTree(lines[lines.length - 1]);
            return UUID.fromString(result.path("run_id").asText());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Python matching execution failed: "
                    + SecretRedactor.redact(e.getMessage(), secrets), e);
        }
    }

    private static String tail(String text) {
        return text.length() > 4000 ? text.substring(text.length() - 4000) : text;
    }
}
