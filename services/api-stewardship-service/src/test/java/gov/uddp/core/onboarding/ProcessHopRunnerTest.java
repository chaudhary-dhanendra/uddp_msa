package gov.uddp.core.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real process boundary against a stand-in {@code hop-run} that misbehaves as badly as possible: it echoes
 * JDK_JAVA_OPTIONS and then prints the whole JVM argument file (the database settings) before failing.
 */
class ProcessHopRunnerTest {
    static final String PASSWORD = "Pw-" + UUID.randomUUID();
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    @TempDir
    Path temp;

    @Test
    void passesAbsolutePathsAndNeverReturnsTheDatabasePassword() throws Exception {
        Path hopHome = fakeHop(temp.resolve("hop"));
        Path repository = Files.createDirectories(temp.resolve("repository"));
        Files.createDirectories(repository.resolve("hop/pipelines"));
        Files.writeString(repository.resolve("data.csv"), "id,name\n1,A\n", StandardCharsets.UTF_8);
        var runner = new ProcessHopRunner(connections(), null, repository, hopHome.toString(), "uddp-mvp");
        var source = new SourceDefinition("src", "file", "generic-file-ingest.hpl", "files", "data.csv", "id", "1.0");

        var result = runner.ingest(source, "XX", "1.0.0", UUID.randomUUID());

        assertEquals(3, result.exitCode());
        assertFalse(result.output().contains(PASSWORD), result.output());
        assertTrue(result.output().contains("******"), result.output());
        assertTrue(result.output().contains("SOURCE_FILE=" + repository.resolve("data.csv").toAbsolutePath()),
                result.output());
        assertTrue(result.output().contains(repository.resolve("hop/pipelines/generic-file-ingest.hpl")
                .toAbsolutePath().toString()), result.output());
        var picked = Pattern.compile("JDK_JAVA_OPTIONS: @(\\S+)").matcher(result.output());
        assertTrue(picked.find(), "JDK_JAVA_OPTIONS names only an argument file: " + result.output());
        assertFalse(Files.exists(Path.of(picked.group(1))), "The settings file is deleted after Hop exits");
    }

    static PostgresConnectionFactory connections() {
        return PostgresConnectionFactory.forSettings("jdbc:postgresql://localhost:5432/uddp_demo", "uddp", PASSWORD);
    }

    /** A hop-run stand-in that prints its command line, JDK_JAVA_OPTIONS and the argument file, then exits 3. */
    static Path fakeHop(Path home) throws Exception {
        Files.createDirectories(home);
        if (WINDOWS) {
            Files.writeString(home.resolve("hop-run.bat"), String.join("\r\n",
                    "@echo off",
                    "echo hop-run %*",
                    "echo NOTE: Picked up JDK_JAVA_OPTIONS: %JDK_JAVA_OPTIONS%",
                    "set \"ARGS=%JDK_JAVA_OPTIONS:~1%\"",
                    "type \"%ARGS%\"",
                    "echo Error connecting to database: password=" + PASSWORD,
                    "exit /b 3",
                    ""), StandardCharsets.UTF_8);
        } else {
            Path script = home.resolve("hop-run.sh");
            Files.writeString(script, String.join("\n",
                    "#!/bin/sh",
                    "echo hop-run \"$@\"",
                    "echo \"NOTE: Picked up JDK_JAVA_OPTIONS: $JDK_JAVA_OPTIONS\"",
                    "cat \"${JDK_JAVA_OPTIONS#@}\"",
                    "echo \"Error connecting to database: password=" + PASSWORD + "\"",
                    "exit 3",
                    ""), StandardCharsets.UTF_8);
            script.toFile().setExecutable(true);
        }
        return home;
    }
}
