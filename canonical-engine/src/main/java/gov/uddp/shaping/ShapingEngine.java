package gov.uddp.shaping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.mapping.RawCsvRecordParser;
import gov.uddp.core.mapping.RawRecord;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.SourceDefinition;
import gov.uddp.core.validation.ValidationIssue;
import gov.uddp.core.validation.ValidationIssue.Severity;
import gov.uddp.core.mapping.RawRowReader;
import gov.uddp.shaping.api.BatchRows;
import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.ParseResult;
import gov.uddp.shaping.api.Provenance;
import gov.uddp.shaping.api.OperationLookup;
import gov.uddp.shaping.api.RawRow;
import gov.uddp.shaping.api.RowDroppedException;
import gov.uddp.shaping.api.RecordShaper;
import gov.uddp.shaping.api.TidyRecord;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The shaping layer between Raw and mapping: turns one raw record into a tidy record. A source with neither
 * {@code layout} nor {@code parse} passes the CSV fields through exactly as {@link RawCsvRecordParser} returns them.
 */
@Service
public class ShapingEngine implements RecordShaper {
    public static final String PASSTHROUGH = "passthrough";
    public static final String LOW_CONFIDENCE_RULE = "SHAPING_LOW_CONFIDENCE";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Sheets kept shaped at once; rows arrive sheet by sheet, so two cover a chunk that spans a sheet boundary. */
    private static final int SHEET_CACHE_SIZE = 2;
    private static final String HEADER_PREFIX = "@h";

