package gov.uddp.core.onboarding;

import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The generic Hop template and parameters for a file or workbook source, as ingestion-service runs them: the source
 * file resolved against the repository root, its CSV header, or a workbook's sheets. Hop itself runs only in
 * ingestion-service; nothing here starts a process.
 */
@Component
public class HopInvocations {

    public static final String EXCEL_TEMPLATE = "generic-excel-ingest.hpl";

    /** A generic template and its Hop parameters. */
    public record Invocation(String template, Map<String, String> parameters) {
    }

    private final Path repositoryRoot;

    @org.springframework.beans.factory.annotation.Autowired
    public HopInvocations(StatePackProperties properties) {
        this(properties.repositoryRoot());
    }

    public HopInvocations(Path repositoryRoot) {
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
    }

    /** The template and parameters for a file or workbook source; a database source is ingestion-service's to read. */
    public Invocation fileInvocation(SourceDefinition source, String stateCode, String packVersion, UUID batchId)
            throws java.io.IOException {
        if (source.database()) {
            throw new IllegalArgumentException("Database sources are read by ingestion-service");
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
    public Path sourceFile(SourceDefinition source) {
        return repositoryRoot.resolve(source.location()).toAbsolutePath().normalize();
    }

    /** The declared sheet names, comma separated, or {@code *} (every sheet) when the source names none. */
    public static String sheets(SourceDefinition source) {
        return source.sheets() == null || source.sheets().isEmpty() ? "*" : String.join(",", source.sheets().keySet());
    }
}
