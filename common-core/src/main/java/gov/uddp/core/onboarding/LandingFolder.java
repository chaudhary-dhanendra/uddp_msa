package gov.uddp.core.onboarding;

import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Landing-folder intake. A file source whose {@code location} is a directory is a landing folder: large deliveries are
 * copied there instead of being uploaded through the browser, and each run ingests the oldest file waiting in it
 * (name order). After ingestion succeeds the file moves to the folder's {@code processed/} subfolder, so a file is
 * never ingested twice. Relative locations are relative to the repository root, as for single-file sources.
 */
@Component
public class LandingFolder {
    static final String PROCESSED = "processed";
    private static final List<String> EXTENSIONS = List.of(".csv", ".xlsx", ".xls");

    /** A waiting file as the source definition that reads it, and what to do once it has been ingested. */
    public record Intake(SourceDefinition source, Path file, Runnable completed) {
    }

    private final Path root;

    public LandingFolder(@Value("${uddp.landing.root:..}") String root) {
        this.root = Path.of(root);
    }

    private gov.uddp.core.events.IngestionClient remote;

    /** MSA: with ingestion-service owning landing folders, the folder is looked at there, not on this machine. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRemote(gov.uddp.core.events.IngestionClient remote) {
        this.remote = remote;
    }

    public boolean isLandingFolder(SourceDefinition source) {
        if (remote != null) {
            return "file".equals(source.sourceType()) && source.location() != null
                    && remote.get(waitingPath(source)).isPresent();
        }
        return "file".equals(source.sourceType()) && source.location() != null && Files.isDirectory(folder(source));
    }

    private static String waitingPath(SourceDefinition source) {
        return "api/ingestion/landing/waiting?location=" + gov.uddp.core.events.IngestionClient.encode(source.location());
    }

    public Path folder(SourceDefinition source) {
        return root.resolve(source.location()).toAbsolutePath().normalize();
    }

    /** The files waiting in the folder, oldest name first; empty when the folder is missing. */
    public List<Path> waiting(SourceDefinition source) {
        if (remote != null) {
            List<Path> names = new java.util.ArrayList<>();
            remote.get(waitingPath(source)).ifPresent(list -> list.forEach(name -> names.add(Path.of(name.asText()))));
            return names;
        }
        Path folder = folder(source);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> EXTENSIONS.stream().anyMatch(ext -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the landing folder " + source.location(), e);
        }
    }

    /** The next waiting file, if any; moving it to {@code processed/} is the {@code completed} follow-up. */
    public Optional<Intake> next(SourceDefinition source) {
        return waiting(source).stream().findFirst().map(file -> {
            var staged = new SourceDefinition(source.sourceId(), source.sourceType(), source.hopTemplate(),
                    source.connectionRef(), file.toString(), source.recordIdField(), source.sourceSchemaVersion(),
                    source.recordKeyFields(), source.table(), source.query(), source.format(), source.sheets(),
                    source.layout(), source.parse(), source.asOfDate());
            return new Intake(staged, file, () -> archive(file));
        });
    }

    private void archive(Path file) {
        try {
            Path processed = Files.createDirectories(file.getParent().resolve(PROCESSED));
            Files.move(file, processed.resolve(Instant.now().toEpochMilli() + "-" + file.getFileName()),
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot move " + file.getFileName() + " to the processed folder", e);
        }
    }
}