    private final RawCsvRecordParser csv;
    private final OperationLookup registry;
    private final RawRowReader reader = new RawRowReader(JSON);
    private BatchRows batchRows;
    /** Layout result per delivery, source and sheet: the most recently shaped sheets (rows arrive sheet by sheet). */
    private final Map<String, SheetShape> sheets = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SheetShape> eldest) {
            return size() > SHEET_CACHE_SIZE;
        }
    };

    @Autowired
    public ShapingEngine(RawCsvRecordParser csv, OperationLookup registry) {
        this.csv = csv;
        this.registry = registry;
    }

    /** Passthrough-only engine, for callers without a registry. */
    public ShapingEngine(RawCsvRecordParser csv) {
        this(csv, null);
    }

    /** Sheet-wide context for {@code format: xlsx} sources (the raw records of the row's delivery). */
    @Autowired(required = false)
    public void setBatchRows(BatchRows batchRows) {
        this.batchRows = batchRows;
    }

    @Override
    public Shaped shapeWithWarnings(RawRecord raw, LoadedStatePack pack, String sourceId) {
        SourceDefinition source = source(pack, sourceId);
        if (source != null && ("xlsx".equals(source.formatOrDefault())
                || (source.layout() != null && !source.layout().isEmpty() && batchRows != null))) {
            return shapeSheetRow(raw, pack, source);
        }
        Map<String, Object> fields = new LinkedHashMap<>(csv.parse(raw.rawPayload()));
        Map<String, Provenance> provenance = new LinkedHashMap<>();
        List<ValidationIssue> warnings = new ArrayList<>();
        if (source == null || !source.shaped()) {
            fields.keySet().forEach(name -> provenance.put(name, passthrough()));
            return new Shaped(new TidyRecord(fields, provenance), warnings);
        }
        fields = layout(source, fields, provenance);
        parse(source, fields, provenance, warnings);
        return new Shaped(new TidyRecord(fields, provenance), warnings);
    }

    @Override
    public boolean shaped(LoadedStatePack pack, String sourceId) {
        SourceDefinition source = source(pack, sourceId);
        return source != null && source.shaped();
    }

    @Override
    public String digest(LoadedStatePack pack, String sourceId) {
        SourceDefinition source = source(pack, sourceId);
        if (source == null) {
            throw new IllegalArgumentException("State Pack has no source: " + sourceId);
        }
        try {
            byte[] bytes = JSON.writeValueAsBytes(Map.of(
                    "format", source.formatOrDefault(),
                    "layout", source.layout() == null ? List.of() : source.layout(),
                    "parse", source.parse() == null ? List.of() : source.parse()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot digest shaping steps", e);
        }
    }

    private static Provenance passthrough() {
        return new Provenance(null, null, null, null, PASSTHROUGH, "1", null);
    }

    private Map<String, Object> layout(SourceDefinition source, Map<String, Object> fields,
                                       Map<String, Provenance> provenance) {
        if (source.layout() == null || source.layout().isEmpty()) {
            fields.keySet().forEach(name -> provenance.put(name, passthrough()));
            return fields;
        }
        List<String> names = new ArrayList<>(fields.keySet());
        List<String> cells = new ArrayList<>();
        fields.values().forEach(value -> cells.add(value == null ? null : value.toString()));
        List<RawRow> rows = applyLayout(source, List.of(new RawRow(null, null, 1, cells, Map.of())));
        String[] last = lastOperation(source);
        Map<String, Object> shaped = new LinkedHashMap<>();
        if (!rows.isEmpty()) {
            RawRow row = rows.getFirst();
            for (int index = 0; index < names.size() && index < row.cells().size(); index++) {
                shaped.put(names.get(index), row.cells().get(index));
                provenance.put(names.get(index),
                        new Provenance(row.file(), row.sheet(), row.row(), null, last[0], last[1], null));
            }
            for (Map.Entry<String, String> carried : row.carried().entrySet()) {
                shaped.put(carried.getKey(), carried.getValue());
                provenance.put(carried.getKey(),
                        new Provenance(row.file(), row.sheet(), row.row(), null, "carried", last[1], null));
            }
        }
        return shaped;
    }

    private List<RawRow> applyLayout(SourceDefinition source, List<RawRow> rows) {
        List<RawRow> result = rows;
        for (Map<String, Object> step : source.layout() == null ? List.<Map<String, Object>>of() : source.layout()) {
            String op = String.valueOf(step.get("op"));
            Map<String, Object> config = new LinkedHashMap<>(step);
            config.remove("op");
            result = registry.operation(op).apply(result, config);
        }
        return result;
    }

    /** Name and version of the last layout step, or passthrough when there is none. */
    private static String[] lastOperation(SourceDefinition source) {
        if (source.layout() == null || source.layout().isEmpty()) {
            return new String[] {PASSTHROUGH, "1"};
        }
        String op = String.valueOf(source.layout().getLast().get("op"));
        return new String[] {op.substring(0, op.indexOf('@')), op.substring(op.indexOf('@') + 1)};
    }

    /**
     * An Excel row is shaped with its sheet: the layout steps run over every row of that one sheet (header-detect,
     * carry-down and skip-rows need their neighbours, and every step works per sheet) and the row's own result is looked
     * up. A row the steps consumed (the header itself, a banner) raises {@link RowDroppedException}. Only the sheet in
     * hand is read and kept (plus a few recently shaped sheets), so memory is bounded by the largest sheet, not by the
     * whole workbook (docs/perf.md). A CSV delivery with layout steps is one sheet.
     */
    private Shaped shapeSheetRow(RawRecord raw, LoadedStatePack pack, SourceDefinition source) {
        boolean workbook = "xlsx".equals(source.formatOrDefault());
        RawRow parsed = reader.read(raw.rawPayload());
        SheetShape shape = sheetShape(raw, pack, source, workbook ? parsed.file() : null,
                workbook ? parsed.sheet() : null);
        RawRow own = parsed;
        if (!workbook) {
            Integer index = shape.csvRows().get(raw.rawRecordId());
            if (index == null) {
                throw new IllegalStateException("Raw record is not present in its delivery");
            }
            own = new RawRow("csv", null, index, parsed.cells(), parsed.carried());
        }
        RawRow row = shape.rows().get(sheetKey(own));
        if (row == null) {
            throw new RowDroppedException("Row " + own.row() + " of sheet " + own.sheet()
                    + " is not a data row after the layout steps");
        }
        String[] last = lastOperation(source);
        Map<String, Object> fields = new LinkedHashMap<>();
        Map<String, Provenance> provenance = new LinkedHashMap<>();
        for (int index = 0; index < row.cells().size(); index++) {
            String name = row.carried().get(HEADER_PREFIX + index);
            if (name == null) {
                name = "col" + (index + 1);
            }
            if (name.isBlank()) {
                continue;
            }
            fields.put(name, row.cells().get(index));
            provenance.put(name, new Provenance(row.file(), row.sheet(), row.row(), null, last[0], last[1], null));
        }
        for (Map.Entry<String, String> carried : row.carried().entrySet()) {
            if (!carried.getKey().startsWith(HEADER_PREFIX)) {
                fields.put(carried.getKey(), carried.getValue());
                provenance.put(carried.getKey(),
                        new Provenance(row.file(), row.sheet(), row.row(), null, "carried", last[1], null));
            }
        }
        List<ValidationIssue> warnings = new ArrayList<>();
        parse(source, fields, provenance, warnings);
        return new Shaped(new TidyRecord(fields, provenance), warnings);
    }

    /** One shaped sheet: its rows after the layout steps by sheet and row, and a CSV delivery's row numbers. */
    private record SheetShape(Map<String, RawRow> rows, Map<java.util.UUID, Integer> csvRows) {
    }

    private SheetShape sheetShape(RawRecord raw, LoadedStatePack pack, SourceDefinition source, String file,
                                  String sheet) {
        if (batchRows == null) {
            throw new IllegalStateException("An xlsx source needs the delivery's rows (no BatchRows configured)");
        }
        String key = raw.batchId() + "|" + raw.sourceSystem() + "|" + digest(pack, source.sourceId()) + "|" + file
                + "|" + sheet;
        synchronized (sheets) {
            SheetShape cached = sheets.get(key);
            if (cached != null) {
                return cached;
            }
            boolean workbook = "xlsx".equals(source.formatOrDefault());
            List<RawRecord> records = batchRows.sheet(raw.batchId(), raw.sourceSystem(), file, sheet);
            List<RawRow> input = new ArrayList<>();
            Map<java.util.UUID, Integer> csvRows = new java.util.HashMap<>();
            for (RawRecord record : records) {
                if (!raw.sourceSystem().equals(record.sourceSystem())) {
                    continue;
                }
                RawRow parsed = reader.read(record.rawPayload());
                if (workbook) {
                    // BatchRows may hand over more than the sheet (its default reads the delivery): keep this sheet only.
                    if (java.util.Objects.equals(file, parsed.file()) && java.util.Objects.equals(sheet, parsed.sheet())) {
                        input.add(parsed);
                    }
                } else {
                    int index = csvRows.size() + 1;
                    csvRows.put(record.rawRecordId(), index);
                    input.add(new RawRow("csv", null, index, parsed.cells(), parsed.carried()));
                }
            }
            input.sort(Comparator.comparingInt(RawRow::row));
            Map<String, RawRow> shaped = new LinkedHashMap<>();
            for (RawRow row : applyLayout(source, input)) {
                shaped.putIfAbsent(sheetKey(row), row);
            }
            SheetShape shape = new SheetShape(shaped, csvRows);
            sheets.put(key, shape);
            return shape;
        }
    }

    private static String sheetKey(RawRow row) {
        return row.file() + "|" + row.sheet() + "|" + row.row();
    }

    private void parse(SourceDefinition source, Map<String, Object> fields, Map<String, Provenance> provenance,
                       List<ValidationIssue> warnings) {
        if (source.parse() == null) {
            return;
        }
        for (Map<String, Object> step : source.parse()) {
            String sourceField = String.valueOf(step.get("sourceField"));
            Object raw = fields.get(sourceField);
            if (raw == null) {
                continue;
            }
            String parserName = String.valueOf(step.get("parser"));
            FieldParser parser = registry.parser(parserName);
            ParseResult result = parser.parse(raw.toString(), step);
            double minConfidence = step.get("minConfidence") instanceof Number n ? n.doubleValue() : 0.0;
            if (result.confidence() < minConfidence) {
                warnings.add(new ValidationIssue(sourceField, LOW_CONFIDENCE_RULE, Severity.WARNING,
                        parserName + " confidence " + result.confidence() + " is below " + minConfidence
                                + "; the raw value was kept"));
                continue;
            }
            for (String warning : result.warnings() == null ? List.<String>of() : result.warnings()) {
                warnings.add(new ValidationIssue(sourceField, LOW_CONFIDENCE_RULE, Severity.WARNING, warning));
            }
            Map<?, ?> outputs = step.get("outputs") instanceof Map<?, ?> map ? map : Map.of();
            for (Map.Entry<?, ?> output : outputs.entrySet()) {
                String target = String.valueOf(output.getValue());
                fields.put(target, result.values().get(String.valueOf(output.getKey())));
                provenance.put(target, new Provenance(null, null, null, sourceField, parser.name(), parser.version(),
                        result.confidence()));
            }
        }
    }

    private static SourceDefinition source(LoadedStatePack pack, String sourceId) {
        if (pack == null || pack.sources() == null) {
            return null;
        }
        return pack.sources().stream().filter(item -> item.sourceId().equals(sourceId)).findFirst().orElse(null);
    }
}
