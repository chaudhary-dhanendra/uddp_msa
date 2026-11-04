package gov.uddp.shaping.parsers;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UnitQuantityParserTest {
    private final UnitQuantityParser parser = new UnitQuantityParser();

    @Test
    void splitsQuantityAndUnit() {
        var kanal = parser.parse("1.5 Kanal", Map.of());
        assertThat(kanal.values()).containsEntry("quantity", new BigDecimal("1.5")).containsEntry("unit", "KANAL");
        assertThat(kanal.confidence()).isEqualTo(1.0);
        assertThat(parser.parse(" 1 Unit ", Map.of()).values())
                .containsEntry("quantity", new BigDecimal("1")).containsEntry("unit", "UNIT");
        assertThat(parser.parse("3kw", Map.of()).values()).containsEntry("quantity", new BigDecimal("3"))
                .containsEntry("unit", "KW");
        assertThat(parser.parse("2,5 Head", Map.of()).values()).containsEntry("quantity", new BigDecimal("2.5"));
    }

    @Test
    void aBareNumberHasNoUnitAndJunkGivesNothing() {
        var bare = parser.parse("4", Map.of());
        assertThat(bare.values()).containsOnlyKeys("quantity");
        assertThat(bare.confidence()).isEqualTo(0.8);
        var junk = parser.parse("Kanal 1", Map.of());
        assertThat(junk.values()).isEmpty();
        assertThat(junk.warnings()).hasSize(1);
        assertThat(parser.parse(null, Map.of()).values()).isEmpty();
    }
}
