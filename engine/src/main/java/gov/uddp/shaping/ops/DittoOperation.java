package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.OpSupport.VERSION;
import static gov.uddp.shaping.ops.OpSupport.blank;

import gov.uddp.shaping.api.LayoutOperation;
import gov.uddp.shaping.api.RawRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code ditto@1.0} replaces ditto marks with the value above.
 *
 * <p>Config: {@code columns: [..]} names or zero-based indexes; {@code tokens} (default {@code -do-}, {@code _do-},
 * {@code do} and the double quote). A cell whose trimmed text equals a token, ignoring case, takes the (already
 * resolved) value of the row above in the same sheet; with nothing above it is left as is. Blank cells are not touched
 * (use carry-down for those).
 */
@Component
public class DittoOperation implements LayoutOperation, gov.uddp.shaping.admin.WordListConsumer {

    private static final List<String> BUILT_IN_TOKENS = List.of("-do-", "_do-", "do", "\"");

    private final java.nio.file.Path tokensFile;
    private volatile List<String> defaultTokens = BUILT_IN_TOKENS;

    /** Built-in tokens only. */
    public DittoOperation() {
        this.tokensFile = null;
    }

    /** Default tokens from {@code reference-data/shaping/ditto-tokens.yaml}, when that file exists. */
    @org.springframework.beans.factory.annotation.Autowired
    public DittoOperation(@org.springframework.beans.factory.annotation.Value("${uddp.platform-directory:../platform}")
                          String platformDirectory) {
        this.tokensFile = java.nio.file.Path.of(platformDirectory, "reference-data", "shaping", "ditto-tokens.yaml");
        reloadWordLists();
    }

    @Override
    public void reloadWordLists() {
        if (tokensFile == null || !java.nio.file.Files.isRegularFile(tokensFile)) {
            return;
        }
        try {
            var tokens = new com.fasterxml.jackson.databind.ObjectMapper(
                    new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readTree(tokensFile.toFile()).path("tokens");
            List<String> read = new ArrayList<>();
            tokens.forEach(token -> read.add(token.asText()));
            defaultTokens = read.isEmpty() ? BUILT_IN_TOKENS : List.copyOf(read);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Cannot read " + tokensFile, e);
        }
    }

    @Override
    public String name() {
        return "ditto";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public List<RawRow> apply(List<RawRow> rows, Map<String, Object> config) {
        List<String> columns = OpSupport.strings(config, "columns");
        List<String> configured = OpSupport.strings(config, "tokens");
        List<String> tokens = (configured.isEmpty() ? defaultTokens : configured).stream()
                .map(token -> token.trim().toLowerCase(Locale.ROOT)).toList();
        List<RawRow> result = new ArrayList<>();
        Map<Sheet, Map<Integer, String>> lastBySheet = new HashMap<>();
        for (RawRow row : rows) {
            Map<Integer, String> last = lastBySheet.computeIfAbsent(new Sheet(row.file(), row.sheet()),
                    ignored -> new HashMap<>());
            List<String> cells = new ArrayList<>(row.cells());
            for (int index : OpSupport.existingColumnIndexes(row, columns)) {
                if (index >= cells.size() || blank(cells.get(index))) {
                    continue;
                }
                if (tokens.contains(cells.get(index).trim().toLowerCase(Locale.ROOT)) && last.get(index) != null) {
                    cells.set(index, last.get(index));
                }
                last.put(index, cells.get(index));
            }
            result.add(OpSupport.withCells(row, cells));
        }
        return result;
    }

    /** Ditto values are stateful within, but never across, a workbook sheet. */
    private record Sheet(String file, String name) {
    }
}
