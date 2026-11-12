package gov.uddp.shaping.parsers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MobileNumberParserTest {
    private final MobileNumberParser parser = new MobileNumberParser();

    @Test
    void countryCodeTrunkZeroAndSeparatorsAreRemoved() {
        assertThat(parser.parse("+91 94190 00001", Map.of()).values()).containsEntry("mobile", "9419000001");
        assertThat(parser.parse("094190-00001", Map.of()).values()).containsEntry("mobile", "9419000001");
        assertThat(parser.parse("(94190) 00.001", Map.of()).values()).containsEntry("mobile", "9419000001");
        assertThat(parser.parse("9419000001", Map.of()).confidence()).isEqualTo(1.0);
    }

    @Test
    void severalNumbersKeepTheFirstAndListTheRest() {
        var result = parser.parse("9419000001 / 9419000002, 0941-900-0003", Map.of());
        assertThat(result.values()).containsEntry("mobile", "9419000001")
                .containsEntry("otherMobiles", "9419000002,9419000003");
    }

    @Test
    void aNumberThatCannotBeAMobileIsLowConfidenceOrNothing() {
        var landline = parser.parse("1982252001", Map.of());
        assertThat(landline.confidence()).isEqualTo(0.5);
        assertThat(landline.warnings()).allMatch(w -> !w.contains("1982252001"), "warnings never hold the value");
        var tooShort = parser.parse("941900001", Map.of());
        assertThat(tooShort.values()).isEmpty();
        assertThat(tooShort.confidence()).isZero();
    }
}
