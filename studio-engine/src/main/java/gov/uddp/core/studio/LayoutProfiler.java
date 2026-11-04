package gov.uddp.core.studio;

import gov.uddp.core.identity.VerhoeffChecksum;
import gov.uddp.core.studio.StudioModels.LayoutProposal;
import gov.uddp.shaping.api.RawRow;
import gov.uddp.shaping.api.RelationMarkers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Profiles the raw rows of an uploaded file (all sheets) for messy-layout patterns and proposes the generic layout
 * steps and field parsers that would tidy them (ADR-0015: no source or state names). Every proposal carries the
 * number of rows or values that show the pattern; nothing is applied until the user accepts it.
 */
class LayoutProfiler {
    private static final int HEADER_SCAN = 20;
    private static final double HEADER_FILL = 0.6;
    private static final double SHARE = 0.3;
    private static final Pattern SERIAL = Pattern.compile("\\d{1,5}");
    private static final Pattern NUMERIC = Pattern.compile("[-+]?[\\d,]+(\\.\\d+)?%?");
    private static final Pattern TOTAL = Pattern.compile("(?i)^\\s*(grand\\s+|sub\\s*)?total\\b|^\\s*=?\\s*SUM\\(");
    private static final String TOTAL_REGEX = "(?i)^\\s*(grand\\s+|sub\\s*)?total\\b|^\\s*=?\\s*SUM\\(";
    private static final Pattern DICTIONARY = Pattern.compile("(?i)^\\s*(value|logic)\\b.*");
    private static final Pattern MASKED = Pattern.compile("(?i)^[x*•#]{4,}[\\s-]?(\\d{4})$");
    private static final Pattern TWELVE = Pattern.compile("\\d{12}");
    private static final Pattern INDIAN_GROUPING = Pattern.compile("-?\\d{1,2}(,\\d{2})+,\\d{3}(\\.\\d+)?");
    private static final Set<String> DITTO = Set.of("-do-", "_do-", "do", "\"", "”", "〃", "ditto");
    private static final Set<String> ADDRESS_WORDS = Set.of("address", "addr", "location");
    private static final Pattern WORDS = Pattern.compile("[^a-z0-9]+");

    private final RelationMarkers relations;
    private final VerhoeffChecksum verhoeff = new VerhoeffChecksum();

    /** {@code relations} may be null: composite name-relation cells are then not looked for. */
    LayoutProfiler(RelationMarkers relations) {
        this.relations = relations;
    }

    /** One sheet with its detected header and the data rows under it. */
    private record Sheet(String name, List<RawRow> all, int headerIndex, int headerLevels, List<String> header,
                         List<RawRow> data) {
    }

    List<LayoutProposal> profile(List<RawRow> rows) {
        Map<String, List<RawRow>> bySheet = new LinkedHashMap<>();
        for (RawRow row : rows) {
            bySheet.computeIfAbsent(row.sheet() == null ? "" : row.sheet(), k -> new ArrayList<>()).add(row);
        }
        boolean multiSheet = bySheet.size() > 1;
        List<Sheet> sheets = new ArrayList<>();
        List<LayoutProposal> out = new ArrayList<>();
        for (var entry : bySheet.entrySet()) {
            Sheet sheet = sheet(entry.getKey(), entry.getValue());
            if (sheet == null) {
                continue;
            }
            sheets.add(sheet);
            String label = entry.getKey().isEmpty() ? null : entry.getKey();
            titleAndHeader(sheet, label, out);
            dataDictionary(sheet, label, out);
            totals(sheet, label, out);
            templateRows(sheet, label, out);
            sections(sheet, label, out);
            ditto(sheet, label, out);
            carryDown(sheet, label, out);
            numbers(sheet, label, out);
            columnValues(sheet, label, out);
        }
        if (multiSheet) {
            aliases(sheets, out);
            if (sheets.size() > 1) {
                // Rows from several sheets keep where they came from as an ordinary field (mappable like any column).
                out.add(layout("sheet-name", null, "Rows come from " + sheets.size()
                        + " sheets: keep each row's sheet name in a \"sheet\" column", sheets.size(), sheets.size(),
                        "{ op: sheet-name, as: sheet }"));
            }
        }
        return out;
    }

