package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.OpSupport.HEADER_PREFIX;
import static gov.uddp.shaping.ops.OpSupport.VERSION;
import static gov.uddp.shaping.ops.OpSupport.blank;

import gov.uddp.shaping.api.LayoutOperation;
import gov.uddp.shaping.api.RawRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code wide-to-long@1.0} turns repeating column groups into one row per group.
 *
 * <p>Config: {@code pattern: "Tranche {n} *"} (column-name pattern; {@code {n}}, written as its own word, is the group
 * number, {@code *} the rest
 * of the name, ignoring case and extra spaces), {@code n: [1, 2]} the group numbers and {@code into: NAME} the carried
 * value that receives n. For each input row and each n, one output row holds the non-grouped columns plus the columns of
 * group n renamed to the {@code *} part ("Tranche 1 Amount" becomes "Amount"). A group whose cells are all blank is
 * not emitted. Needs header-detect to run first.
 */
@Component
public class WideToLongOperation implements LayoutOperation {

    private static final String MARK = "{n}";

    @Override
    public String name() {
        return "wide-to-long";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public List<RawRow> apply(List<RawRow> rows, Map<String, Object> config) {
        String pattern = OpSupport.requiredString(config, "pattern", name());
        String into = OpSupport.requiredString(config, "into", name());
        List<String> groups = OpSupport.strings(config, "n");
        List<RawRow> result = new ArrayList<>();
        for (RawRow row : rows) {
            for (String n : groups) {
                Pattern regex = regex(pattern, n);
                List<String> cells = new ArrayList<>();
                Map<String, String> carried = new LinkedHashMap<>();
                boolean groupFilled = false;
                for (int index = 0; index < row.cells().size(); index++) {
                    String header = OpSupport.headerName(row, index);
                    Matcher match = header == null ? null : regex.matcher(header.trim());
                    boolean inGroup = match != null && match.matches();
                    if (!inGroup && header != null && inAnyGroup(pattern, groups, header)) {
                        continue;
                    }
                    String cell = row.cells().get(index);
                    groupFilled |= inGroup && !blank(cell);
                    if (header != null) {
                        carried.put(HEADER_PREFIX + cells.size(), inGroup ? match.group(1).trim() : header);
                    }
                    cells.add(cell);
                }
                if (!groupFilled) {
                    continue;
                }
                row.carried().forEach((key, value) -> {
                    if (!key.startsWith(HEADER_PREFIX)) {
                        carried.put(key, value);
                    }
                });
                carried.put(into, n);
                result.add(new RawRow(row.file(), row.sheet(), row.row(), cells, carried));
            }
        }
        return result;
    }

    private static boolean inAnyGroup(String pattern, List<String> groups, String header) {
        return groups.stream().anyMatch(n -> regex(pattern, n).matcher(header.trim()).matches());
    }

    /** {@code {n}} becomes the group number; the last {@code *} is captured, any earlier one is not. */
    private static Pattern regex(String pattern, String n) {
        String[] parts = pattern.split("\\*", -1);
        StringBuilder regex = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            regex.append(spaced(parts[index]));
            if (index < parts.length - 1) {
                regex.append(index == parts.length - 2 ? "\\s*(.*)" : ".*");
            }
        }
        // The group number must not be the start of a longer number (1 versus 10).
        String resolved = regex.toString().replace(Pattern.quote(MARK), Pattern.quote(n) + "(?!\\d)");
        return Pattern.compile(resolved, Pattern.CASE_INSENSITIVE);
    }

    /** Literal text where any run of whitespace matches any run of whitespace. */
    private static String spaced(String literal) {
        List<String> words = new ArrayList<>();
        for (String word : literal.trim().split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(Pattern.quote(word));
            }
        }
        return String.join("\\s+", words);
    }
}
