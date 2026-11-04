package gov.uddp.core.studio;

import gov.uddp.shaping.api.RawRow;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Reads every sheet of an uploaded workbook as raw rows (1-based row numbers, blank cells as ""), for layout profiling. */
final class RawSheets {
    private RawSheets() {
    }

    static boolean isWorkbook(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx");
    }

    /** Each sheet in workbook order with its non-blank rows; {@code selected} names the sheets loaded (null: all). */
    static List<StudioModels.SheetSummary> summary(Path file, List<String> selected) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (RawRow row : read(file)) {
            counts.merge(row.sheet(), row.cells().stream().allMatch(String::isBlank) ? 0 : 1, Integer::sum);
        }
        List<StudioModels.SheetSummary> out = new ArrayList<>();
        counts.forEach((name, rows) -> out.add(new StudioModels.SheetSummary(name, rows,
                selected == null || selected.isEmpty() || selected.contains(name))));
        return out;
    }

    /** An .xlsx file as its sheets' rows; any other file yields no rows (a CSV has no layout to profile). */
    static List<RawRow> read(Path file) {
        if (!isWorkbook(file)) {
            return List.of();
        }
        List<RawRow> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (InputStream in = Files.newInputStream(file); Workbook workbook = new XSSFWorkbook(in)) {
            for (Sheet sheet : workbook) {
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
                    rows.add(new RawRow(file.getFileName().toString(), sheet.getSheetName(), r + 1, cells, Map.of()));
                }
            }
        } catch (org.apache.poi.EncryptedDocumentException e) {
            throw new IllegalArgumentException("This workbook is password-protected. Remove the password and save it "
                    + "as .xlsx, or export the sheets as CSV", e);
        } catch (org.apache.poi.UnsupportedFileFormatException e) {
            throw new IllegalArgumentException("The file is not a valid .xlsx workbook", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }
}