    // ---- sheet structure ---------------------------------------------------------------------------------------

    private Sheet sheet(String name, List<RawRow> rows) {
        int scan = Math.min(HEADER_SCAN, rows.size());
        int widest = 0;
        for (int i = 0; i < scan; i++) {
            widest = Math.max(widest, filled(rows.get(i)));
        }
        if (widest < 2) {
            return null;
        }
        int header = -1;
        for (int i = 0; i < scan; i++) {
            RawRow row = rows.get(i);
            if (filled(row) >= Math.ceil(widest * HEADER_FILL) && filled(row) >= 2 && textOnly(row)) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            return null;
        }
        int levels = 1;
        // A sparser row of text directly above the header holds merged group labels: a multi-row header.
        if (header > 0) {
            RawRow above = rows.get(header - 1);
            if (filled(above) >= 2 && filled(above) < filled(rows.get(header)) && textOnly(above)) {
                levels = 2;
            }
        }
        List<String> names = new ArrayList<>();
        rows.get(header).cells().forEach(c -> names.add(c == null ? "" : c.replaceAll("\\s+", " ").strip()));
        return new Sheet(name, rows, header, levels, names, new ArrayList<>(rows.subList(header + 1, rows.size())));
    }

    private void titleAndHeader(Sheet sheet, String label, List<LayoutProposal> out) {
        int titleRows = sheet.headerIndex() - (sheet.headerLevels() - 1);
        if (titleRows > 0) {
            out.add(layout("header-detect", label, "Title rows above the header: the header is row "
                    + (sheet.headerIndex() + 1) + (sheet.headerLevels() > 1 ? " (with a group-label row above it)" : ""),
                    titleRows, sheet.headerIndex() + 1, "{ op: header-detect, maxScanRows: " + HEADER_SCAN + " }"));
        }
        if (sheet.headerLevels() > 1) {
            out.add(layout("header-detect", label, "Multi-row header: group labels in row " + sheet.headerIndex()
                    + " above the column names in row " + (sheet.headerIndex() + 1), sheet.headerLevels(),
                    sheet.headerLevels(), "{ op: header-detect, multiRow: true }"));
        }
    }

    private void dataDictionary(Sheet sheet, String label, List<LayoutProposal> out) {
        if (sheet.data().isEmpty()) {
            return;
        }
        RawRow first = sheet.data().getFirst();
        long filled = first.cells().stream().filter(c -> !blank(c)).count();
        long hits = first.cells().stream().filter(c -> !blank(c) && DICTIONARY.matcher(c).matches()).count();
        if (filled > 0 && hits * 2 >= filled) {
            out.add(layout("skip-rows", label, "Data-dictionary row under the header (cells start with Value or Logic)",
                    (int) hits, (int) filled, "{ op: skip-rows, rules: [ { anyColumn: \"(?i)^\\\\s*(value|logic)\\\\b\" } ] }"));
        }
    }

    private void totals(Sheet sheet, String label, List<LayoutProposal> out) {
        long totals = sheet.data().stream()
                .filter(row -> row.cells().stream().anyMatch(c -> !blank(c) && TOTAL.matcher(c).find())).count();
        if (totals > 0) {
            out.add(layout("skip-rows", label, "Totals row (a Total label or a SUM formula)", (int) totals,
                    sheet.data().size(), "{ op: skip-rows, rules: [ { anyColumn: \"" + TOTAL_REGEX.replace("\\", "\\\\")
                    + "\" } ] }"));
        }
    }

    /** Rows of a pre-printed template: only the serial-number cell is filled. */
    private void templateRows(Sheet sheet, String label, List<LayoutProposal> out) {
        List<RawRow> data = sheet.data();
        for (int c = 0; c < sheet.header().size(); c++) {
            final int column = c;
            List<RawRow> serialOnly = data.stream()
                    .filter(row -> filled(row) == 1 && !blank(cell(row, column)) && SERIAL.matcher(cell(row, column).strip()).matches())
                    .toList();
            long serialRows = data.stream().filter(row -> !blank(cell(row, column))
                    && SERIAL.matcher(cell(row, column).strip()).matches()).count();
            if (!serialOnly.isEmpty() && serialRows * 2 >= data.size() && !sheet.header().get(c).isBlank()) {
                out.add(layout("skip-rows", label, "Empty template rows: only the serial number in \"" + sheet.header().get(c)
                        + "\" is filled", serialOnly.size(), data.size(),
                        "{ op: skip-rows, rules: [ { onlyFilled: [\"" + sheet.header().get(c) + "\"] } ] }"));
                return;
            }
        }
    }

