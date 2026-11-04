package gov.uddp.ingestion.connector;

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
 * Landing folders (Track C3, moved from the edge): a source whose location is a folder takes the oldest waiting file
 * (name order); after ingestion succeeds the file moves to the folder's {@code processed/} subfolder, so a file is
 * never ingested twice. Locations are relative to {@code uddp.ingestion.landing-folders-root}.
 */
@Component
public class LandingFolders {

    static final String PROCESSED = "processed";
    private static final List<String> EXTENSIONS = List.of(".csv", ".xlsx", ".xls");

    /** A waiting file and what to do once it has been ingested. */
    public record Intake(Path file, Runnable completed) {
    }

    private final Path root;

    public LandingFolders(@Value("${uddp.ingestion.landing-folders-root:${uddp.ingestion.repository-root:../..}}")
                          String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public boolean isLandingFolder(String location) {
        return location != null && !location.isBlank() && Files.isDirectory(folder(location));
    }

    public Path folder(String location) {
        Path folder = root.resolve(location).normalize();
        if (!folder.startsWith(root)) {
            throw new IllegalArgumentException("A landing folder must be inside the landing root");
        }
        return folder;
    }

    /** The files waiting in the folder, oldest name first; empty when the folder is missing. */
    public List<Path> waiting(String location) {
        if (!isLandingFolder(location)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder(location))) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> EXTENSIONS.stream().anyMatch(ext -> file.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(ext)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the landing folder " + location, e);
        }
    }

    /** The next waiting file, if any; moving it to {@code processed/} is the {@code completed} follow-up. */
    public Optional<Intake> next(String location) {
        return waiting(location).stream().findFirst().map(file -> new Intake(file, () -> archive(file)));
    }

    private static void archive(Path file) {
        try {
            Path processed = Files.createDirectories(file.getParent().resolve(PROCESSED));
            Files.move(file, processed.resolve(Instant.now().toEpochMilli() + "-" + file.getFileName()),
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot move " + file.getFileName() + " to the processed folder", e);
        }
    }
}
