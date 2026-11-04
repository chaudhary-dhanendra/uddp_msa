package gov.uddp.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.TestDatabase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Studio takes an .xlsx workbook as well as a CSV: every sheet is listed with its rows, the profile reads the
 * header of each sheet, the Shape step proposes the workbook's layout steps (title row, ditto, section rows, sheet
 * name), and the user picks which sheets to load. Macro-enabled, password-protected and other files are refused.
 * Synthetic two-sheet workbook (invented districts Ngairang and Senkhai).
 */
@SpringBootTest
@AutoConfigureMockMvc
class StudioWorkbookUploadTest {
    static final Path PACKS = temporary("uddp-workbook-packs");
    static final Path WORKSPACE = temporary("uddp-workbook-workspace");

    @TestConfiguration
    static class Database {
        @Bean
        @Primary
        PostgresConnectionFactory workbookConnections() {
            return TestDatabase.freshMigrated();
        }
    }

    @DynamicPropertySource
    static void locations(DynamicPropertyRegistry registry) {
        registry.add("uddp.studio.packs-directory", PACKS::toString);
        registry.add("uddp.state-pack-directory", PACKS::toString);
        registry.add("uddp.studio.workspace-directory", WORKSPACE::toString);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    void aTwoSheetWorkbookIsProfiledShapedAndNarrowedToTheChosenSheets() throws Exception {
        String id = draft("workbook-pack", "WB");
        JsonNode uploaded = json.readTree(mvc.perform(multipart("/api/studio/drafts/" + id + "/upload")
                        .file(new MockMultipartFile("file", "Livestock.XLSX", MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                workbook())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(List.of("Ngairang", "Senkhai"), texts(uploaded.path("source").path("sheets")),
                "a new workbook loads every sheet");

        JsonNode profile = body(get("/api/studio/drafts/" + id + "/profile"));
        assertEquals(List.of("Ngairang", "Senkhai"), texts(profile.path("sheets").findValues("name")));
        assertEquals(List.of(7, 3), profile.path("sheets").findValues("rows").stream().map(JsonNode::asInt).toList());
        assertTrue(texts(profile.path("columns").findValues("name")).containsAll(List.of("Village", "Owner", "Animal",
                "Count")), "column names come from each sheet's header row, below the title: " + profile);

        JsonNode shaping = body(get("/api/studio/drafts/" + id + "/shaping"));
        List<String> kinds = texts(shaping.path("proposals").findValues("kind"));
        assertTrue(kinds.containsAll(List.of("header-detect", "ditto", "section-rows", "sheet-name")), kinds.toString());
        assertTrue(texts(shaping.path("proposals").findValues("sheet")).contains("Ngairang"), "proposals name their sheet");

        String onlySenkhai = json.writeValueAsString(Map.of("layout", List.of(), "parse", List.of(),
                "sheets", List.of("Senkhai")));
        mvc.perform(put("/api/studio/drafts/" + id + "/shaping").contentType(MediaType.APPLICATION_JSON)
                .content(onlySenkhai)).andExpect(status().isOk());
        JsonNode narrowed = body(get("/api/studio/drafts/" + id + "/profile"));
        assertEquals(2, narrowed.path("rowCount").asInt(), "two data rows under Senkhai's header");
        assertFalse(narrowed.path("sheets").get(0).path("selected").asBoolean());
        assertTrue(narrowed.path("sheets").get(1).path("selected").asBoolean());
        JsonNode saved = body(get("/api/studio/drafts/" + id));
        assertEquals("header-detect@1.0", saved.path("source").path("layout").get(0).path("op").asText(),
                "a workbook's saved layout always reads the header row");

        JsonNode preview = json.readTree(mvc.perform(post("/api/studio/drafts/" + id + "/shaping/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(onlySenkhai))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(texts(preview.path("afterSheets")).stream().allMatch("Senkhai"::equals), preview.toString());
    }

    @Test
    void aWorkbookPreviewMapsTheShapedRowsAndGivesReasonCodesFixTargetsAndSuggestions() throws Exception {
        String id = draft("preview-pack", "PV");
        byte[] book;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            rows(workbook.createSheet("Senkhai"), List.of(
                    List.of("Register of owners"),
                    List.of("OwnerId", "Owner", "Sex"),
                    List.of("O1", "Sanatombi Chanu", "F"),
                    List.of("O2", "Nganba Luwang", "Mle")));
            workbook.write(out);
            book = out.toByteArray();
        }
        mvc.perform(multipart("/api/studio/drafts/" + id + "/upload")
                .file(new MockMultipartFile("file", "owners.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, book)))
                .andExpect(status().isOk());
        JsonNode current = body(get("/api/studio/drafts/" + id));
        var configuration = json.createObjectNode();
        configuration.putArray("mappings")
                .add(json.createObjectNode().put("sourceField", "Owner").put("entity", "Resident").put("target", "name"))
                .add(json.createObjectNode().put("sourceField", "Sex").put("entity", "Resident").put("target", "gender")
                        .put("crosswalk", "gender"));
        var crosswalk = json.createObjectNode().put("id", "gender").put("codeSet", "gender");
        crosswalk.putArray("entries")
                .add(json.createObjectNode().put("sourceValue", "M").put("targetValue", "MALE"))
                .add(json.createObjectNode().put("sourceValue", "F").put("targetValue", "FEMALE"));
        configuration.putArray("crosswalks").add(crosswalk);
        configuration.set("rules", current.path("rules"));
        configuration.put("recordIdField", "OwnerId");
        mvc.perform(put("/api/studio/drafts/" + id + "/configuration").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(configuration))).andExpect(status().isOk());

        JsonNode preview = body(get("/api/studio/drafts/" + id + "/preview"));
        assertTrue(preview.path("packError").isNull(), preview.toString());
        assertEquals(2, preview.path("rowsPreviewed").asInt(), "the title and header rows are not data");
        assertEquals("Sanatombi Chanu", preview.path("rows").get(0).path("resident").path("name").asText());
        JsonNode unmapped = preview.path("rows").get(1).path("issues").get(0);
        assertEquals("UNMAPPED_CODE", unmapped.path("code").asText());
        assertEquals("CROSSWALK", unmapped.path("fix").path("kind").asText());
        assertEquals("gender", unmapped.path("fix").path("key").asText());
        assertTrue(unmapped.path("suggestion").asText().contains("M"), unmapped.toString());
        assertFalse(unmapped.path("suggestion").asText().contains("Nganba"), "suggestions name no row values");
        assertEquals(1, preview.path("countsByCode").path("UNMAPPED_CODE").asInt());
    }

    @Test
    void aCrosswalkSentBlankForAHandPickedTargetGetsSuggestions() throws Exception {
        String id = draft("blank-crosswalk-pack", "BX");
        byte[] book;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            rows(workbook.createSheet("Owners"), List.of(
                    List.of("OwnerId", "Owner", "Sex", "Sex code"),
                    List.of("O1", "Asha Test", "Female", "FEMALE"),
                    List.of("O2", "Ravi Test", "Male", "MALE")));
            workbook.write(out);
            book = out.toByteArray();
        }
        mvc.perform(multipart("/api/studio/drafts/" + id + "/upload")
                .file(new MockMultipartFile("file", "owners.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, book)))
                .andExpect(status().isOk());
        JsonNode current = body(get("/api/studio/drafts/" + id));
        var configuration = json.createObjectNode();
        configuration.putArray("mappings")
                .add(json.createObjectNode().put("sourceField", "Owner").put("entity", "Resident").put("target", "name"))
                .add(json.createObjectNode().put("sourceField", "Sex code").put("entity", "Resident")
                        .put("target", "gender").put("crosswalk", "gender"));
        // What the Map step sends for a target picked by hand: one blank row per value, nothing chosen.
        var crosswalk = json.createObjectNode().put("id", "gender").put("codeSet", "gender");
        crosswalk.putArray("entries")
                .add(json.createObjectNode().put("sourceValue", "FEMALE").putNull("targetValue"))
                .add(json.createObjectNode().put("sourceValue", "MALE").putNull("targetValue"));
        configuration.putArray("crosswalks").add(crosswalk);
        configuration.set("rules", current.path("rules"));
        configuration.put("recordIdField", "OwnerId");
        JsonNode saved = json.readTree(mvc.perform(put("/api/studio/drafts/" + id + "/configuration")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(configuration)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode entries = saved.path("crosswalks").get(0).path("entries");
        assertEquals(2, entries.size(), saved.toString());
        for (JsonNode entry : entries) {
            assertEquals(entry.path("sourceValue").asText(), entry.path("targetValue").asText(), entry.toString());
            assertEquals("already a platform code", entry.path("basis").asText());
        }
    }

    @Test
    void otherFilesAreRefusedWithTheReason() throws Exception {
        String id = draft("refused-pack", "RF");
        assertEquals("Only .csv and .xlsx files can be uploaded",
                refused(id, "people.txt", "pid\nP1\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(refused(id, "macros.xlsm", workbook()).contains("Macro-enabled"));
        byte[] encrypted = new byte[512];
        byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(ole2, 0, encrypted, 0, ole2.length);
        assertTrue(refused(id, "locked.xlsx", encrypted).contains("password-protected"));
        assertEquals("The file is not a valid .xlsx workbook",
                refused(id, "fake.xlsx", "pid\nP1\n".getBytes(StandardCharsets.UTF_8)));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Ngairang: a title row, a section row per block and a ditto Village; Senkhai: a plain header and two rows. */
    private static byte[] workbook() throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet first = book.createSheet("Ngairang");
            rows(first, List.of(
                    List.of("Livestock census 2026, Ngairang district"),
                    List.of("Village", "Owner", "Animal", "Count"),
                    List.of("Block North"),
                    List.of("Lamkhai", "Thoiba Meetei", "Cow", "3"),
                    List.of("-do-", "Ibemhal Devi", "Goat", "5"),
                    List.of("Block South"),
                    List.of("Wangkhem", "Tomba Singh", "Pig", "2")));
            Sheet second = book.createSheet("Senkhai");
            rows(second, List.of(
                    List.of("Village", "Owner", "Animal", "Count"),
                    List.of("Phunal", "Sanatombi Chanu", "Cow", "1"),
                    List.of("Phunal", "Nganba Luwang", "Duck", "12")));
            book.write(out);
            return out.toByteArray();
        }
    }

    private static void rows(Sheet sheet, List<List<String>> values) {
        for (int r = 0; r < values.size(); r++) {
            Row row = sheet.createRow(r);
            for (int c = 0; c < values.get(r).size(); c++) {
                row.createCell(c).setCellValue(values.get(r).get(c));
            }
        }
    }

    private String draft(String packId, String stateCode) throws Exception {
        return json.readTree(mvc.perform(post("/api/studio/drafts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packId\":\"" + packId + "\",\"stateCode\":\"" + stateCode
                                + "\",\"displayName\":\"Workbook\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("draftId").asText();
    }

    private String refused(String id, String name, byte[] content) throws Exception {
        return json.readTree(mvc.perform(multipart("/api/studio/drafts/" + id + "/upload")
                        .file(new MockMultipartFile("file", name, MediaType.APPLICATION_OCTET_STREAM_VALUE, content)))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString())
                .path("message").asText();
    }

    private JsonNode body(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    private static List<String> texts(List<JsonNode> nodes) {
        return nodes.stream().map(JsonNode::asText).toList();
    }

    private static Path temporary(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
