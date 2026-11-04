package gov.uddp.shaping.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.shaping.api.RawRow;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Test helper: one worksheet per messy-layout pattern (all names synthetic), generated with Apache POI into
 * {@code shaping/layout-patterns.xlsx}, plus loading of the sheets as {@link RawRow}s and of the expected outputs
 * ({@code shaping/layout-patterns.<name>.expected.json}) next to it.
 */
public final class LayoutPatterns {

    public static final String WORKBOOK = "layout-patterns.xlsx";
    private static final String FOLDER = "/shaping/";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Sheet name to rows; "" is a blank cell. */
    public static final Map<String, String[][]> SHEETS = new LinkedHashMap<>();

    static {
        SHEETS.put("header", new String[][] {
                {"Beneficiary Master Report", "", "", ""},
                {"", "", "", ""},
                {"Name\n(Required)", "Gender (Dropdown)", "  Date of Birth ", "Amount"},
                {"Asha Test", "F", "1-Apr-80", "100"},
                {"Ravi Sample", "M", "2-May-81", "200"}});
        SHEETS.put("tranches", new String[][] {
                {"", "", "Tranche 1", "", "Tranche 2", ""},
                {"Name", "Id", "Amount", "Date", "Amount", "Date"},
                {"Asha Test", "A1", "1000", "1-Apr-26", "2000", "1-Jul-26"},
                {"Ravi Sample", "A2", "3000", "1-May-26", "", ""}});
        SHEETS.put("aliases", new String[][] {
                {"Beneficiary  Name", "DOB", "Mobile No", "Village"},
                {"Asha Test", "1-Apr-80", "9000000001", "Demo Nagar"}});
        SHEETS.put("skip", new String[][] {
                {"District Report", "", ""},
                {"Name", "Amount", "Remarks"},
                {"Asha Test", "10", "ok"},
                {"", "", ""},
                {"Total", "30", ""},
                {"Ravi Sample", "20", "ok"},
                {"Page 1 of 2", "", ""}});
        SHEETS.put("section", new String[][] {
                {"Name", "Amount"},
                {"District A", ""},
                {"Asha Test", "10"},
                {"Ravi Sample", "20"},
                {"District B", ""},
                {"Meena Demo", "30"}});
        SHEETS.put("carry", new String[][] {
                {"Zone", "Name", "Rank"},
                {"North", "Asha Test", "1"},
                {"", "Ravi Sample", "2"},
                {"South", "Meena Demo", "3"},
                {"", "Dev Sample", "4"}});
        SHEETS.put("carry2", new String[][] {
                {"", "Zed Test", "9"},
                {"East", "Kiran Demo", "8"}});
        SHEETS.put("ditto", new String[][] {
                {"Name", "Department"},
                {"Asha Test", "Dept A"},
                {"Ravi Sample", "-do-"},
                {"Meena Demo", "\""},
                {"Dev Sample", " DO "},
                {"Kiran Test", "Dept B"},
                {"Lata Demo", "_do-"}});
        SHEETS.put("numbers", new String[][] {
                {"Name", "Indian", "Western"},
                {"Asha Test", "25,23,809", "2,523,809"},
                {"Ravi Sample", "1,00,000.50", "12,345"},
                {"Meena Demo", "750", "7,50"},
                {"Dev Sample", "", "-1,234,567"}});
    }

    private LayoutPatterns() {
    }

    public static void write(Path target) {
        try (Workbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(target)) {
            SHEETS.forEach((name, rows) -> {
                Sheet sheet = workbook.createSheet(name);
                for (int r = 0; r < rows.length; r++) {
                    Row row = sheet.createRow(r);
                    for (int c = 0; c < rows[r].length; c++) {
                        if (!rows[r][c].isEmpty()) {
                            row.createCell(c).setCellValue(rows[r][c]);
                        }
                    }
                }
            });
            workbook.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads the named sheets of the committed workbook as raw rows (1-based row numbers, blanks as ""). */
    public static List<RawRow> read(String... sheetNames) {
        List<RawRow> rows = new ArrayList<>();
        try (InputStream in = LayoutPatterns.class.getResourceAsStream(FOLDER + WORKBOOK);
                Workbook workbook = new XSSFWorkbook(in)) {
            DataFormatter formatter = new DataFormatter();
            for (String name : sheetNames) {
                Sheet sheet = workbook.getSheet(name);
                int width = 0;
                for (Row row : sheet) {
                    width = Math.max(width, row.getLastCellNum());
                }
                for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    List<String> cells = new ArrayList<>();
                    for (int c = 0; c < width; c++) {
                        Cell cell = row == null ? null : row.getCell(c);
                        cells.add(cell == null ? "" : formatter.formatCellValue(cell));
                    }
                    rows.add(new RawRow(WORKBOOK, name, r + 1, cells, Map.of()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    /** Operation config written as JSON, the way a pack's YAML step would read. */
    public static Map<String, Object> config(String json) {
        try {
            return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode json(List<RawRow> rows) {
        ArrayNode array = JSON.createArrayNode();
        for (RawRow row : rows) {
            ObjectNode node = array.addObject();
            node.put("sheet", row.sheet());
            node.put("row", row.row());
            ArrayNode cells = node.putArray("cells");
            row.cells().forEach(cells::add);
            ObjectNode carried = node.putObject("carried");
            row.carried().forEach(carried::put);
        }
        return array;
    }

    public static void assertMatches(String expectedName, List<RawRow> actual) {
        try (InputStream in = LayoutPatterns.class.getResourceAsStream(
                FOLDER + "layout-patterns." + expectedName + ".expected.json")) {
            assertEquals(JSON.readTree(in), json(actual), "output of " + expectedName);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
