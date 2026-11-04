package gov.uddp.core.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.connector.JdbcConnector.Dialect;
import gov.uddp.core.connector.SourceConnector.Config;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Track C5 without Docker: Excel and REST against fixtures, and the queries generated for SQL Server and Oracle. */
class ConnectorFixtureTest {
    @TempDir
    Path work;

    private static final class MemorySecrets implements ConnectorSecrets {
        final Map<String, String> values = new HashMap<>();

        @Override
        public Optional<String> get(String name) {
            return Optional.ofNullable(values.get(name));
        }

        @Override
        public void put(String name, String value) {
            values.put(name, value);
        }
    }

    private final MemorySecrets secrets = new MemorySecrets();

    private Config config(String... keysAndValues) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return new Config(values, secrets);
    }

    @Test
    void excelReadsTheChosenSheetAndHeaderRowAndSkipsEmptyRows() throws IOException {
        Path file = work.resolve("delivery.xlsx");
        try (var book = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            book.createSheet("Notes").createRow(0).createCell(0).setCellValue("ignore me");
            var sheet = book.createSheet("Data");
            sheet.createRow(0).createCell(0).setCellValue("Report for October");
            var header = sheet.createRow(2);
            header.createCell(0).setCellValue("name");
            header.createCell(1).setCellValue("age");
            header.createCell(2).setCellValue("joined");
            var first = sheet.createRow(3);
            first.createCell(0).setCellValue("Ram Singh");
            first.createCell(1).setCellValue(44);
            var date = first.createCell(2);
            date.setCellValue(java.time.LocalDate.of(2020, 5, 17));
            var style = book.createCellStyle();
            style.setDataFormat(book.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            date.setCellStyle(style);
            sheet.createRow(4); // an empty row between records
            var second = sheet.createRow(5);
            second.createCell(0).setCellValue("Sita Singh");
            second.createCell(1).setCellValue(40);
            book.write(out);
        }
        var excel = new ExcelConnector();
        var settings = config("path", file.toString(), "sheet", "Data", "headerRow", "3");

        var table = excel.fetch(settings).table();
        assertEquals(List.of("name", "age", "joined"), table.header());
        assertEquals(2, table.rows().size());
        assertEquals("Ram Singh", table.rows().get(0).get("name"));
        assertEquals("44", table.rows().get(0).get("age"));
        assertEquals("2020-05-17", table.rows().get(0).get("joined"));
        assertEquals("", table.rows().get(1).get("joined"));
        assertEquals(1, excel.preview(settings, 1).rows().size());
        assertThrows(IllegalArgumentException.class, () -> excel.test(config("path", file.toString(), "sheet", "Nope")));
    }

    @Test
    void restPullsPagesFromAJsonArrayPathWithTheAuthHeaderFromTheSecretStore() {
        secrets.put("connector.x.api.authValue", "Bearer super-secret-token");
        List<Map<String, String>> sentHeaders = new ArrayList<>();
        List<URI> requested = new ArrayList<>();
        RestConnector.Transport transport = (uri, headers) -> {
            requested.add(uri);
            sentHeaders.add(headers);
            String query = uri.getQuery();
            if (query.contains("page=1")) {
                return "{\"data\":{\"items\":[{\"id\":1,\"name\":\"A\"},{\"id\":2,\"name\":\"B\",\"tags\":[\"x\"]}]}}";
            }
            if (query.contains("page=2")) {
                return "{\"data\":{\"items\":[{\"id\":3,\"name\":\"C\"}]}}";
            }
            throw new IOException("unexpected " + uri);
        };
        var rest = new RestConnector(new ObjectMapper(), transport);
        var settings = config("url", "https://api.example.test/people", "recordsPath", "data.items",
                "pagination", "page", "pageSize", "2", "authValueRef", "connector.x.api.authValue");

        var table = rest.fetch(settings).table();
        assertEquals(List.of("id", "name", "tags"), table.header());
        assertEquals(3, table.rows().size());
        assertEquals("[\"x\"]", table.rows().get(1).get("tags"));
        assertEquals(2, requested.size(), "paging stops at the short page");
        assertTrue(requested.getFirst().toString().contains("size=2"));
        assertEquals("Bearer super-secret-token", sentHeaders.getFirst().get("Authorization"));
        assertEquals(1, rest.preview(settings, 1).rows().size());

        RestConnector.Transport failing = (uri, headers) -> {
            throw new IOException("denied for " + headers.get("Authorization"));
        };
        var error = assertThrows(IllegalArgumentException.class,
                () -> new RestConnector(new ObjectMapper(), failing).test(settings));
        assertFalse(error.getMessage().contains("super-secret-token"), "credentials never reach an error message");
        assertThrows(IllegalArgumentException.class,
                () -> rest.test(config("url", "ftp://example.test/x")));
    }

    @Test
    void sqlServerAndOracleGenerateTheirOwnQueriesAndUrls() {
        String query = "select id, name from people where active = 1";
        assertEquals("select * from (" + query + ") as source_query", Dialect.SQLSERVER.select(null, query));
        assertEquals("select * from (" + query + ") source_query", Dialect.ORACLE.select(null, query));
        assertEquals("select * from hr.people", Dialect.ORACLE.select("hr.people", null));
        assertEquals("jdbc:sqlserver://db:1433;databaseName=dept", Dialect.SQLSERVER.url("db", 1433, "dept"));
        assertEquals("jdbc:oracle:thin:@//db:1521/DEPT", Dialect.ORACLE.url("db", 1521, "DEPT"));
        assertEquals("jdbc:mysql://db:3306/dept", Dialect.MYSQL.url("db", 3306, "dept"));
        assertThrows(IllegalArgumentException.class, () -> Dialect.ORACLE.select(null, "select 1; drop table x"));
        assertThrows(IllegalArgumentException.class, () -> Dialect.SQLSERVER.select("people; drop", null));
        assertEquals(Dialect.SQLSERVER, JdbcConnector.dialect(config("dbType", "SqlServer".toLowerCase())));
    }
}
