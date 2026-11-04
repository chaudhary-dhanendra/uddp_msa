package gov.uddp.shaping.parsers;

import static org.assertj.core.api.Assertions.assertThat;

import gov.uddp.shaping.ops.DittoOperation;
import gov.uddp.shaping.api.RawRow;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** After an approved word list change the parsers and operations read the files again, with no restart. */
class WordListReloadTest {
    @TempDir
    Path tmp;

    @Test
    void parsersAndDittoReadTheListsAgain() throws IOException {
        Path platform = tmp.resolve("platform");
        try (Stream<Path> files = Files.walk(Path.of("../../platform/reference-data"))) {
            for (Path source : files.toList()) {
                Path target = platform.resolve("reference-data").resolve(Path.of("../../platform/reference-data").relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
        NameRelationParsing parsing = new NameRelationParsing(platform);
        NameNormaliseParser parser = new NameNormaliseParser(parsing);
        DittoOperation ditto = new DittoOperation(platform.toString());

        assertThat(parser.parse("Dr Ram Kumar", Map.of()).values().toString()).contains("DR");
        assertThat(ditto.apply(List.of(row(1, "A", "x"), row(2, "A", "same")), Map.of("columns", List.of(1))).get(1).cells())
                .containsExactly("A", "same");

        Path shaping = platform.resolve("reference-data/shaping");
        Files.writeString(shaping.resolve("honorifics.yaml"), "honorifics: [\"Dr\"]\n");
        Files.writeString(shaping.resolve("ditto-tokens.yaml"), "tokens: [\"same\"]\n");
        parsing.reloadWordLists();
        ditto.reloadWordLists();

        assertThat(parser.parse("Dr Ram Kumar", Map.of()).values().toString()).doesNotContain("DR").contains("RAM KUMAR");
        assertThat(ditto.apply(List.of(row(1, "A", "x"), row(2, "A", "same")), Map.of("columns", List.of(1))).get(1).cells())
                .containsExactly("A", "x");
    }

    private static RawRow row(int number, String... cells) {
        return new RawRow("f", "s", number, List.of(cells), Map.of());
    }
}
