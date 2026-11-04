package gov.uddp.core.studio;

import gov.uddp.core.studio.StudioModels.ShapePreview;
import gov.uddp.core.studio.StudioModels.ShapingConfig;
import gov.uddp.core.studio.StudioModels.Source;
import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.OperationLookup;
import gov.uddp.shaping.api.ParseResult;
import gov.uddp.shaping.api.RawRow;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * The Shape step's before/after: runs a draft's proposed layout steps and field parsers over the first rows of its
 * source, the same operations the pipeline runs, without saving anything or touching the database.
 */
@Service
public class ShapePreviewService {
    static final int SAMPLE_ROWS = 20;
    private static final String HEADER_PREFIX = "@h";

    private final OperationLookup registry;
    private final CsvProfiler profiler;

    public ShapePreviewService(OperationLookup registry, CsvProfiler profiler) {
        this.registry = registry;
        this.profiler = profiler;
    }

    /** Checks that every step names a registered operation or parser, so a bad step is refused when it is saved. */
    public void validate(ShapingConfig config) {
        for (Map<String, Object> step : config.layout()) {
            if (!(step.get("op") instanceof String op) || op.isBlank()) {
                throw new IllegalArgumentException("Every layout step needs an op");
            }
            registry.operation(op);
        }
        for (Map<String, Object> step : config.parse()) {
            if (!(step.get("sourceField") instanceof String field) || field.isBlank()) {
                throw new IllegalArgumentException("Every parse step needs a sourceField");
            }
            if (!(step.get("parser") instanceof String parser) || parser.isBlank()) {
                throw new IllegalArgumentException("Every parse step needs a parser");
            }
            registry.parser(parser);
        }
    }

    public ShapePreview preview(Source source, ShapingConfig config) {
        validate(config);
        List<RawRow> rows = sampleSource(source, config.sheets());
        boolean workbook = RawSheets.isWorkbook(Path.of(source.location()));
        List<Map<String, Object>> layout = workbook ? workbookLayout(config.layout()) : config.layout();
        String yaml = String.join("\n", PackGenerator.shapingLines(layout, config.parse()));

        List<RawRow> before = rows.stream().limit(SAMPLE_ROWS).toList();
        int width = before.stream().mapToInt(r -> r.cells().size()).max().orElse(0);
        List<String> beforeColumns = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            beforeColumns.add("col" + (i + 1));
        }
        List<List<String>> beforeRows = before.stream().map(r -> padded(r.cells(), width)).toList();

