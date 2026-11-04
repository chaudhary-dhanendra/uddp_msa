package gov.uddp.core.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.onboarding.OnboardingService.ProcessingMode;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.OnboardingHarness;
import gov.uddp.core.testsupport.Redelivery;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M11 acceptance criteria 2 (State 4 onboarded incrementally joins the existing Ram Singh identity) and 3 (a corrected
 * source record keeps the Golden ID, adds a version and a VALUE_CHANGED event, and the old value stays in history).
 */
class GoldenIncrementalAndCorrectionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path work;

    @Test
    void stateFourOnboardedIncrementallyJoinsTheExistingRamSinghIdentity() throws Exception {
        Path packs = Redelivery.copyPacks(Files.createDirectories(work.resolve("packs")),
                SampleData.STATE_PACKS, SampleData.REPOSITORY_ROOT.resolve("services/statepack-service/seed/m10-state-packs"));
        var harness = new OnboardingHarness(packs);
        var sql = new Sql(harness.connections);
        var processing = SampleData.processing(harness.connections, harness.packs);
        SampleData.landAll(harness.connections, SampleData.STATE_PACKS).values().forEach(ids -> ids.forEach(processing::processBatch));
        var golden = new GoldenResidentService(harness.connections, JSON, harness.packs);
        var baseline = golden.build(MatchingRunFixture.completed(harness.connections, List.of(Set.copyOf(sql.canonicalsNamed("Ram Singh")))));
        assertEquals(5, baseline.created());
        UUID ramId = sql.goldenOf(sql.canonicalsNamed("Ram Singh").getFirst());
        Map<UUID, Long> versionsBefore = currentVersions(sql);

        harness.activate("state-4", "1.0.0");
        harness.autoMatches = () -> List.of(Set.copyOf(sql.canonicalsNamed("Ram Singh")));
        UUID run = harness.onboarding.start("state-4", "1.0.0", "citizen-register", ProcessingMode.INCREMENTAL);
        harness.onboarding.runToCompletion(run);

        assertEquals("COMPLETED", sql.text("select status from onboarding_run where onboarding_run_id=?", run));
        assertEquals("INCREMENTAL", sql.text("select processing_mode from onboarding_run where onboarding_run_id=?", run));
        UUID matching = sql.uuid("select matching_run_id from onboarding_stage_execution where onboarding_run_id=?"
                + " and stage='MATCHED' and status='COMPLETED'", run);
        assertEquals("INCREMENTAL", sql.text("select mode from matching_run where run_id=?", matching));
        assertEquals(2, sql.count("select input_count from onboarding_stage_execution where onboarding_run_id=?"
                + " and stage='MATCHED'", run), "only the two new State 4 records are matched");
        UUID ram4 = sql.uuid("select canonical_resident_id from canonical_resident_current where state_code='ST4'"
                + " and canonical_payload->>'name'='Ram Singh'");
        UUID neha = sql.uuid("select canonical_resident_id from canonical_resident_current where state_code='ST4'"
                + " and canonical_payload->>'name'='Neha Verma'");
        assertEquals(0, sql.count("select count(*) from resident_match_pair where run_id=?"
                + " and canonical_record_a not in (?, ?) and canonical_record_b not in (?, ?)", matching, ram4, neha, ram4, neha),
                "every scored pair touches a new record");

        assertEquals(9, sql.count("select count(*) from canonical_resident"));
        assertEquals(6, sql.count("select count(*) from golden_resident where status='ACTIVE'"));
        assertEquals(ramId, sql.goldenOf(ram4), "State 4 Ram Singh joins the SAME Golden ID");
        assertEquals(4, sql.count("select count(*) from golden_resident_membership where golden_resident_id=?"
                + " and effective_to is null", ramId));
        assertEquals(2, sql.count("select version_number from golden_resident_version where golden_resident_id=?"
                + " and effective_to is null", ramId));
        assertEquals(1, sql.count("select count(*) from golden_resident_event where golden_resident_id=?"
                + " and event_type='MEMBER_ADDED' and canonical_resident_id=? and version_number=2", ramId, ram4));
        UUID nehaId = sql.goldenOf(neha);
        assertTrue(!versionsBefore.containsKey(nehaId));
        assertEquals(1, sql.count("select count(*) from golden_resident_event where golden_resident_id=?"
                + " and event_type='CREATED'", nehaId));
        var after = currentVersions(sql);
        versionsBefore.forEach((id, version) -> {
            if (!id.equals(ramId)) {
                assertEquals(version, after.get(id), "an untouched identity gets no new version");
            }
        });
    }

    @Test
    void aMobileCorrectionKeepsTheGoldenIdAndRecordsValueChangedWithHistory() throws Exception {
        assertCorrection(false);
    }

    @Test
    void aMobileCorrectionMatchedIncrementallyGivesTheSameResult() throws Exception {
        assertCorrection(true);
    }

    private void assertCorrection(boolean incremental) throws Exception {
        PostgresConnectionFactory connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        var packs = SampleData.loader(SampleData.STATE_PACKS);
        var golden = new GoldenResidentService(connections, JSON, packs, pensionTrustedForMobile());
        golden.build(SampleData.processedSampleWithMatchingRun(connections));
        UUID ramPension = sql.uuid("select canonical_resident_id from canonical_resident_current where state_code='ST2'"
                + " and canonical_payload->>'name'='Ram Singh'");
        UUID ramId = sql.goldenOf(ramPension);
        assertEquals("9876543210", sql.text("select golden_payload->>'mobile' from golden_resident_version"
                + " where golden_resident_id=? and effective_to is null", ramId));

        UUID batch = Redelivery.land(connections, packs.find("state-2").orElseThrow(), "pension", work,
                Redelivery.replaceIn("F5001", "9876543210", "9999999999"));
        SampleData.processing(connections, packs).processBatch(batch);
        UUID corrected = sql.uuid("select canonical_resident_id from canonical_resident_current where state_code='ST2'"
                + " and canonical_payload->>'name'='Ram Singh'");
        assertNotEquals(ramPension, corrected);
        Set<UUID> ram = new HashSet<>(sql.canonicalsNamed("Ram Singh"));
        UUID run = incremental ? MatchingRunFixture.incremental(connections, List.of(ram))
                : MatchingRunFixture.completed(connections, List.of(ram));

        var summary = golden.build(run);

        assertEquals(0, summary.created());
        assertEquals(1, summary.versioned());
        assertEquals(4, summary.unchanged());
        assertEquals(ramId, sql.goldenOf(corrected), "same Golden ID after the correction");
        assertEquals(5, sql.count("select count(*) from golden_resident where status='ACTIVE'"));
        assertEquals(2, sql.count("select version_number from golden_resident_version where golden_resident_id=?"
                + " and effective_to is null", ramId));
        assertEquals("9999999999", sql.text("select golden_payload->>'mobile' from golden_resident_version"
                + " where golden_resident_id=? and version_number=2", ramId));
        assertEquals(1, sql.count("select count(*) from golden_resident_event where golden_resident_id=?"
                + " and event_type='VALUE_CHANGED' and field_path='mobile' and old_value='\"9876543210\"'"
                + " and new_value='\"9999999999\"'", ramId));
        assertEquals(1, sql.count("select count(*) from golden_resident_event where golden_resident_id=?"
                + " and event_type='MEMBER_VERSION_CHANGED' and canonical_resident_id=?", ramId, corrected));
        assertEquals(0, sql.count("select count(*) from golden_resident_membership where effective_to is null"
                + " and canonical_resident_id=?", ramPension), "the superseded version is no longer a member");

        @SuppressWarnings("unchecked")
        var versions = (List<Map<String, Object>>) golden.history(ramId).get("versions");
        assertEquals(2, versions.size());
        assertEquals("9876543210", ((JsonNode) versions.get(0).get("goldenPayload")).path("mobile").asText(),
                "the old value stays queryable in history");
        assertEquals("ALL_VALUES_AGREE", mobileRule(versions.get(0)));
        assertEquals("CONFIGURED_SOURCE_PRECEDENCE", mobileRule(versions.get(1)));
        assertTrue(((List<?>) versions.get(0).get("members")).stream()
                .anyMatch(member -> ramPension.equals(((Map<?, ?>) member).get("canonical_resident_id"))));
    }

    /** A platform policy under which the pension source is the governed authority for mobile numbers. */
    private PlatformSurvivorshipPolicyLoader pensionTrustedForMobile() throws Exception {
        Path platform = Files.createDirectories(work.resolve("platform/survivorship"));
        Files.writeString(platform.resolve("resident.yaml"), """
                entity: Resident
                version: resident-survivorship-v3
                fields:
                  name: { strategy: DETERMINISTIC_FALLBACK }
                  dateOfBirth: { strategy: DETERMINISTIC_FALLBACK }
                  gender: { strategy: DETERMINISTIC_FALLBACK }
                  mobile:
                    strategy: SOURCE_TRUST
                    sourcePriority: [pension]
                  address.districtLGD: { strategy: DETERMINISTIC_FALLBACK }
                """);
        var properties = new StatePackProperties();
        properties.setPlatformDirectory(work.resolve("platform").toString());
        return new PlatformSurvivorshipPolicyLoader(properties);
    }

    private static String mobileRule(Map<String, Object> version) {
        @SuppressWarnings("unchecked")
        var provenance = (List<Map<String, Object>>) version.get("fieldProvenance");
        return provenance.stream().filter(row -> "mobile".equals(row.get("field_path"))).findFirst().orElseThrow()
                .get("rule_applied").toString();
    }

    private static Map<UUID, Long> currentVersions(Sql sql) {
        var out = new java.util.HashMap<UUID, Long>();
        for (UUID id : sql.column("select golden_resident_id from golden_resident where status='ACTIVE'", UUID.class)) {
            out.put(id, sql.count("select version_number from golden_resident_version where golden_resident_id=?"
                    + " and effective_to is null", id));
        }
        return out;
    }
}
