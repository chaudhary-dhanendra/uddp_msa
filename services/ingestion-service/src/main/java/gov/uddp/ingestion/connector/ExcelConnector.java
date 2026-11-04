package gov.uddp.ingestion.connector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Excel (.xlsx) files. Settings: {@code path}, {@code sheet} (name; first sheet when blank) and {@code headerRow}
 * (1-based, default 1). Cell values are read as the sheet displays them, so dates and numbers keep their format.
 */
@Component
public class ExcelConnector implements SourceConnector {
    @Override
    public String type() {
        return "excel";
    }

    @Override
    public void test(Config config) {
        preview(config, 1);
    }

    @Override
    public Table preview(Config config, int limit) {
        return read(config, limit);
    }

    @Override
    public Delivery fetch(Config config) {
        return Delivery.of(read(config, Integer.MAX_VALUE));
    }

    Table read(Config config, int limit) {
        Path file = Path.of(config.required("path"));
        if (!Files.isReadable(file)) {
            throw new IllegalArgumentException("The Excel file is not readable: " + file.getFileName());
        }
        int headerRow = config.integer("headerRow", 1);
        if (headerRow < 1) {
            throw new IllegalArgumentException("headerRow must be 1 or more");
        }
        try (OPCPackage pack = OPCPackage.open(file.toFile(), PackageAccess.READ);
                XSSFWorkbook workbook = new XSSFWorkbook(pack)) {
            String name = config.optional("sheet", null);
            Sheet sheet = name == null ? workbook.getSheetAt(0) : workbook.getSheet(name);
            if (sheet == null) {
                throw new IllegalArgumentException("The workbook has no sheet named '" + name + "'");
            }
            var formatter = new DataFormatter();
            var evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Row headerCells = sheet.getRow(headerRow - 1);
            if (headerCells == null) {
                throw new IllegalArgumentException("Header row " + headerRow + " is empty");
            }
            List<String> header = header(headerCells, formatter, evaluator);
            List<Map<String, String>> rows = new ArrayList<>();
            for (int index = headerRow; index <= sheet.getLastRowNum() && rows.size() < limit; index++) {
                Row row = sheet.getRow(index);
                if (row == null) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                boolean any = false;
                for (int column = 0; column < header.size(); column++) {
                    Cell cell = row.getCell(column);
                    String value = cell == null ? "" : formatter.formatCellValue(cell, evaluator).strip();
                    any |= !value.isEmpty();
                    values.put(header.get(column), value);
                }
                if (any) {
                    rows.add(values);
                }
            }
            return new Table(header, rows);
        } catch (IOException | org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
            throw new IllegalArgumentException("Cannot read the Excel file: " + e.getMessage(), e);
        }
    }

    private static List<String> header(Row cells, DataFormatter formatter,
                                       org.apache.poi.ss.usermodel.FormulaEvaluator evaluator) {
        int last = cells.getLastCellNum();
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int column = 0; column < last; column++) {
            Cell cell = cells.getCell(column);
            String name = cell == null ? "" : formatter.formatCellValue(cell, evaluator).strip();
            if (name.isEmpty()) {
                name = "column_" + (column + 1);
            }
            String unique = name;
            for (int n = 2; !seen.add(unique); n++) {
                unique = name + "_" + n;
            }
            names.add(unique);
        }
        return names;
    }
}
