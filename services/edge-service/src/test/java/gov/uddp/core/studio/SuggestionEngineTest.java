package gov.uddp.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.scheme.SchemeCatalogue;
import gov.uddp.core.studio.StudioModels.CodeValue;
import gov.uddp.core.studio.StudioModels.ColumnProfile;
import gov.uddp.core.studio.StudioModels.Crosswalk;
import gov.uddp.core.studio.StudioModels.CrosswalkEntry;
import gov.uddp.core.studio.StudioModels.LayoutProposal;
import gov.uddp.core.studio.StudioModels.ValueCount;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.shaping.api.RawRow;
import gov.uddp.shaping.ops.LayoutPatterns;
import gov.uddp.shaping.parsers.NameRelationParsing;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Layout proposals of the profiler, on the layout-patterns workbook sheets and a few synthetic ones. */
class SuggestionEngineTest {
    private final SuggestionEngine engine = new SuggestionEngine(null, 0.7,
            new NameRelationParsing(Path.of("../../platform")));

    private static List<RawRow> sheet(String name, String[]... rows) {
        List<RawRow> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            out.add(new RawRow("f.xlsx", name, i + 1, List.of(rows[i]), Map.of()));
        }
        return out;
    }

    private Optional<LayoutProposal> find(List<RawRow> rows, String kind, String text) {
        return engine.layoutProposals(rows).stream()
                .filter(p -> p.kind().equals(kind) && p.description().contains(text)).findFirst();
    }

    private LayoutProposal expect(List<RawRow> rows, String kind, String text) {
        return find(rows, kind, text).orElseThrow(() -> new AssertionError(kind + " / " + text + " not proposed in "
                + engine.layoutProposals(rows)));
    }

    @Test
    void titleRowsAndHeaderIndex() {
        LayoutProposal p = expect(LayoutPatterns.read("header"), "header-detect", "Title rows");
        assertEquals(2, p.evidenceCount());
        assertTrue(p.description().contains("row 3"), p.description());
        assertEquals("layout", p.section());
        assertTrue(!p.accepted());
    }

    @Test
    void multiRowHeader() {
        LayoutProposal p = expect(LayoutPatterns.read("tranches"), "header-detect", "Multi-row header");
        assertEquals(2, p.evidenceCount());
        assertTrue(p.yaml().contains("multiRow: true"));
    }

    @Test
    void dataDictionaryRow() {
        List<RawRow> rows = sheet("s", new String[] {"Name", "Gender", "Age"},
                new String[] {"Value: text", "Value: M/F", "Value: number"},
                new String[] {"Asha Test", "F", "30"});
        assertEquals(3, expect(rows, "skip-rows", "Data-dictionary").evidenceCount());
    }

    @Test
    void totalsRow() {
        LayoutProposal p = expect(LayoutPatterns.read("skip"), "skip-rows", "Totals row");
        assertEquals(1, p.evidenceCount());
        List<RawRow> formula = sheet("s", new String[] {"Name", "Amount"}, new String[] {"A", "1"},
                new String[] {"", "=SUM(B2:B2)"});
        assertEquals(1, expect(formula, "skip-rows", "Totals row").evidenceCount());
    }

    @Test
    void emptyTemplateRows() {
        List<RawRow> rows = sheet("s", new String[] {"Sl No", "Name", "Amount"},
                new String[] {"1", "Asha Test", "10"}, new String[] {"2", "Ravi Sample", "20"},
                new String[] {"3", "", ""}, new String[] {"4", "", ""});
        LayoutProposal p = expect(rows, "skip-rows", "Empty template rows");
        assertEquals(2, p.evidenceCount());
        assertTrue(p.yaml().contains("onlyFilled: [\"Sl No\"]"));
    }

    @Test
    void sectionRows() {
        LayoutProposal p = expect(LayoutPatterns.read("section"), "section-rows", "Section rows");
        assertEquals(2, p.evidenceCount());
    }

    @Test
    void dittoTokensPerColumn() {
        LayoutProposal p = expect(LayoutPatterns.read("ditto"), "ditto", "Department");
        assertEquals(4, p.evidenceCount());
        assertTrue(p.yaml().contains("\"-do-\"") && p.yaml().contains("\"do\""), p.yaml());
    }

    @Test
    void blankMeansAboveColumns() {
        LayoutProposal p = expect(LayoutPatterns.read("carry"), "carry-down", "Zone");
        assertEquals(2, p.evidenceCount());
    }

    @Test
    void sheetHeaderVariantsBecomeColumnAliases() {
        List<RawRow> rows = new ArrayList<>(sheet("Jan", new String[] {"Name", "Date of Birth", "Mobile Number"},
                new String[] {"Asha Test", "1-Apr-80", "9000000001"}));
        rows.addAll(sheet("Feb", new String[] {"Name", "DOB", "Mobile No"},
                new String[] {"Ravi Sample", "2-May-81", "9000000002"}));
        LayoutProposal p = expect(rows, "column-aliases", "DOB");
        assertEquals(2, p.evidenceCount());
        assertTrue(p.yaml().contains("\"Date of Birth\": [\"DOB\"]")
                        && p.yaml().contains("\"Mobile Number\": [\"Mobile No\"]"),
                p.yaml());
    }

    @Test
    void compositeNameRelationCells() {
        List<RawRow> rows = sheet("s", new String[] {"Beneficiary", "Amount"},
                new String[] {"Asha Test S/O Ram Test", "10"}, new String[] {"Meena Demo W/O Dev Demo", "20"},
                new String[] {"Ravi Sample", "30"});
        LayoutProposal p = expect(rows, "name-relation", "Beneficiary");
        assertEquals(2, p.evidenceCount());
        assertEquals(3, p.evidenceTotal());
        assertEquals("parse", p.section());
        assertTrue(p.yaml().contains("name: person_name"), p.yaml());
    }

    @Test
    void maskedIds() {
        List<RawRow> rows = sheet("s", new String[] {"Name", "ID"}, new String[] {"A Test", "XXXXXXXX4821"},
                new String[] {"B Test", "********7955"}, new String[] {"C Test", "4821"});
        assertEquals(2, expect(rows, "masked-id", "\"ID\"").evidenceCount());
    }

    @Test
    void twelveDigitIdsWithChecksumFailuresAreFlagged() {
        List<RawRow> rows = sheet("s", new String[] {"Name", "UID"}, new String[] {"A Test", "234567890124"},
                new String[] {"B Test", "234567890125"}, new String[] {"C Test", "345678901234"});
        LayoutProposal p = expect(rows, "onInvalid", "\"UID\"");
        assertEquals("mapping", p.section());
        assertTrue(p.evidenceCount() >= 1 && p.evidenceTotal() == 3, p.description());
        assertTrue(p.yaml().contains("onInvalid: flag"));
    }

    @Test
    void freeTextAddressColumns() {
        List<RawRow> rows = sheet("s", new String[] {"Name", "Address"},
                new String[] {"A Test", "Sakti / Kharu / Leh"}, new String[] {"B Test", "Vill Choglamsar, Leh"});
        LayoutProposal p = expect(rows, "free-address", "Address");
        assertEquals(2, p.evidenceCount());
        // Without outputs the parser would add no columns for the Map step.
        assertTrue(p.yaml().contains("geography.village.name: address_village"), p.yaml());
    }

    @Test
    void indianDigitGrouping() {
        LayoutProposal p = expect(LayoutPatterns.read("numbers"), "numbers", "Indian digit grouping");
        assertEquals(2, p.evidenceCount());
        assertTrue(p.yaml().contains("\"Indian\"") && !p.yaml().contains("\"Western\""), p.yaml());
    }

    @Test
    void cleanSheetGetsNoProposals() {
        List<RawRow> rows = sheet("s", new String[] {"Name", "Gender"}, new String[] {"Asha Test", "F"},
                new String[] {"Ravi Sample", "M"});
        assertTrue(engine.layoutProposals(rows).isEmpty(), engine.layoutProposals(rows).toString());
    }

    /** A master of synthetic names answering like the live LGD lookup: names that contain a probe. */
    private static StudioMetadata master(String... names) {
        return new StudioMetadata(SampleData.REPOSITORY_ROOT.resolve("platform"),
                new SchemeCatalogue(SampleData.SCHEME_CATALOGUE)) {
            @Override
            public boolean liveCodeSet(String codeSet) {
                return true;
            }

            @Override
            public List<CodeValue> codes(String codeSet, String value) {
                return java.util.Arrays.stream(names)
                        .filter(n -> StudioMetadata.probes(value.strip()).stream()
                                .anyMatch(p -> n.toLowerCase().contains(p.toLowerCase())))
                        .map(n -> new CodeValue("C-" + n, n)).toList();
            }
        };
    }

    private static CrosswalkEntry suggestionFor(StudioMetadata metadata, String value) {
        ColumnProfile column = new ColumnProfile("district_text", "text", null, 0, 1,
                List.of(new ValueCount(value, 1)), false);
        Crosswalk crosswalk = new SuggestionEngine(metadata, 0.7).crosswalk(column, "lgd-district",
                new SuggestionEngine.Learned(List.of(), Map.of()));
        return crosswalk.entries().getFirst();
    }

    @Test
    void theCodeColumnTakesTheCodeTargetAndTheNameColumnTheNameTarget() {
        Map<String, String> villages = Map.of("851", "Sakti", "871", "Tarvon");
        StudioMetadata metadata = new StudioMetadata(SampleData.REPOSITORY_ROOT.resolve("platform"),
                new SchemeCatalogue(SampleData.SCHEME_CATALOGUE)) {
            @Override
            public boolean liveCodeSet(String codeSet) {
                return "lgd-village".equals(codeSet);
            }

            @Override
            public List<CodeValue> codes(String codeSet, String value) {
                return villages.entrySet().stream()
                        .filter(v -> v.getKey().equals(value) || v.getValue().equalsIgnoreCase(value))
                        .map(v -> new CodeValue(v.getKey(), v.getValue())).toList();
            }
        };
        ColumnProfile names = new ColumnProfile("VILLAGE", "text", null, 0, 2,
                List.of(new ValueCount("Sakti", 1), new ValueCount("Tarvon", 1)), false);
        ColumnProfile codes = new ColumnProfile("VILLAGE_LGD", "integer", null, 0, 2,
                List.of(new ValueCount("851", 1), new ValueCount("871", 1)), false);
        var suggested = new SuggestionEngine(metadata, 0.7)
                .suggest(new StudioModels.Profile(2, List.of(names, codes), List.of()), List.of());

        Map<String, List<String>> targets = new java.util.HashMap<>();
        suggested.columns().forEach(c -> targets.put(c.column(), c.targets().stream().map(t -> t.target()).toList()));
        assertEquals(List.of("geography.village.name"), targets.get("VILLAGE"));
        assertEquals(List.of("geography.village.lgd"), targets.get("VILLAGE_LGD"));
        assertEquals(1, suggested.crosswalks().size(), "the geography-name target needs an LGD crosswalk");
        assertEquals("lgd-village", suggested.crosswalks().getFirst().codeSet());
    }

    @Test
    void qualifiedNamesSuggestTheMasterNameForTheUserToConfirm() {
        StudioMetadata metadata = master("Zarvon", "Quillmere");
        for (String value : new String[] {"North-Zarvon", "Zarvon (Rural)", "Zarvon Dist."}) {
            CrosswalkEntry entry = suggestionFor(metadata, value);
            assertEquals("C-Zarvon", entry.targetValue(), value);
            assertTrue(entry.confidence() > 0 && entry.confidence() < 0.7, value + " " + entry.confidence());
            assertTrue(!entry.accepted(), value + " waits for the user");
        }
        assertEquals("C-Zarvon", suggestionFor(metadata, "zarvon").targetValue());
    }

    @Test
    void anUnrelatedNameGetsNoSuggestion() {
        CrosswalkEntry entry = suggestionFor(master("Zarvon", "Quillmere"), "Plinth");
        assertEquals(null, entry.targetValue());
    }

    @Test
    void aWordSharedByTwoDistrictsIsAmbiguousNotWrong() {
        CrosswalkEntry entry = suggestionFor(master("Upper Zarvon", "Lower Zarvon"), "Zarvon");
        assertEquals(null, entry.targetValue(), entry.toString());
    }
}