        List<RawRow> shaped = applyLayout(rows, layout).stream().limit(SAMPLE_ROWS).toList();
        Tidy tidy = tidy(shaped, config.parse());
        List<List<String>> afterRows = tidy.fields().stream()
                .map(values -> tidy.names().stream().map(n -> values.getOrDefault(n, "")).toList()).toList();
        return new ShapePreview(yaml, beforeColumns, beforeRows, List.copyOf(tidy.names()), afterRows,
                tidy.warnings().stream().distinct().limit(50).toList(),
                workbook ? before.stream().map(r -> String.valueOf(r.sheet())).toList() : null,
                workbook ? shaped.stream().map(r -> String.valueOf(r.sheet())).toList() : null);
    }

    /**
     * A workbook's tidy records as a table (up to {@code limit} rows, from the source's selected sheets), shaped by
     * its saved layout and parse steps exactly as the pipeline shapes them; used to profile, map and preview it.
     */
    public CsvProfiler.Table table(Source source, int limit) {
        List<RawRow> rows = sampleSource(source, source.sheets());
        List<Map<String, Object>> layout = workbookLayout(source.layout());
        List<RawRow> shaped = applyLayout(rows, layout);
        Tidy tidy = tidy(shaped.stream().limit(limit).toList(), source.parse() == null ? List.of() : source.parse());
        List<Map<String, String>> records = tidy.fields().stream().map(values -> {
            Map<String, String> record = new LinkedHashMap<>();
            tidy.names().forEach(n -> record.put(n, values.getOrDefault(n, "")));
            return record;
        }).toList();
        return new CsvProfiler.Table(List.copyOf(tidy.names()), records, shaped.size());
    }

    /**
     * The layout a workbook source runs: its saved steps, starting with header-detect when they do not name one, so a
     * sheet's column names are read from its header row (without it every column would be col1, col2, ...).
     */
    static List<Map<String, Object>> workbookLayout(List<Map<String, Object>> layout) {
        List<Map<String, Object>> steps = layout == null ? List.of() : layout;
        List<Map<String, Object>> withHeader = new ArrayList<>();
        steps.stream().filter(ShapePreviewService::headerDetect).forEach(withHeader::add);
        if (withHeader.isEmpty()) {
            withHeader.add(Map.of("op", "header-detect@1.0"));
        }
        steps.stream().filter(step -> !headerDetect(step)).forEach(withHeader::add);
        return List.copyOf(withHeader);
    }

    private static boolean headerDetect(Map<String, Object> step) {
        return String.valueOf(step.get("op")).startsWith("header-detect@");
    }

    private List<RawRow> applyLayout(List<RawRow> rows, List<Map<String, Object>> layout) {
        List<RawRow> shaped = rows;
        for (Map<String, Object> step : layout) {
            Map<String, Object> options = new LinkedHashMap<>(step);
            String op = String.valueOf(options.remove("op"));
            shaped = registry.operation(op).apply(shaped, options);
        }
        return shaped;
    }

    private record Tidy(List<String> names, List<Map<String, String>> fields, List<String> warnings) {
    }

    private Tidy tidy(List<RawRow> shaped, List<Map<String, Object>> parse) {
        List<String> names = new ArrayList<>();
        List<Map<String, String>> fields = new ArrayList<>();
        for (RawRow row : shaped) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < row.cells().size(); i++) {
                String name = row.carried().get(HEADER_PREFIX + i);
                values.put(name == null ? "col" + (i + 1) : name, row.cells().get(i));
            }
            row.carried().forEach((key, value) -> {
                if (!key.startsWith(HEADER_PREFIX)) {
                    values.put(key, value);
                }
            });
            fields.add(values);
            values.keySet().stream().filter(n -> !n.isBlank() && !names.contains(n)).forEach(names::add);
        }
        List<String> warnings = new ArrayList<>();
        for (Map<String, Object> step : parse) {
            String field = String.valueOf(step.get("sourceField"));
            String parserName = String.valueOf(step.get("parser"));
            FieldParser parser = registry.parser(parserName);
            Map<?, ?> outputs = step.get("outputs") instanceof Map<?, ?> map ? map : Map.of();
            for (Map<String, String> values : fields) {
                String raw = values.get(field);
                if (raw == null) {
                    continue;
                }
                ParseResult result = parser.parse(raw, step);
                double min = step.get("minConfidence") instanceof Number n ? n.doubleValue() : 0.0;
                if (result.confidence() < min) {
                    warnings.add(field + ": " + parserName + " confidence " + result.confidence() + " is below " + min);
                    continue;
                }
                result.warnings().forEach(w -> warnings.add(field + ": " + w));
                for (Map.Entry<?, ?> output : outputs.entrySet()) {
                    Object value = result.values().get(String.valueOf(output.getKey()));
                    String target = String.valueOf(output.getValue());
                    values.put(target, value == null ? "" : String.valueOf(value));
                    if (!names.contains(target)) {
                        names.add(target);
                    }
                }
            }
        }
        return new Tidy(names, fields, warnings);
    }

    /** The source's raw rows in file order: every sheet of a workbook, or the header and rows of a CSV as rows. */
    private List<RawRow> sampleSource(Source source, List<String> sheets) {
        if (!"file".equals(source.sourceType()) || source.location() == null) {
            throw new IllegalArgumentException("Shaping applies to uploaded file sources");
        }
        Path file = Path.of(source.location());
        List<RawRow> rows = RawSheets.read(file);
        if (rows.isEmpty()) {
            var table = profiler.read(file);
            rows = new ArrayList<>();
            rows.add(new RawRow(file.getFileName().toString(), null, 1, table.header(), Map.of()));
            int number = 1;
            for (Map<String, String> record : table.rows()) {
                final int line = ++number;
                rows.add(new RawRow(file.getFileName().toString(), null, line,
                        table.header().stream().map(h -> record.getOrDefault(h, "")).toList(), Map.of()));
            }
        }
        List<RawRow> selected = sheets == null || sheets.isEmpty() ? rows
                : rows.stream().filter(r -> r.sheet() == null || sheets.contains(r.sheet())).toList();
        return selected.stream()
                .sorted(Comparator.comparing((RawRow r) -> String.valueOf(r.sheet())).thenComparingInt(RawRow::row))
                .limit(5000).toList();
    }

    private static List<String> padded(List<String> cells, int width) {
        List<String> row = new ArrayList<>(cells);
        while (row.size() < width) {
            row.add("");
        }
        return row;
    }
}
