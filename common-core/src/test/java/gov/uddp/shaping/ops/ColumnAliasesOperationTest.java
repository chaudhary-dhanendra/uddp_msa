package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.LayoutPatterns.assertMatches;
import static gov.uddp.shaping.ops.LayoutPatterns.config;

import gov.uddp.shaping.api.RawRow;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class ColumnAliasesOperationTest {

    @Test
    void renamesVariantsIgnoringCaseAndSpaces() {
        List<RawRow> withHeaders = new HeaderDetectOperation().apply(LayoutPatterns.read("aliases"), config("{}"));

        List<RawRow> aliased = new ColumnAliasesOperation().apply(withHeaders, config(
                "{\"aliases\": {\"NAME\": [\"beneficiaryname\", \"applicant\"], \"BIRTH\": [\"d.o.b\", \"dob\"],"
                        + " \"MOBILE\": [\"MOBILE NO\"]}}"));

        assertMatches("aliases", aliased);
    }

    @Test
    void canonicalKeyCollectsTheSourceVariantAcrossSheets() {
        List<RawRow> headed = new HeaderDetectOperation().apply(List.of(
                new RawRow("f", "Leh", 1, List.of("DOB"), Map.of()),
                new RawRow("f", "Leh", 2, List.of("12-03-1958"), Map.of()),
                new RawRow("f", "Kargil", 1, List.of("Date of Birth"), Map.of()),
                new RawRow("f", "Kargil", 2, List.of("14-08-1950"), Map.of())), config("{}"));

        List<RawRow> aliased = new ColumnAliasesOperation().apply(headed,
                config("{\"aliases\": {\"DOB\": [\"Date of Birth\"]}}"));

        assertEquals(List.of("DOB", "DOB"), aliased.stream().map(row -> row.carried().get("@h0")).toList());
    }
}
