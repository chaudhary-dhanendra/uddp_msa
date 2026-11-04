package gov.uddp.core.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A source whose location is a folder ingests the oldest waiting file, then moves it to processed/. */
class LandingFolderTest {
    @TempDir
    Path root;

    private SourceDefinition source(String location) {
        return new SourceDefinition("pds", "file", "generic-file-ingest.hpl", null, location, "ration_no", "1.0",
                List.of("ration_no"));
    }

    @Test
    void aDirectoryLocationIsALandingFolderAndAFileLocationIsNot() throws IOException {
        Files.createDirectories(root.resolve("landing/pds"));
        Files.writeString(root.resolve("one.csv"), "a\n1\n");
        var landing = new LandingFolder(root.toString());

        assertTrue(landing.isLandingFolder(source("landing/pds")));
        assertFalse(landing.isLandingFolder(source("one.csv")));
        assertTrue(landing.next(source("landing/pds")).isEmpty());
    }

    @Test
    void theOldestNamedFileIsIngestedAndThenArchivedSoItIsNeverIngestedTwice() throws IOException {
        Path folder = Files.createDirectories(root.resolve("landing/pds"));
        Files.writeString(folder.resolve("2026-10-02.csv"), "a\n2\n");
        Files.writeString(folder.resolve("2026-10-01.csv"), "a\n1\n");
        Files.writeString(folder.resolve("notes.txt"), "not a delivery");
        var landing = new LandingFolder(root.toString());
        var source = source("landing/pds");

        var first = landing.next(source).orElseThrow();
        assertEquals("2026-10-01.csv", first.file().getFileName().toString());
        assertEquals(first.file().toString(), first.source().location());
        assertEquals("ration_no", first.source().recordIdField());
        first.completed().run();

        assertFalse(Files.exists(first.file()));
        try (var archived = Files.list(folder.resolve("processed"))) {
            assertEquals(1, archived.count());
        }
        assertEquals("2026-10-02.csv", landing.next(source).orElseThrow().file().getFileName().toString());
        assertEquals(1, landing.waiting(source).size());
    }
}
