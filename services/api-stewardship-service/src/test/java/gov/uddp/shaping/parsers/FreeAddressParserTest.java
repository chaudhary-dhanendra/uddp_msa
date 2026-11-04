package gov.uddp.shaping.parsers;

import static org.assertj.core.api.Assertions.assertThat;

import gov.uddp.core.geography.GeographyService;
import gov.uddp.core.read.Page;
import gov.uddp.shaping.api.ParseResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FreeAddressParserTest {

    private static GeographyService.GeoEntity e(String type, String code, String name, String parentType,
                                                String parentCode, String parentName) {
        return new GeographyService.GeoEntity(type, code, name, "ACTIVE", parentType, parentCode, parentName);
    }

    /** Ngairang: Lamkhai block (Rimpang, Tengsar, sample villages), Phunal block; Senkhai: Wangkhem block (Mukhra, Rimpang). */
    private static final List<GeographyService.GeoEntity> FIXTURE = List.of(
            e("DISTRICT", "D1", "Ngairang", "STATE", "S", "Test State"),
            e("DISTRICT", "D2", "Senkhai", "STATE", "S", "Test State"),
            e("BLOCK", "B1", "Lamkhai", "DISTRICT", "D1", "Ngairang"),
            e("BLOCK", "B2", "Phunal", "DISTRICT", "D1", "Ngairang"),
            e("BLOCK", "B3", "Wangkhem", "DISTRICT", "D2", "Senkhai"),
            e("VILLAGE", "V1", "Rimpang", "BLOCK", "B1", "Lamkhai"),
            e("VILLAGE", "V2", "Tengsar", "BLOCK", "B1", "Lamkhai"),
            e("VILLAGE", "V3", "Mukhra", "BLOCK", "B3", "Wangkhem"),
            e("VILLAGE", "V4", "Rimpang", "BLOCK", "B3", "Wangkhem"));

    private final FreeAddressParser parser = new FreeAddressParser(new FixtureGeography(),
            Path.of("../../platform"));

    /** In-memory master: search and ancestors answered from {@link #FIXTURE}. */
    private static final class FixtureGeography extends GeographyService {
        FixtureGeography() {
            super(null);
        }

        @Override
        public Page<GeoEntity> search(String type, String text, String parentType, String parentCode, String status,
                                      Integer page, Integer size) {
            String needle = text == null ? "" : text.toLowerCase(Locale.ROOT);
            List<GeoEntity> hits = FIXTURE.stream().filter(x -> x.entityType().equals(type))
                    .filter(x -> x.name().toLowerCase(Locale.ROOT).contains(needle)).toList();
            return new Page<>(hits, 0, 200, hits.size(), 1, "name,asc");
        }

        @Override
        public Optional<GeoEntity> find(String type, String code) {
            return FIXTURE.stream().filter(x -> x.entityType().equals(type) && x.code().equals(code)).findFirst();
        }

        @Override
        public List<GeoEntity> ancestors(String type, String code) {
            List<GeoEntity> chain = new ArrayList<>();
            GeoEntity current = find(type, code).orElse(null);
            while (current != null && current.parentType() != null) {
                current = find(current.parentType(), current.parentCode()).orElse(null);
                if (current != null) {
                    chain.add(0, current);
                }
            }
            return chain;
        }
    }

    @Test
    void allThreeLevelsNamedIsFullConfidence() {
        ParseResult result = parser.parse("Rimpang / Lamkhai / Ngairang", Map.of());

        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(result.values()).containsEntry("geography.village.name", "Rimpang")
                .containsEntry("geography.block.name", "Lamkhai").containsEntry("geography.district.name", "Ngairang");
    }

    @Test
    void prefixAndUniqueVillageInDistrictIsPointEight() {
        ParseResult result = parser.parse("Vill Tengsar, Ngairang", Map.of());

        assertThat(result.confidence()).isEqualTo(0.8);
        assertThat(result.values()).containsEntry("geography.village.name", "Tengsar")
                .containsEntry("geography.block.name", "Lamkhai").containsEntry("geography.district.name", "Ngairang");
    }

    @Test
    void blockAndDistrictWithoutVillage() {
        ParseResult result = parser.parse("Phunal-Ngairang", Map.of());

        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(result.values()).doesNotContainKey("geography.village.name")
                .containsEntry("geography.block.name", "Phunal").containsEntry("geography.district.name", "Ngairang");
    }

    @Test
    void whitespaceSeparatedLevels() {
        ParseResult result = parser.parse("Mukhra Wangkhem Senkhai", Map.of());

        assertThat(result.confidence()).isEqualTo(1.0);
        assertThat(result.values()).containsEntry("geography.village.name", "Mukhra")
                .containsEntry("geography.block.name", "Wangkhem").containsEntry("geography.district.name", "Senkhai");
    }

    @Test
    void suffixAndAliasAreNormalised() {
        ParseResult result = parser.parse("Tengser (CT)",
                Map.of("aliases", Map.of("Tengsar", List.of("Tengser"))));

        assertThat(result.confidence()).isEqualTo(0.8);
        assertThat(result.values()).containsEntry("geography.village.name", "Tengsar");
    }

    @Test
    void ambiguousVillageGivesSuggestionsAndNoNames() {
        ParseResult result = parser.parse("Rimpang", Map.of());

        assertThat(result.confidence()).isEqualTo(0.5);
        assertThat(result.values()).doesNotContainKey("geography.village.name");
        assertThat((List<?>) result.values().get("geography.suggestions")).hasSize(2);
    }

    @Test
    void packAliasesApply() {
        ParseResult result = parser.parse("Mukhraa, Senkhai", Map.of("aliases", Map.of("Mukhra", List.of("Mukhraa"))));

        assertThat(result.values()).containsEntry("geography.village.name", "Mukhra");
    }

    @Test
    void unknownTextResolvesNothing() {
        ParseResult result = parser.parse("Nowhere", Map.of());

        assertThat(result.confidence()).isEqualTo(0.0);
        assertThat(result.values()).isEmpty();
    }
}
