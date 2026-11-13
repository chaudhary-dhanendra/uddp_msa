package gov.uddp.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.uddp.contracts.StatePackClient;
import gov.uddp.contracts.StatePackRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActivePackStateFieldsTest {

    @Test
    void readsTheGoldenStateFieldsOfEachActivePackFromItsFiles() {
        var withFields = pack("la-animal-husbandry", Map.of(
                "state.yaml", "packId: la-animal-husbandry\nstateCode: LAAH\nversion: 1.0.1\n"
                        + "extensions: [extensions/resident.yaml]\n",
                "extensions/resident.yaml", "entity: Resident\nfields:\n"
                        + "  - { key: sheep, label: SHEEP, type: number, golden: true }\n"
                        + "  - { key: note, label: Note, type: text, golden: false }\n"));
        var without = pack("la-agriculture", Map.of("state.yaml", "packId: la-agriculture\nstateCode: LAAG\n"));

        var fields = new ActivePackStateFields(null).fromPacks(List.of(withFields, without));

        assertEquals(Map.of("LAAH", List.of("sheep")), fields);
    }

    private static StatePackClient.Pack pack(String packId, Map<String, String> files) {
        return new StatePackClient.Pack(new StatePackRef(packId, "1.0.1", "sha256:0123456789abcdef"), null, "ACTIVE",
                files);
    }
}