    /** A row with a single text cell between rows of data is a group heading ("District A"). */
    private void sections(Sheet sheet, String label, List<LayoutProposal> out) {
        List<RawRow> data = sheet.data();
        Map<Integer, List<RawRow>> byColumn = new LinkedHashMap<>();
        for (int i = 0; i < data.size() - 1; i++) {
            RawRow row = data.get(i);
            if (filled(row) != 1) {
                continue;
            }
            int column = firstFilled(row);
            String text = cell(row, column).strip();
            boolean dataBelow = data.subList(i + 1, data.size()).stream().anyMatch(r -> filled(r) >= 2);
            if (dataBelow && !SERIAL.matcher(text).matches() && !NUMERIC.matcher(text).matches()
                    && !TOTAL.matcher(text).find()) {
                byColumn.computeIfAbsent(column, k -> new ArrayList<>()).add(row);
            }
        }
        byColumn.forEach((column, found) -> {
            String name = column < sheet.header().size() ? sheet.header().get(column) : "";
            if (!name.isBlank()) {
                out.add(layout("section-rows", label, "Section rows: a single value in \"" + name + "\" heads a group of rows",
                        found.size(), data.size(),
                        "{ op: section-rows, onlyFilled: [\"" + name + "\"], carryAs: section }"));
            }
        });
    }

    private void ditto(Sheet sheet, String label, List<LayoutProposal> out) {
        List<String> columns = new ArrayList<>();
        Set<String> tokens = new LinkedHashSet<>();
        int count = 0;
        for (int c = 0; c < sheet.header().size(); c++) {
            String previous = null;
            int found = 0;
            for (RawRow row : sheet.data()) {
                String value = cell(row, c);
                if (blank(value)) {
                    continue;
                }
                String key = value.strip().toLowerCase(Locale.ROOT);
                if (DITTO.contains(key) && previous != null) {
                    found++;
                    tokens.add(key);
                } else if (!DITTO.contains(key)) {
                    previous = value;
                }
            }
            if (found > 0 && !sheet.header().get(c).isBlank()) {
                columns.add(sheet.header().get(c));
                count += found;
            }
        }
        if (!columns.isEmpty()) {
            out.add(layout("ditto", label, "Ditto marks (" + String.join(" ", tokens) + ") repeat the value above in "
                    + String.join(", ", columns), count, sheet.data().size(),
                    "{ op: ditto, columns: " + list(columns) + ", tokens: " + list(new ArrayList<>(tokens)) + " }"));
        }
    }

    /** A text column that is blank under a filled value on a share of rows whose other cells are filled. */
    private void carryDown(Sheet sheet, String label, List<LayoutProposal> out) {
        List<RawRow> rows = sheet.data().stream().filter(r -> filled(r) >= 2).toList();
        List<String> columns = new ArrayList<>();
        int count = 0;
        for (int index = 0; index < sheet.header().size(); index++) {
            final int c = index;
            if (sheet.header().get(c).isBlank() || rows.isEmpty() || blank(cell(rows.getFirst(), c))) {
                continue;
            }
            List<String> values = rows.stream().map(r -> cell(r, c)).filter(v -> !blank(v)).toList();
            long blanks = rows.size() - values.size();
            boolean text = values.stream().noneMatch(v -> NUMERIC.matcher(v.strip()).matches());
            boolean ditto = values.stream().anyMatch(v -> DITTO.contains(v.strip().toLowerCase(Locale.ROOT)));
            if (text && !ditto && blanks >= 2 && blanks >= SHARE * rows.size()) {
                columns.add(sheet.header().get(c));
                count += (int) blanks;
            }
        }
        if (!columns.isEmpty()) {
            out.add(layout("carry-down", label, "Blank means \"same as above\" in " + String.join(", ", columns), count,
                    rows.size(), "{ op: carry-down, columns: " + list(columns) + " }"));
        }
    }

