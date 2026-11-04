package gov.uddp.shaping.parsers;

import static org.assertj.core.api.Assertions.assertThat;

import gov.uddp.shaping.api.ParseResult;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NameRelationParserTest {

    private static final NameRelationParsing PARSING = new NameRelationParsing(Path.of("../../platform"));
    private static final NameRelationAgeParser WITH_AGE = new NameRelationAgeParser(PARSING);
    private static final NameRelationParser WITHOUT_AGE = new NameRelationParser(PARSING);

    private static Map<String, Object> expect(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    static Stream<Arguments> cells() {
        return Stream.of(
                Arguments.of("Tsering Angchuk S/O Sonam Dorjay", 1.0, expect(
                        "name", "Tsering Angchuk", "nameNormalised", "TSERING ANGCHUK", "relation.type", "FATHER",
                        "relation.name", "Sonam Dorjay", "relation.nameNormalised", "SONAM DORJAY",
                        "relation.deceased", false, "inferredGender", "MALE")),
                Arguments.of("Mohd Ali S/O Late Gh Nabi Age-71", 1.0, expect(
                        "name", "Mohd Ali", "nameNormalised", "MOHAMMED ALI", "relation.type", "FATHER",
                        "relation.name", "Gh Nabi", "relation.nameNormalised", "GHULAM NABI",
                        "relation.deceased", true, "age.years", 71, "inferredGender", "MALE")),
                Arguments.of("Rigzin Namgyal SO Dorjay Namgyal 58Y", 0.8, expect(
                        "name", "Rigzin Namgyal", "relation.type", "FATHER", "relation.name", "Dorjay Namgyal",
                        "relation.nameNormalised", "DORJAY NAMGYAL", "age.years", 58, "inferredGender", "MALE")),
                Arguments.of("Stanzin Dolma D/O Haji Muhammad Hussain (63 Yrs)", 1.0, expect(
                        "name", "Stanzin Dolma", "relation.type", "FATHER", "relation.name", "Haji Muhammad Hussain",
                        "relation.nameNormalised", "MOHAMMED HUSSAIN", "age.years", 63, "inferredGender", "FEMALE")),
                Arguments.of("Zainab Bano W/O Marhoom Munshi Ghulam Rasool, Age:42", 1.0, expect(
                        "name", "Zainab Bano", "relation.type", "HUSBAND", "relation.name", "Munshi Ghulam Rasool",
                        "relation.nameNormalised", "GHULAM RASOOL", "relation.deceased", true, "age.years", 42,
                        "inferredGender", "FEMALE")),
                Arguments.of("Padma Lamo C/O Smt Dechen;", 1.0, expect(
                        "name", "Padma Lamo", "relation.type", "GUARDIAN", "relation.name", "Smt Dechen",
                        "relation.nameNormalised", "DECHEN")),
                Arguments.of("Mr Tashi Namgyal Age 67", 0.6, expect(
                        "name", "Mr Tashi Namgyal", "nameNormalised", "TASHI NAMGYAL", "age.years", 67)));
    }

    @ParameterizedTest
    @MethodSource("cells")
    void parsesEachCell(String cell, double confidence, Map<String, Object> expected) {
        ParseResult result = WITH_AGE.parse(cell, Map.of());

        assertThat(result.confidence()).isEqualTo(confidence);
        assertThat(result.values()).containsAllEntriesOf(expected);
        assertThat(result.values().get("name")).isNotEqualTo("");
    }

    @org.junit.jupiter.api.Test
    void ageIsNotLiftedByTheParserWithoutAge() {
        ParseResult result = WITHOUT_AGE.parse("Mohd Ali S/O Gh Nabi", Map.of());

        assertThat(result.values()).doesNotContainKey("age.years");
        assertThat(result.values()).containsEntry("relation.nameNormalised", "GHULAM NABI");
    }

    @org.junit.jupiter.api.Test
    void relationOnlyCellPrefixIsAccepted() {
        ParseResult result = WITH_AGE.parse("w/o Sonam Dorjay", Map.of());

        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(result.values()).doesNotContainKey("name");
        assertThat(result.values()).containsEntry("relation.type", "HUSBAND")
                .containsEntry("relation.name", "Sonam Dorjay").containsEntry("inferredGender", "FEMALE");
    }

    @org.junit.jupiter.api.Test
    void packsCanExtendTheMarkers() {
        ParseResult result = WITH_AGE.parse("Ali B/O Hasan", Map.of("extraMarkers", Map.of("B/O", "BROTHER")));

        assertThat(result.values()).containsEntry("relation.type", "BROTHER").containsEntry("name", "Ali");
        assertThat(result.confidence()).isEqualTo(1.0);
    }
}
