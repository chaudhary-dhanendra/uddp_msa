package gov.uddp.core.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.database.ChildProcessDatabaseSettings;
import gov.uddp.core.security.SecretRedactor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs a Matching Studio module of the Python/Splink package (training, estimates, impact preview, labelling,
 * metrics) in a child process. The child gets the database settings in its environment, never on its command line;
 * its output is redacted. The module prints one JSON object on its last line, which is returned.
 */
@Component
public class PythonModules {
    /** The process boundary tests replace. */
    @FunctionalInterface
    public interface Runner {
        JsonNode run(String module, List<String> arguments);
    }

    private final ObjectMapper json;
    private final PostgresConnectionFactory connections;
    private final Path repositoryRoot;

    @Autowired
    public PythonModules(ObjectMapper json, PostgresConnectionFactory connections, StatePackProperties properties) {
        this.json = json;
        this.connections = connections;
        this.repositoryRoot = properties.repositoryRoot();
    }

    public JsonNode run(String module, List<String> arguments) {
        List<String> secrets = connections.secrets();
        try {
            var command = new ArrayList<>(List.of("python", "-m", "matching." + module));
            command.addAll(arguments);
            var builder = new ProcessBuilder(command).directory(repositoryRoot.toFile()).redirectErrorStream(true);
            builder.environment().put("PYTHONPATH",
                    repositoryRoot.resolve("services").resolve("identity-service").toString());
            ChildProcessDatabaseSettings.forEnvironment(builder, connections.processVariables());
            var process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                String tail = output.length() > 2000 ? output.substring(output.length() - 2000) : output;
                throw new IllegalStateException(SecretRedactor.redact(tail, secrets));
            }
            String[] lines = output.strip().split("\\R");
            return json.readTree(lines[lines.length - 1]);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Python " + module + " failed: " + SecretRedactor.redact(e.getMessage(), secrets), e);
        }
    }
}
