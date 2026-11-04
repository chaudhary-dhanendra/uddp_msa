package gov.uddp.core.onboarding;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.database.ChildProcessDatabaseSettings;
import gov.uddp.core.security.SecretRedactor;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Invokes the source's generic Hop template ({@code hop-run}) with the same parameters as the e2e scripts.
 *
 * <p>Hop runs from {@code HOP_HOME}, so the pipeline and source file are passed as absolute paths. Database settings
 * reach Hop through a temporary JVM argument file ({@link ChildProcessDatabaseSettings}), never through an echoed
 * option, and the returned output is redacted. A database source's connection and SELECT are handed to
 * {@code generic-db-ingest.hpl} the same way (ADR-0016).
 */
@Component
public class ProcessHopRunner implements HopRunner {
    private static final int OUTPUT_LIMIT = 4000;
    static final String EXCEL_TEMPLATE = "generic-excel-ingest.hpl";

    private final PostgresConnectionFactory connections;
    private final DatabaseSources databases;
    private final Path repositoryRoot;
    private final String hopHome;
    private final String hopProject;

    @Autowired
    public ProcessHopRunner(PostgresConnectionFactory connections, DatabaseSources databases,
                            StatePackProperties properties) {
        this(connections, databases, properties.repositoryRoot(), System.getenv("HOP_HOME"),
                System.getenv().getOrDefault("UDDP_HOP_PROJECT", "uddp-mvp"));
    }

    ProcessHopRunner(PostgresConnectionFactory connections, DatabaseSources databases, Path repositoryRoot,
                     String hopHome, String hopProject) {
        this.connections = connections;
        this.databases = databases;
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.hopHome = hopHome;
        this.hopProject = hopProject;
    }

    @Override
    public Result ingest(SourceDefinition source, String stateCode, String packVersion, UUID batchId) {
        if (hopHome == null || hopHome.isBlank()) {
            throw new IllegalStateException("HOP_HOME is required for onboarding ingestion");
        }
        Map<String, String> settings = new LinkedHashMap<>(connections.processVariables());
        List<String> secrets = new ArrayList<>(connections.secrets());
        try {
            boolean excel = "xlsx".equals(source.formatOrDefault());
            Path pipeline = pipeline(excel ? EXCEL_TEMPLATE : source.hopTemplate());
            List<String> parameters = new ArrayList<>();
            String header;
            if (source.database()) {
                // The source's credentials and SELECT go in the same deleted argument file as the platform's.
                Map<String, String> sourceSettings = databases.hopVariables(source.connectionRef());
                secrets.add(sourceSettings.get("UDDP_SOURCE_PASSWORD"));
                settings.putAll(sourceSettings);
                settings.put("UDDP_SOURCE_SQL", DatabaseSources.rawLineSelect(source.table(), source.query()));
                header = String.join(",", databases.read(source.connectionRef(), source.table(), source.query(), 1)
                        .header());
            } else if (excel) {
                header = "";
                parameters.add("FILE=" + sourceFile(source));
                parameters.add("SHEETS=" + sheets(source));
            } else {
                Path sourceFile = sourceFile(source);
                header = Files.readAllLines(sourceFile, StandardCharsets.UTF_8).getFirst();
                parameters.add("SOURCE_FILE=" + sourceFile);
            }
            if (!excel) {
                parameters.add("CSV_HEADER=" + header);
            }
            parameters.addAll(List.of(
                    "STATE_CODE=" + stateCode,
                    "SOURCE_SYSTEM=" + source.sourceId(),
                    "SOURCE_ENTITY=" + source.sourceId(),
                    "SOURCE_SCHEMA_VERSION=" + source.sourceSchemaVersion(),
                    "STATE_PACK_VERSION=" + packVersion,
                    "BATCH_ID=" + batchId));
            var command = new ArrayList<>(List.of(executable().toString(), "-j", hopProject, "-r", "local",
                    "-f", pipeline.toString(), "-ps", "@@", "-p", String.join("@@", parameters)));
            var builder = new ProcessBuilder(command).redirectErrorStream(true);
            try (var argumentFile = ChildProcessDatabaseSettings.forJvm(builder, settings)) {
                var process = builder.start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int exitCode = process.waitFor();
                return new Result(exitCode, SecretRedactor.redact(tail(output), secrets));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Generic Hop execution was interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Generic Hop execution failed: "
                    + SecretRedactor.redact(e.getMessage(), secrets), e);
        }
    }

    /** A generic template and its Hop parameters, for a runner that executes Hop elsewhere (ingestion-service). */
    public record Invocation(String template, Map<String, String> parameters) {
    }

    /**
     * The template and parameters {@link #ingest} would run for a file or workbook source, without running Hop. A
     * database source is refused: its connection is resolved where its credentials live.
     */
    public Invocation fileInvocation(SourceDefinition source, String stateCode, String packVersion, UUID batchId)
            throws java.io.IOException {
        if (source.database()) {
            throw new IllegalArgumentException("Database sources are ingested by the local Hop runner only");
        }
        boolean excel = "xlsx".equals(source.formatOrDefault());
        Map<String, String> parameters = new LinkedHashMap<>();
        if (excel) {
            parameters.put("FILE", sourceFile(source).toString());
            parameters.put("SHEETS", sheets(source));
        } else {
            Path sourceFile = sourceFile(source);
            parameters.put("SOURCE_FILE", sourceFile.toString());
            parameters.put("CSV_HEADER", Files.readAllLines(sourceFile, StandardCharsets.UTF_8).getFirst());
        }
        parameters.put("STATE_CODE", stateCode);
        parameters.put("SOURCE_SYSTEM", source.sourceId());
        parameters.put("SOURCE_ENTITY", source.sourceId());
        parameters.put("SOURCE_SCHEMA_VERSION", source.sourceSchemaVersion());
        parameters.put("STATE_PACK_VERSION", packVersion);
        parameters.put("BATCH_ID", batchId.toString());
        return new Invocation(excel ? EXCEL_TEMPLATE : source.hopTemplate(), parameters);
    }

    /** The source file as an absolute path; a relative {@code location} is relative to the repository root. */
    Path sourceFile(SourceDefinition source) {
        return repositoryRoot.resolve(source.location()).toAbsolutePath().normalize();
    }

    /** The declared sheet names, comma separated, or {@code *} (every sheet) when the source names none. */
    static String sheets(SourceDefinition source) {
        return source.sheets() == null || source.sheets().isEmpty() ? "*" : String.join(",", source.sheets().keySet());
    }

    Path pipeline(String template) {
        return repositoryRoot.resolve("hop").resolve("pipelines").resolve(template).toAbsolutePath().normalize();
    }

    private Path executable() {
        String launcher = System.getProperty("os.name", "").toLowerCase().contains("win") ? "hop-run.bat" : "hop-run.sh";
        return Path.of(hopHome, launcher);
    }

    private static String tail(String output) {
        return output.length() > OUTPUT_LIMIT ? output.substring(output.length() - OUTPUT_LIMIT) : output;
    }
}