    private void numbers(Sheet sheet, String label, List<LayoutProposal> out) {
        List<String> columns = new ArrayList<>();
        int count = 0;
        for (int c = 0; c < sheet.header().size(); c++) {
            int found = 0;
            for (RawRow row : sheet.data()) {
                if (INDIAN_GROUPING.matcher(cell(row, c).strip()).matches()) {
                    found++;
                }
            }
            if (found > 0 && !sheet.header().get(c).isBlank()) {
                columns.add(sheet.header().get(c));
                count += found;
            }
        }
        if (!columns.isEmpty()) {
            out.add(layout("numbers", label, "Indian digit grouping (12,34,567) in " + String.join(", ", columns), count,
                    sheet.data().size(), "{ op: numbers, grouping: indian, columns: " + list(columns) + " }"));
        }
    }

    // ---- column values -----------------------------------------------------------------------------------------

    private void columnValues(Sheet sheet, String label, List<LayoutProposal> out) {
        for (int index = 0; index < sheet.header().size(); index++) {
            final int c = index;
            String name = sheet.header().get(c);
            if (name.isBlank()) {
                continue;
            }
            List<String> values = sheet.data().stream().map(r -> cell(r, c).strip()).filter(v -> !v.isEmpty()).toList();
            if (values.isEmpty()) {
                continue;
            }
            int total = values.size();
            boolean nameAndRelation = false;
            if (relations != null) {
                long hits = values.stream().filter(relations::hasRelationMarker).count();
                if (hits > SHARE * total) {
                    nameAndRelation = true;
                    // The outputs become tidy columns the Map step offers (person name, relation type and name).
                    out.add(parse("name-relation", label, "Name and relation in one cell (\"Name S/O Father\") in \"" + name + "\"",
                            (int) hits, total, "{ sourceField: \"" + name + "\", parser: name-relation@1.0, outputs: { name: person_name,"
                                    + " relation.type: relation_type, relation.name: relation_name,"
                                    + " relation.deceased: relation_deceased, inferredGender: inferred_gender } }"));
                }
            }
            long masked = values.stream().filter(v -> MASKED.matcher(v).matches()).count();
            if (masked > SHARE * total) {
                out.add(parse("masked-id", label, "Masked identifier numbers (XXXXXXXX1234) in \"" + name + "\"",
                        (int) masked, total, "{ sourceField: \"" + name + "\", parser: masked-id@1.0 }"));
            }
            List<String> twelve = values.stream().map(v -> v.replaceAll("[\\s-]", "")).filter(v -> TWELVE.matcher(v).matches())
                    .toList();
            if (twelve.size() > SHARE * total) {
                long failing = twelve.stream().filter(v -> !verhoeff.valid(v)).count();
                if (failing > 0) {
                    out.add(new LayoutProposal("mapping", "onInvalid", label, "12-digit IDs in \"" + name + "\": "
                            + failing + " of " + twelve.size() + " fail the check digit, so keep them flagged instead of "
                            + "rejecting the record", (int) failing, twelve.size(),
                            "{ sourceField: \"" + name + "\", onInvalid: flag }", false));
                }
            }
            // A "Name S/O Father" cell has a slash too, but it is not an address.
            if (!nameAndRelation && addressColumn(name, values)) {
                out.add(parse("free-address", label, "Free-text address in \"" + name + "\" (village, block, district)",
                        total, total, "{ sourceField: \"" + name + "\", parser: free-address@1.0, outputs: {"
                                + " geography.village.name: address_village, geography.block.name: address_block,"
                                + " geography.district.name: address_district } }"));
            }
        }
    }

    private static boolean addressColumn(String name, List<String> values) {
        boolean named = List.of(WORDS.split(name.toLowerCase(Locale.ROOT))).stream().anyMatch(ADDRESS_WORDS::contains);
        long separated = values.stream().filter(v -> v.split("[,/]").length >= 2 && v.length() >= 8).count();
        long texty = values.stream().filter(v -> v.length() >= 8 && v.chars().anyMatch(Character::isLetter)).count();
        return (named && texty * 2 >= values.size()) || separated * 10 >= values.size() * 6L;
    }

