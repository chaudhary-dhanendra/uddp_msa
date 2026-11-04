package gov.uddp.shaping.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Keeps the committed workbook equal to the generator. Regenerate with -Duddp.regenerate-fixtures=true. */
class LayoutPatternsFixtureTest {

    private static final Path COMMITTED = Path.of("src/test/resources/shaping/" + LayoutPatterns.WORKBOOK);

    @Test
    void committedWorkbookMatchesTheGenerator() throws Exception {
        if (Boolean.getBoolean("uddp.regenerate-fixtures")) {
            Files.createDirectories(COMMITTED.getParent());
            LayoutPatterns.write(COMMITTED);
        }
        for (String sheet : LayoutPatterns.SHEETS.keySet()) {
            String[][] expected = LayoutPatterns.SHEETS.get(sheet);
            List<List<String>> actual = LayoutPatterns.read(sheet).stream().map(row -> row.cells()).toList();
            assertEquals(expected.length, actual.size(), sheet);
            for (int r = 0; r < expected.length; r++) {
                assertEquals(Arrays.asList(expected[r]), actual.get(r).subList(0, expected[r].length), sheet + " row " + r);
            }
        }
    }
}
