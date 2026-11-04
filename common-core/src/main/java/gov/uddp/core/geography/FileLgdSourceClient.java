package gov.uddp.core.geography;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads an LGD snapshot from a CSV or JSON file (Track F1), configured with {@code uddp.geography.lgd.source-file}.
 * CSV: a header with {@code entityType,code,name,status,parentType,parentCode,effectiveFrom} (any order; status,
 * parent and effectiveFrom optional) and an optional {@code # asOf=YYYY-MM-DD} first line. JSON: either an array of
 * rows or {@code {"asOf": "...", "entities": [...]}}. Without an as-of date the file's modification date is used.
 */
@Component
public class FileLgdSourceClient implements LgdSourceClient {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String file;

    public FileLgdSourceClient(@Value("${uddp.geography.lgd.source-file:}") String file) {
        this.file = file;
    }

    @Override
    public String describe() {
        return file == null || file.isBlank() ? "no LGD source configured" : "file " + Path.of(file).getFileName();
    }

    @Override
    public Optional<LgdSnapshotData> fetch() {
        if (file == null || file.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(read(Path.of(file)));
    }

    /** Parses one CSV or JSON snapshot file. */
    public static LgdSnapshotData read(Path path) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            LocalDate modified = LocalDate.ofInstant(Files.getLastModifiedTime(path).toInstant(), java.time.ZoneOffset.UTC);
            return parse(path.getFileName().toString(), text, modified);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the LGD snapshot " + path.getFileName(), e);
        }
    }

    /** Parses snapshot text: JSON when the label ends in .json, otherwise CSV. */
    public static LgdSnapshotData parse(String label, String text, LocalDate fallbackAsOf) throws IOException {
        return label.toLowerCase(Locale.ROOT).endsWith(".json") ? json(label, text, fallbackAsOf)
                : csv(label, text, fallbackAsOf);
    }

    static LgdSnapshotData json(String label, String text, LocalDate fallbackAsOf) throws IOException {
        JsonNode root = JSON.readTree(text);
        JsonNode entities = root.isArray() ? root : root.path("entities");
        LocalDate asOf = root.hasNonNull("asOf") ? date(root.get("asOf").asText(), 0) : fallbackAsOf;
        List<LgdRow> rows = new ArrayList<>();
        int line = 0;
        for (JsonNode row : entities) {
            line++;
            rows.add(new LgdRow(line, text(row, "entityType"), text(row, "code"), text(row, "name"), text(row, "status"),
                    text(row, "parentType"), text(row, "parentCode"), date(text(row, "effectiveFrom"), line)));
        }
        return new LgdSnapshotData(label, asOf, rows);
    }

    static LgdSnapshotData csv(String label, String text, LocalDate fallbackAsOf) {
        List<String> lines = new ArrayList<>(text.lines().toList());
        LocalDate asOf = fallbackAsOf;
        int skipped = 0;
        while (!lines.isEmpty() && (lines.getFirst().isBlank() || lines.getFirst().startsWith("#"))) {
            String comment = lines.removeFirst().replace("#", "").strip();
            skipped++;
            if (comment.startsWith("asOf=")) {
                asOf = date(comment.substring("asOf=".length()).strip(), skipped);
            }
        }
        if (lines.isEmpty()) {
            return new LgdSnapshotData(label, asOf, List.of());
        }
        List<String> header = split(lines.getFirst().replace("﻿", ""));
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            columns.put(header.get(i).strip(), i);
        }
        for (String required : List.of("entityType", "code", "name")) {
            if (!columns.containsKey(required)) {
                throw new IllegalArgumentException("The header line must contain entityType, code and name"
                        + " (missing " + required + ")");
            }
        }
        List<LgdRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            int line = i + 1 + skipped;
            if (lines.get(i).isBlank()) {
                continue;
            }
            List<String> cells = split(lines.get(i));
            rows.add(new LgdRow(line, cell(cells, columns, "entityType"), cell(cells, columns, "code"),
                    cell(cells, columns, "name"), cell(cells, columns, "status"), cell(cells, columns, "parentType"),
                    cell(cells, columns, "parentCode"), date(cell(cells, columns, "effectiveFrom"), line)));
        }
        return new LgdSnapshotData(label, asOf, rows);
    }

    /** One CSV line; double quotes enclose cells that hold commas, and a doubled quote is a quote. */
    private static List<String> split(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted && ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                cell.append('"');
                i++;
            } else if (ch == '"') {
                quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(ch);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private static String cell(List<String> cells, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null || index >= cells.size()) {
            return null;
        }
        String value = cells.get(index).strip();
        return value.isEmpty() ? null : value;
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText().strip();
    }

    /** An unparseable date fails the read, before anything is staged. */
    private static LocalDate date(String value, int line) {
        try {
            return value == null ? null : LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException((line > 0 ? "line " + line + ": " : "asOf: ") + value + " is not a date (use YYYY-MM-DD)", e);
        }
    }
}
