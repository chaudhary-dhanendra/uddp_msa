package gov.uddp.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Profiling a large file reads the first 1,000 rows plus a reservoir sample of 1,000 more, and reports both counts. */
class ProfileSamplingTest {
    @TempDir
    Path work;

    private final CsvProfiler profiler = new CsvProfiler();

    private Path file(int rows) throws IOException {
        List<String> lines = new ArrayList<>(List.of("id,name"));
        for (int i = 0; i < rows; i++) {
            lines.add(i + ",Person " + i);
        }
        return Files.write(work.resolve("big.csv"), lines, StandardCharsets.UTF_8);
    }

    @Test
    void aLargeFileIsSampledAsTheFirstThousandPlusAThousandRandomRows() throws IOException {
        var table = profiler.sample(file(50_000), 1_000, 1_000, new Random(7));

        assertEquals(50_000, table.totalRows());
        assertEquals(2_000, table.rows().size());
        for (int i = 0; i < 1_000; i++) {
            assertEquals(Integer.toString(i), table.rows().get(i).get("id"), "the first 1,000 rows come first, in order");
        }
        var random = table.rows().subList(1_000, 2_000).stream().map(row -> Integer.parseInt(row.get("id"))).toList();
        assertTrue(random.stream().allMatch(id -> id >= 1_000), "the random rows come from after the first 1,000");
        assertEquals(1_000, random.stream().distinct().count());
        assertTrue(random.stream().anyMatch(id -> id > 40_000) && random.stream().anyMatch(id -> id < 10_000),
                "the random rows are spread over the whole file");
    }

    @Test
    void theProfileSaysHowManyRowsWereSampledOfHowMany() throws IOException {
        var profile = profiler.profile(profiler.sample(file(50_000), 1_000, 1_000, new Random(7)));

        assertEquals(2_000, profile.rowsSampled());
        assertEquals(50_000, profile.rowsTotal());
        assertEquals(50_000, profile.rowCount());
    }

    @Test
    void aFileThatFitsInTheSampleIsReadWhole() throws IOException {
        var profile = profiler.profile(profiler.sample(file(1_500), 1_000, 1_000, new Random(7)));

        assertEquals(1_500, profile.rowsSampled());
        assertEquals(1_500, profile.rowsTotal());
    }
}
