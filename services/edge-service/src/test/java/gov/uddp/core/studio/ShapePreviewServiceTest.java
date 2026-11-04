package gov.uddp.core.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.uddp.core.studio.StudioModels.ShapePreview;
import gov.uddp.core.studio.StudioModels.ShapingConfig;
import gov.uddp.core.studio.StudioModels.Source;
import gov.uddp.shaping.OperationRegistry;
import gov.uddp.shaping.ops.ColumnAliasesOperation;
import gov.uddp.shaping.ops.DittoOperation;
import gov.uddp.shaping.ops.HeaderDetectOperation;
import gov.uddp.shaping.ops.SheetNameOperation;
import gov.uddp.shaping.parsers.NameNormaliseParser;
import gov.uddp.shaping.parsers.NameRelationParsing;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Shape step's preview: the real layout operations and parsers over the first rows, nothing saved. */
class ShapePreviewServiceTest {
    @TempDir
    Path tmp;

    private final OperationRegistry registry = new OperationRegistry(
            List.of(new HeaderDetectOperation(), new DittoOperation(), new ColumnAliasesOperation(), new SheetNameOperation()),
            List.of(new NameNormaliseParser(new NameRelationParsing(Path.of("../../platform")))), "none", "none");
    private final ShapePreviewService service = new ShapePreviewService(registry, new CsvProfiler());

    @Test
    void bannerHeaderDittoAndAliasAreShownBeforeAndAfter() throws Exception {
        Source source = workbookSource();
        ShapingConfig config = new ShapingConfig(
                List.of(Map.of("op", "header-detect@1.0"),
                        Map.of("op", "column-aliases@1.0", "aliases", Map.of("FARMER_NAME", List.of("NAME"))),
                        Map.of("op", "ditto@1.0", "columns", List.of("VILLAGE"))),
                List.of(Map.of("sourceField", "FARMER_NAME", "parser", "name-normalise@1.0",
                        "outputs", Map.of("nameNormalised", "person_name_norm"))),
                null);

        ShapePreview preview = service.preview(source, config);

        assertThat(preview.beforeRows().getFirst().getFirst()).isEqualTo("Register of farmers");
        assertThat(preview.beforeColumns()).hasSize(3);
        assertThat(preview.afterColumns()).containsExactly("ID", "FARMER_NAME", "VILLAGE", "person_name_norm");
        assertThat(preview.afterRows()).hasSize(2);
        assertThat(preview.afterRows().get(1)).containsExactly("2", "Haji Sita", "Sakti", "SITA");
        assertThat(preview.yaml()).contains("layout:", "parse:", "aliases: { FARMER_NAME: [NAME] }");
    }

    @Test
    void withoutStepsAWorkbookIsStillReadWithItsHeaderRow() throws Exception {
        // A workbook's layout always starts with header-detect (WP3): without it every column would be col1, col2, ...
        ShapePreview preview = service.preview(workbookSource(), new ShapingConfig(List.of(), List.of(), null));
        assertThat(preview.yaml()).contains("header-detect@1.0");
        assertThat(preview.beforeRows()).hasSize(4);
        assertThat(preview.beforeRows().getFirst().getFirst()).isEqualTo("Register of farmers");
        assertThat(preview.afterColumns()).contains("ID", "VILLAGE");
        assertThat(preview.afterRows()).hasSize(2);
        assertThat(preview.beforeSheets()).hasSize(4);
    }

    @Test
    void workbookLayoutMovesAnExistingHeaderDetectBeforeNamedCleanup() {
        List<Map<String, Object>> layout = ShapePreviewService.workbookLayout(List.of(
                Map.of("op", "ditto@1.0", "columns", List.of("VILLAGE")),
                Map.of("op", "header-detect@1.0"),
                Map.of("op", "numbers@1.0", "columns", List.of("SHEEP"))));

        assertThat(layout).extracting(step -> step.get("op"))
                .containsExactly("header-detect@1.0", "ditto@1.0", "numbers@1.0");
    }

