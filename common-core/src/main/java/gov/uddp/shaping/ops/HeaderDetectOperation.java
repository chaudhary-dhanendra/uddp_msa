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
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code header-detect@1.0} finds the header row(s) of each sheet, drops everything above and including them, and
 * records the column names on every data row (carried keys {@code @h0}, {@code @h1}, ...).
 *
 * <p>Config: {@code maxScanRows} (default 20) rows are scanned per sheet; the header is the first row that is filled
 * across at least 60% of the widest scanned row and holds no number. {@code multiRow: auto} (default; {@code false}
 * disables) treats directly preceding all-text rows with at least two but fewer filled cells than the header as group
 * levels (up to three in all), so a merged group header ("Tranche 1") over sub-headers ("Amount") becomes
 * "Tranche 1 Amount". Blank cells in a group level repeat the last value to the left (a merged cell). Names have newlines and the annotations
 * "(Dropdown)", "(Required)", "(Optional)" removed, whitespace collapsed and are trimmed. A sheet with no header is
 * left unchanged.
 */
@Component
public class HeaderDetectOperation implements LayoutOperation {

    private static final Pattern ANNOTATION = Pattern.compile("\\((dropdown|required|optional)\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("[-+]?[\\d,]+(\\.\\d+)?%?");
    private static final int MAX_LEVELS = 3;

    @Override
    public String name() {
        return "header-detect";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public List<RawRow> apply(List<RawRow> rows, Map<String, Object> config) {
        int maxScan = OpSupport.integer(config, "maxScanRows", 20);
        boolean multiRow = !"false".equalsIgnoreCase(String.valueOf(config.getOrDefault("multiRow", "auto")));
        List<RawRow> result = new ArrayList<>();
        int start = 0;
        while (start < rows.size()) {
            int end = start + 1;
            while (end < rows.size() && OpSupport.sameSheet(rows.get(start), rows.get(end))) {
                end++;
            }
            result.addAll(sheet(rows.subList(start, end), maxScan, multiRow));
            start = end;
        }
        return result;
    }

    private List<RawRow> sheet(List<RawRow> rows, int maxScan, boolean multiRow) {
        int scan = Math.min(maxScan, rows.size());
        int widest = 0;
        for (int index = 0; index < scan; index++) {
            widest = Math.max(widest, OpSupport.filled(rows.get(index)).size());
        }
        int first = -1;
        for (int index = 0; index < scan && first < 0; index++) {
            RawRow row = rows.get(index);
            int filled = OpSupport.filled(row).size();
            if (filled > 0 && filled >= widest * 0.6 && textOnly(row)) {
                first = index;
            }
        }
        if (first < 0) {
            return rows;
        }
        List<RawRow> levels = new ArrayList<>(List.of(rows.get(first)));
        while (multiRow && levels.size() < MAX_LEVELS && first - (levels.size() - 1) > 0) {
            RawRow above = rows.get(first - levels.size());
            int filled = OpSupport.filled(above).size();
            if (filled >= 2 && filled < OpSupport.filled(levels.getFirst()).size() && textOnly(above)) {
                levels.addFirst(above);
            } else {
                break;
            }
        }
        List<String> names = names(levels);
        List<RawRow> data = new ArrayList<>();
        for (RawRow row : rows.subList(first + 1, rows.size())) {
            Map<String, String> carried = new LinkedHashMap<>(row.carried());
            for (int index = 0; index < names.size(); index++) {
                carried.put(HEADER_PREFIX + index, names.get(index));
            }
            data.add(OpSupport.withCarried(row, carried));
        }
        return data;
    }

    private static boolean textOnly(RawRow row) {
        return row.cells().stream().noneMatch(cell -> !blank(cell) && NUMBER.matcher(cell.trim()).matches());
    }

    private static List<String> names(List<RawRow> levels) {
        int width = levels.stream().mapToInt(level -> level.cells().size()).max().orElse(0);
        List<String> names = new ArrayList<>();
        for (int column = 0; column < width; column++) {
            List<String> parts = new ArrayList<>();
            for (int level = 0; level < levels.size(); level++) {
                String value = OpSupport.cell(levels.get(level), column);
                boolean group = level < levels.size() - 1;
                if (blank(value) && group) {
                    value = mergedValue(levels.get(level), column);
                }
                String clean = clean(value);
                if (!clean.isEmpty() && (parts.isEmpty() || !parts.getLast().equals(clean))) {
                    parts.add(clean);
                }
            }
            names.add(String.join(" ", parts));
        }
        return names;
    }

    /** A merged cell shows its value only in its first column; blanks to its right repeat it. */
    private static String mergedValue(RawRow level, int column) {
        for (int index = Math.min(column, level.cells().size()) - 1; index >= 0; index--) {
            String cell = level.cells().get(index);
            if (!blank(cell)) {
                return cell;
            }
        }
        return null;
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String text = ANNOTATION.matcher(value.replaceAll("[\\r\\n]+", " ")).replaceAll(" ");
        return text.replaceAll("\\s+", " ").trim();
    }
}
