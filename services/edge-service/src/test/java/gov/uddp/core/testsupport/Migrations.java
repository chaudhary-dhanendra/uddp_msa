package gov.uddp.core.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Flyway migrations shipped in {@code src/main/resources/db/migration}, so upgrade tests can assert "every later
 * migration ran" without assuming migration versions are contiguous.
 */
public final class Migrations {
    private static final Pattern VERSION = Pattern.compile("V(\\d+)__.*\\.sql");

    /** All shipped numeric migration versions, sorted ascending. */
    private static final List<Integer> VERSIONS = versions();

    /** The highest migration version. */
    public static final int LATEST = VERSIONS.get(VERSIONS.size() - 1);

    /** The total number of versioned migrations shipped by the application. */
    public static final int COUNT = VERSIONS.size();

    private Migrations() {
    }

    /** How many migrations a database at {@code version} still has to run to reach {@link #LATEST}. */
    public static int after(int version) {
        return (int) VERSIONS.stream().filter(candidate -> candidate > version).count();
    }

    private static List<Integer> versions() {
        Path directory = Path.of("src/main/resources/db/migration");
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> VERSION.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(matcher -> Integer.parseInt(matcher.group(1)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