    @Test
    void anUnknownOperationOrParserIsRefused() throws Exception {
        Source source = workbookSource();
        assertThatThrownBy(() -> service.preview(source,
                new ShapingConfig(List.of(Map.of("op", "nope@1.0")), List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope@1.0");
        assertThatThrownBy(() -> service.preview(source,
                new ShapingConfig(List.of(), List.of(Map.of("sourceField", "A", "parser", "nope@1.0")), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nope@1.0");
        assertThatThrownBy(() -> service.validate(new ShapingConfig(List.of(Map.of("columns", List.of("A"))), List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("op");
    }

    @Test
    void aCsvFileIsReadAsHeaderAndRows() throws Exception {
        Path csv = tmp.resolve("people.csv");
        Files.writeString(csv, "pid,name,village\n1,Asha,Sakti\n2,Ravi,-do-\n");
        Source source = new Source("people", "file", "people.csv", csv.toString(), 10L, "pid", List.of(), null, null, null);

        ShapePreview preview = service.preview(source, new ShapingConfig(
                List.of(Map.of("op", "header-detect@1.0"), Map.of("op", "ditto@1.0", "columns", List.of("village"))),
                List.of(), null));

        assertThat(preview.afterColumns()).containsExactly("pid", "name", "village");
        assertThat(preview.afterRows()).containsExactly(List.of("1", "Asha", "Sakti"), List.of("2", "Ravi", "Sakti"));
    }

    @Test
    void heterogeneousWorkbookCleansIndependentDittoColumnsBeforeAliases() throws Exception {
        ShapePreview preview = service.preview(animalHusbandrySource(), new ShapingConfig(List.of(
                Map.of("op", "header-detect@1.0"),
                Map.of("op", "ditto@1.0", "columns", List.of("VILLAGE"), "tokens", List.of("\"")),
                Map.of("op", "ditto@1.0", "columns", List.of("Village"), "tokens", List.of("-do-")),
                Map.of("op", "column-aliases@1.0", "aliases", Map.of(
                        "DOB", List.of("Date of Birth"), "VILLAGE", List.of("Village"),
                        "VILLAGE_LGD", List.of("Village LGD"), "MOBILE", List.of("Mobile No"),
                        "SHEEP", List.of("Sheep"), "GOATS", List.of("Goats"), "YAKS", List.of("Yaks"))),
                Map.of("op", "column-aliases@1.0", "aliases", Map.of(
                        "OWNER_NAME", List.of("Name of Owner"), "FATHER_NAME", List.of("Father/Husband"),
                        "GENDER", List.of("Sex"))),
                Map.of("op", "sheet-name@1.0", "as", "sheet")), List.of(), null));

        assertThat(preview.afterRows()).hasSize(10);
        assertThat(preview.afterColumns()).containsExactly(
                "OWNER_NAME", "FATHER_NAME", "GENDER", "DOB", "VILLAGE", "VILLAGE_LGD", "MOBILE",
                "SHEEP", "GOATS", "YAKS", "sheet");
        int owner = preview.afterColumns().indexOf("OWNER_NAME");
        int village = preview.afterColumns().indexOf("VILLAGE");
        int sheet = preview.afterColumns().indexOf("sheet");
        assertThat(preview.afterRows()).anySatisfy(row -> {
            assertThat(row.get(owner)).isEqualTo("Fatima Bano");
            assertThat(row.get(village)).isEqualTo("Akchamal");
            assertThat(row.get(sheet)).isEqualTo("Kargil");
        });
        assertThat(preview.afterRows()).anySatisfy(row -> {
            assertThat(row.get(owner)).isEqualTo("Padma Dolma");
            assertThat(row.get(village)).isEqualTo("Choglamsar (Ct)");
            assertThat(row.get(sheet)).isEqualTo("Leh");
        });
    }

    @Test
    void shapingStepsAreWrittenIntoThePackSourceYaml() {
        Source source = new Source("farmers", "file", "farmers.xlsx", "sample-data/farmers.xlsx", 1L, "ID", List.of(), null,
                null, null).withShaping(List.of(Map.of("op", "header-detect@1.0")),
                List.of(ordered("sourceField", "NAME", "parser", "name-normalise@1.0")), List.of("Farmers"));
        assertThat(PackGenerator.shapingLines(source.layout(), source.parse())).containsExactly(
                "layout:", "  - { op: 'header-detect@1.0' }",
                "parse:", "  - { sourceField: NAME, parser: 'name-normalise@1.0' }");
    }

    private static Map<String, Object> ordered(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }

    private Source workbookSource() throws Exception {
        Path file = tmp.resolve("farmers.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            var sheet = workbook.createSheet("Farmers");
            String[][] rows = {{"Register of farmers", "", ""}, {"ID", "NAME", "VILLAGE"},
                    {"1", "Ram", "Sakti"}, {"2", "Haji Sita", "-do-"}};
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < 3; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            workbook.write(out);
        }
        return new Source("farmers", "file", "farmers.xlsx", file.toString(), Files.size(file), "ID", List.of(), null,
                null, null);
    }

    private Source animalHusbandrySource() throws Exception {
        Path file = tmp.resolve("animal-husbandry.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            writeSheet(workbook, "Leh", new String[] {"OWNER_NAME", "FATHER_NAME", "GENDER", "DOB", "VILLAGE", "VILLAGE_LGD", "MOBILE", "SHEEP", "GOATS", "YAKS"},
                    new String[][] {{"Tsering Angchuk", "Sonam Dorjay", "M", "12-03-1958", "Choglamsar (Ct)", "910", "9419000001", "12", "30", "2"},
                            {"Padma Dolma", "Tsering Angchuk", "F", "05-07-1962", "\"", "910", "9419000002", "0", "8", "0"},
                            {"Stanzin Norbu", "Tashi Phuntsog", "M", "21-11-1979", "Chemrey", "871", "9419000003", "40", "25", "6"},
                            {"Rigzin Lhamo", "Dorje Namgyal", "F", "30-06-1955", "Hunder", "928", "9419000004", "5", "0", "0"},
                            {"Kunzes Angmo", "Rigzin Namgyal", "F", "10-10-1990", "Bodgang", "915", "9419000011", "7", "3", "0"},
                            {"Jigmet Namgyal", "Tsewang Rigzin", "M", "07-07-1945", "Hamis", "892", "9419000012", "18", "4", "1"}});
            writeSheet(workbook, "Kargil", new String[] {"Name of Owner", "Father/Husband", "Sex", "Date of Birth", "Village", "Village LGD", "Mobile No", "Sheep", "Goats", "Yaks"},
                    new String[][] {{"Mohd Ali", "Gh Nabi", "M", "14-08-1950", "Akchamal", "969", "9419000005", "20", "15", "0"},
                            {"Fatima Bano", "Mohd Ali", "F", "09-09-1965", "-do-", "969", "9419000006", "0", "6", "0"},
                            {"Sonam Wangchuk", "Phuntsog Namgyal", "M", "02-01-1968", "Abran", "1065", "9419000007", "35", "10", "9"},
                            {"Tsering Dolma", "Sonam Dorje", "F", "15-05-1987", "Aksho", "1064", "9419000010", "3", "2", "0"}});
            workbook.write(out);
        }
        return new Source("animals", "file", file.getFileName().toString(), file.toString(), Files.size(file), "OWNER_NAME", List.of(), null, null, null);
    }

    private static void writeSheet(XSSFWorkbook workbook, String name, String[] header, String[][] data) {
        var sheet = workbook.createSheet(name);
        for (int r = 0; r <= data.length; r++) {
            Row row = sheet.createRow(r);
            String[] values = r == 0 ? header : data[r - 1];
            for (int c = 0; c < values.length; c++) row.createCell(c).setCellValue(values[c]);
        }
    }
}
