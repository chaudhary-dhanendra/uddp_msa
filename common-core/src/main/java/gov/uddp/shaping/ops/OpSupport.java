package gov.uddp.shaping.ops;

import gov.uddp.shaping.api.RawRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/** Helpers shared by the built-in layout operations. Column names live in the carried map as {@code @h<index>}. */
final class OpSupport {

    static final String VERSION = "1.0";
    static final String HEADER_PREFIX = "@h";

    private OpSupport() {
    }

    static boolean blank(String cell) {
        return cell == null || cell.isBlank();
    }

    /** Lower-case with all whitespace removed, for case- and space-insensitive comparison. */
    static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase();
    }

    static String cell(RawRow row, int index) {
        return index >= 0 && index < row.cells().size() ? row.cells().get(index) : null;
    }

    static String headerName(RawRow row, int index) {
        return row.carried().get(HEADER_PREFIX + index);
    }

    /** Resolves a column given as a header name (case/space-insensitive) or a zero-based index. */
    static int columnIndex(RawRow row, String column) {
        String wanted = normalize(column);
        for (int index = 0; index < row.cells().size(); index++) {
            String header = headerName(row, index);
            if (header != null && normalize(header).equals(wanted)) {
                return index;
            }
        }
        if (wanted.matches("\\d+")) {
            return Integer.parseInt(wanted);
        }
        throw new IllegalArgumentException("Row " + row.row() + " of sheet " + row.sheet() + " has no column: " + column);
    }

    static List<Integer> columnIndexes(RawRow row, List<String> columns) {
        List<Integer> indexes = new ArrayList<>();
        for (String column : columns) {
            indexes.add(columnIndex(row, column));
        }
        return indexes;
    }

    /**
     * Resolves a configured shaping column when it exists on this row. Unlike {@link #columnIndex(RawRow, String)},
     * this deliberately treats a column that belongs to another sheet layout as not applicable.  This lookup keeps
     * header case significant: heterogeneous sheets can legitimately have separate rules for {@code VILLAGE} and
     * {@code Village} before a later alias operation unifies them.
     */
    static OptionalInt optionalColumnIndex(RawRow row, String column) {
        String wanted = column == null ? "" : column.trim();
        if (!wanted.matches("\\d+") && row.carried().keySet().stream().noneMatch(key -> key.startsWith(HEADER_PREFIX))) {
            throw new IllegalArgumentException("Row " + row.row() + " of sheet " + row.sheet()
                    + " has no established headers for column: " + column);
        }
        for (int index = 0; index < row.cells().size(); index++) {
            String header = headerName(row, index);
            if (header != null && header.trim().equals(wanted)) {
                return OptionalInt.of(index);
            }
        }
        if (wanted.matches("\\d+")) {
            int index = Integer.parseInt(wanted);
            return index < row.cells().size() ? OptionalInt.of(index) : OptionalInt.empty();
        }
        return OptionalInt.empty();
    }

    /** Existing configured columns only, for optional Tier-1 value shaping across heterogeneous sheet layouts. */
    static List<Integer> existingColumnIndexes(RawRow row, List<String> columns) {
        List<Integer> indexes = new ArrayList<>();
        for (String column : columns) {
            optionalColumnIndex(row, column).ifPresent(indexes::add);
        }
        return indexes;
    }

    static RawRow withCells(RawRow row, List<String> cells) {
        return new RawRow(row.file(), row.sheet(), row.row(), cells, row.carried());
    }

    static RawRow withCarried(RawRow row, Map<String, String> carried) {
        return new RawRow(row.file(), row.sheet(), row.row(), row.cells(), carried);
    }

    static RawRow withCarried(RawRow row, String key, String value) {
        Map<String, String> carried = new LinkedHashMap<>(row.carried());
        carried.put(key, value);
        return withCarried(row, carried);
    }

    /** True when both rows come from the same sheet of the same file (carry-down state resets otherwise). */
    static boolean sameSheet(RawRow a, RawRow b) {
        return a != null && b != null && Objects.equals(a.file(), b.file()) && Objects.equals(a.sheet(), b.sheet());
    }

    static List<String> strings(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(value));
    }

    static String requiredString(Map<String, Object> config, String key, String operation) {
        Object value = config.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(operation + " requires '" + key + "'");
        }
        return value.toString();
    }

    static int integer(Map<String, Object> config, String key, int fallback) {
        return config.get(key) instanceof Number number ? number.intValue() : fallback;
    }

    /** Non-blank cell indexes of a row. */
    static List<Integer> filled(RawRow row) {
        List<Integer> filled = new ArrayList<>();
        for (int index = 0; index < row.cells().size(); index++) {
            if (!blank(row.cells().get(index))) {
                filled.add(index);
            }
        }
        return filled;
    }

    /** True when exactly the listed columns are filled and every other cell is blank. */
    static boolean onlyFilled(RawRow row, List<String> columns) {
        List<Integer> wanted = existingColumnIndexes(row, columns);
        if (wanted.size() != columns.size()) {
            return false;
        }
        List<Integer> filled = filled(row);
        return !wanted.isEmpty() && filled.size() == wanted.size() && filled.containsAll(wanted);
    }
}