    // ---- sheets that spell the same column differently -------------------------------------------------------------

    private void aliases(List<Sheet> sheets, List<LayoutProposal> out) {
        if (sheets.size() < 2) {
            return;
        }
        List<String> canonical = new ArrayList<>(sheets.getFirst().header());
        // column-aliases is keyed by the canonical/logical column.  Keep the source spellings as
        // its values: a workbook may have more than one variant of the same first-sheet column.
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        for (Sheet sheet : sheets.subList(1, sheets.size())) {
            for (String variant : sheet.header()) {
                if (variant.isBlank() || canonical.contains(variant)) {
                    continue;
                }
                for (String known : canonical) {
                    if (!known.isBlank() && sameColumn(variant, known)) {
                        aliases.computeIfAbsent(known, ignored -> new ArrayList<>()).add(variant);
                        break;
                    }
                }
            }
        }
        if (!aliases.isEmpty()) {
            StringBuilder yaml = new StringBuilder("{ op: column-aliases, aliases: {");
            aliases.forEach((known, variants) -> yaml.append(" \"").append(known).append("\": ")
                    .append(list(variants)).append(","));
            yaml.setLength(yaml.length() - 1);
            out.add(layout("column-aliases", null, "Sheets spell the same columns differently: "
                    + String.join(", ", aliases.values().stream().flatMap(List::stream).toList()),
                    aliases.values().stream().mapToInt(List::size).sum(), canonical.size(), yaml + " } }"));
        }
    }

    private static boolean sameColumn(String a, String b) {
        return SuggestionEngine.similarName(a, b) || initials(a).equalsIgnoreCase(b.replaceAll("\s+", ""))
                || initials(b).equalsIgnoreCase(a.replaceAll("\s+", "")) || abbreviated(a, b);
    }

    /** "Date of Birth" -> "DOB". */
    private static String initials(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : SuggestionEngine.tokens(name)) {
            out.append(word.charAt(0));
        }
        return out.toString();
    }

    /** Same number of words, each equal or an abbreviation of the other ("Mobile No" / "Mobile Number"). */
    private static boolean abbreviated(String a, String b) {
        List<String> left = SuggestionEngine.tokens(a);
        List<String> right = SuggestionEngine.tokens(b);
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            String x = left.get(i);
            String y = right.get(i);
            String shorter = x.length() <= y.length() ? x : y;
            String longer = shorter == x ? y : x;
            if (!shorter.equals(longer) && !(shorter.equals("no") && longer.equals("number"))
                    && !(shorter.length() >= 2 && shorter.charAt(0) == longer.charAt(0)
                    && subsequence(shorter, longer))) {
                return false;
            }
        }
        return true;
    }

    private static boolean subsequence(String shorter, String longer) {
        int at = 0;
        for (int i = 0; i < longer.length() && at < shorter.length(); i++) {
            if (longer.charAt(i) == shorter.charAt(at)) {
                at++;
            }
        }
        return at == shorter.length();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static LayoutProposal layout(String kind, String sheet, String description, int count, int total, String yaml) {
        return new LayoutProposal("layout", kind, sheet, description, count, total, yaml, false);
    }

    private static LayoutProposal parse(String kind, String sheet, String description, int count, int total, String yaml) {
        return new LayoutProposal("parse", kind, sheet, description, count, total, yaml, false);
    }

    private static String list(List<String> values) {
        return values.stream().map(v -> "\"" + v.replace("\"", "\\\"") + "\"").toList().toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String cell(RawRow row, int index) {
        return index < row.cells().size() && row.cells().get(index) != null ? row.cells().get(index) : "";
    }

    private static int filled(RawRow row) {
        return (int) row.cells().stream().filter(c -> !blank(c)).count();
    }

    private static int firstFilled(RawRow row) {
        for (int i = 0; i < row.cells().size(); i++) {
            if (!blank(row.cells().get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean textOnly(RawRow row) {
        return row.cells().stream().filter(c -> !blank(c)).noneMatch(c -> NUMERIC.matcher(c.strip()).matches());
    }
}
