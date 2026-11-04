package gov.uddp.shaping.parsers;

import static org.assertj.core.api.Assertions.assertThat;

import gov.uddp.shaping.api.ParseResult;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NameNormaliseParserTest {

    private final NameNormaliseParser parser = new NameNormaliseParser(new NameRelationParsing(Path.of("../platform")));
    private final NameRelationParser relation = new NameRelationParser(new NameRelationParsing(Path.of("../platform")));

    @Test
    void normalisesAPlainNameLikeTheNamePartOfAParentageCell() {
        ParseResult plain = parser.parse("Gh. Nabi", Map.of());
        ParseResult composite = relation.parse("Mohd Ali S/O Gh. Nabi", Map.of());

        assertThat(plain.confidence()).isEqualTo(1.0);
        assertThat(plain.values().get("nameNormalised")).isEqualTo(composite.values().get("relation.nameNormalised"));
    }

    @Test
    void blankValueGivesNoValueAndZeroConfidence() {
        ParseResult result = parser.parse("  ", Map.of());

        assertThat(result.values()).isEmpty();
        assertThat(result.confidence()).isZero();
    }
}
