package gov.uddp.core.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The Python matching integration tests seed canonical Residents from
 * tests/fixtures/canonical/sample-residents.json; this keeps that file equal to what Common Core
 * actually produces from sample-data (for the fields matching reads).
 */
class CanonicalFixtureContractTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void pythonCanonicalFixtureMatchesCommonCoreOutput() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var service = SampleData.processing(connections, SampleData.loader(SampleData.STATE_PACKS));
        SampleData.landAll(connections, SampleData.STATE_PACKS).values().forEach(ids -> ids.forEach(service::processBatch));

        Set<String> produced = new HashSet<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select state_code, source_system, canonical_payload::text from canonical_resident");
                var r = s.executeQuery()) {
            while (r.next()) {
                produced.add(key(r.getString(1), r.getString(2), JSON.readTree(r.getString(3))));
            }
        }
        Set<String> fixture = new HashSet<>();
        var file = SampleData.REPOSITORY_ROOT.resolve("tests/fixtures/canonical/sample-residents.json");
        for (JsonNode entry : JSON.readTree(Files.readString(file))) {
            fixture.add(key(entry.get("stateCode").asText(), entry.get("sourceSystem").asText(), entry.get("resident")));
        }

        assertEquals(7, produced.size());
        assertEquals(fixture, produced);
    }

    static String key(String stateCode, String sourceSystem, JsonNode resident) {
        return String.join("|", stateCode, sourceSystem, resident.path("name").asText(),
                resident.path("dateOfBirth").asText(), resident.path("gender").asText(), resident.path("mobile").asText(),
                resident.path("address").path("districtLGD").asText());
    }
}
