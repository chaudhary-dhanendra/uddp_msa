package gov.uddp.shaping.ops;

import static gov.uddp.shaping.ops.LayoutPatterns.assertMatches;
import static gov.uddp.shaping.ops.LayoutPatterns.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.uddp.shaping.api.RawRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DittoOperationTest {

    private final DittoOperation operation = new DittoOperation();

    @Test
    void defaultTokensRepeatTheValueAboveCaseInsensitively() {
        List<RawRow> shaped = operation.apply(LayoutPatterns.read("ditto"), config("{\"columns\": [1]}"));

        assertMatches("ditto", shaped);
    }

    @Test
    void customTokensReplaceTheDefaults() {
        List<RawRow> shaped = operation.apply(LayoutPatterns.read("ditto"),
                config("{\"columns\": [1], \"tokens\": [\"same\"]}"));

        assertEquals("-do-", shaped.get(2).cells().get(1));
    }

    @Test
    void namedColumnsRequireHeadersInsteadOfSilentlySkipping() {
        List<RawRow> rows = List.of(new RawRow("f", "Leh", 1, List.of("Padma Dolma", "\""), Map.of()));

        assertThatThrownBy(() -> operation.apply(rows,
                config("{\"columns\": [\"VILLAGE\"], \"tokens\": [\"\\\"\"]}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no established headers");
    }

    @Test
    void independentDittoRulesRunBeforeAliasesAcrossHeterogeneousSheets() {
        Map<String, String> leh = Map.of("@h0", "OWNER_NAME", "@h1", "VILLAGE");
        Map<String, String> kargil = Map.of("@h0", "Name of Owner", "@h1", "Village");
        List<RawRow> rows = List.of(
                new RawRow("f", "Kargil", 1, List.of("Mohd Ali", "Akchamal"), kargil),
                new RawRow("f", "Kargil", 2, List.of("Fatima Bano", "-do-"), kargil),
                new RawRow("f", "Kargil", 3, List.of("Sonam Wangchuk", "Abran"), kargil),
                new RawRow("f", "Kargil", 4, List.of("Tsering Dolma", "Aksho"), kargil),
                new RawRow("f", "Leh", 1, List.of("Tsering Angchuk", "Choglamsar (Ct)"), leh),
                new RawRow("f", "Leh", 2, List.of("Padma Dolma", "\""), leh),
                new RawRow("f", "Leh", 3, List.of("Stanzin Norbu", "Chemrey"), leh),
                new RawRow("f", "Leh", 4, List.of("Rigzin Lhamo", "Hunder"), leh),
                new RawRow("f", "Leh", 5, List.of("Kunzes Angmo", "Bogdang"), leh),
                new RawRow("f", "Leh", 6, List.of("Jigmet Namgyal", "Hamis"), leh));

        List<RawRow> shaped = operation.apply(rows, config("{\"columns\": [\"VILLAGE\"], \"tokens\": [\"\\\"\"]}"));
        shaped = operation.apply(shaped, config("{\"columns\": [\"Village\"], \"tokens\": [\"-do-\"]}"));

        assertEquals("Akchamal", shaped.get(1).cells().get(1));
        assertEquals("Choglamsar (Ct)", shaped.get(5).cells().get(1));

        var aliases = new ColumnAliasesOperation().apply(shaped,
                Map.of("aliases", Map.of("VILLAGE", List.of("Village"))));
        assertThat(aliases.get(1).carried().get("@h1")).isEqualTo("VILLAGE");
        assertThat(aliases.get(1).cells().get(1)).isEqualTo("Akchamal");
        assertThat(aliases.get(5).carried().get("@h1")).isEqualTo("VILLAGE");
        assertThat(aliases.get(5).cells().get(1)).isEqualTo("Choglamsar (Ct)");
    }

    @Test
    void dittoStateIsScopedToEachSheetEvenWhenRowsAreInterleaved() {
        Map<String, String> headers = Map.of("@h0", "Village");
        List<RawRow> rows = List.of(
                new RawRow("f", "Kargil", 1, List.of("Akchamal"), headers),
                new RawRow("f", "Leh", 1, List.of("Choglamsar (Ct)"), headers),
                new RawRow("f", "Kargil", 2, List.of("-do-"), headers),
                new RawRow("f", "Leh", 2, List.of("-do-"), headers));

        List<RawRow> shaped = operation.apply(rows, config("{\"columns\": [\"Village\"], \"tokens\": [\"-do-\"]}"));

        assertThat(shaped).extracting(row -> row.cells().getFirst())
                .containsExactly("Akchamal", "Choglamsar (Ct)", "Akchamal", "Choglamsar (Ct)");
    }
}
