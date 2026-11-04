package gov.uddp.core.testsupport;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Re-delivers a State Pack source the way a later Hop run would: the configured file, optionally edited, landed as
 * a new Raw batch through {@link HopShapedRawLoader}. Also copies State Pack directories so a test can change one
 * pack's configuration (for example its version) without touching the repository.
 */
public final class Redelivery {
    private Redelivery() {
    }

    /** Lands the pack's source again, after applying {@code edit} to its data lines; returns the new batch. */
    public static UUID land(PostgresConnectionFactory connections, LoadedStatePack pack, String sourceId,
                            Path workDirectory, UnaryOperator<List<String>> edit) {
        SourceDefinition source = pack.sources().stream()
                .filter(s -> s.sourceId().equals(sourceId))
                .findFirst()
                .orElseThrow();
        try {
            var lines = Files.readAllLines(SampleData.REPOSITORY_ROOT.resolve(source.location()), StandardCharsets.UTF_8);
            var data = edit.apply(lines.subList(1, lines.size()).stream().filter(line -> !line.isBlank()).toList());
            Path file = workDirectory.resolve(sourceId + "-" + UUID.randomUUID() + ".csv");
            Files.write(file, Stream.concat(Stream.of(lines.getFirst()), data.stream()).toList(), StandardCharsets.UTF_8);
            var copy = new SourceDefinition(source.sourceId(), source.sourceType(), source.hopTemplate(),
                    source.connectionRef(), file.toAbsolutePath().toString(), source.recordIdField(),
                    source.sourceSchemaVersion(), source.recordKeyFields());
            return new HopShapedRawLoader(connections, SampleData.REPOSITORY_ROOT).land(pack, copy);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Replaces {@code from} with {@code to} in the line that contains {@code marker}. */
    public static UnaryOperator<List<String>> replaceIn(String marker, String from, String to) {
        return lines -> lines.stream().map(line -> line.contains(marker) ? line.replace(from, to) : line).toList();
    }

    /** Copies every pack under each directory into {@code target}; returns {@code target}. */
    public static Path copyPacks(Path target, Path... directories) {
        try {
            for (Path directory : directories) {
                try (Stream<Path> files = Files.walk(directory)) {
                    for (Path file : files.sorted(Comparator.naturalOrder()).toList()) {
                        Path destination = target.resolve(directory.relativize(file).toString());
                        if (Files.isDirectory(file)) {
                            Files.createDirectories(destination);
                        } else {
                            Files.copy(file, destination);
                        }
                    }
                }
            }
            return target;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
