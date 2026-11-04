package gov.uddp.core.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves the M9 platform policy at the database boundary, including persisted field provenance. */
class M9SourceTrustGoldenIntegrationTest {
    @TempDir Path temporaryDirectory;

    @Test void sourceTrustResolvesAnAcceptedCrossStateConflictWithoutCanonicalIdFallback() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var packs = SampleData.loader(SampleData.FIXTURE_PACKS);
        var processing = SampleData.processing(connections, packs);
        SampleData.landAll(connections, SampleData.FIXTURE_PACKS).values().forEach(ids -> ids.forEach(processing::processBatch));
        UUID registry = ConflictFixtureTest.canonicalId(connections, "FXA", "Ram Singh");
        UUID welfare = ConflictFixtureTest.canonicalId(connections, "FXB", "Ram Singh Yadav");
        assertNotEquals(registry, welfare);
        UUID run = MatchingRunFixture.completed(connections, List.of(Set.of(registry, welfare)));

        Path platform = temporaryDirectory.resolve("platform");
        Files.createDirectories(platform.resolve("survivorship"));
        Files.writeString(platform.resolve("survivorship/resident.yaml"), """
                entity: Resident
                version: resident-survivorship-v3
                fields:
                  name: { strategy: DETERMINISTIC_FALLBACK }
                  dateOfBirth: { strategy: DETERMINISTIC_FALLBACK }
                  gender: { strategy: DETERMINISTIC_FALLBACK }
                  mobile:
                    strategy: SOURCE_TRUST
                    sourcePriority: [welfare, registry]
                  address.districtLGD: { strategy: DETERMINISTIC_FALLBACK }
                """);
        var properties = new StatePackProperties();
        properties.setPlatformDirectory(platform.toString());
        var golden = new GoldenResidentService(connections, new ObjectMapper(), packs,
                new PlatformSurvivorshipPolicyLoader(properties));
        golden.build(run);

        var resident = golden.list(run, "resident-survivorship-v3").stream()
                .filter(row -> ((com.fasterxml.jackson.databind.JsonNode) row.get("goldenPayload")).path("mobile").asText().equals("9111111111"))
                .findFirst().orElseThrow();
        UUID goldenId = (UUID) resident.get("goldenResidentId");
        var detail = golden.detail(goldenId);
        assertEquals("resident-survivorship-v3", detail.get("survivorshipPolicyVersion"));
        assertEquals(2, ((List<?>) detail.get("members")).size());
        var mobile = ((List<java.util.Map<String, Object>>) detail.get("fieldProvenance")).stream()
                .filter(row -> "mobile".equals(row.get("field_path"))).findFirst().orElseThrow();
        assertEquals(welfare, mobile.get("canonical_resident_id"));
        assertEquals("CONFIGURED_SOURCE_PRECEDENCE", mobile.get("rule_applied"));
        assertEquals("resident-survivorship-v3", mobile.get("survivorship_policy_version"));
    }
}
