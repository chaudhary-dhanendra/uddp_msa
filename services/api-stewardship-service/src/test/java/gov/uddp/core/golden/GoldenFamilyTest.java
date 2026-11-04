package gov.uddp.core.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import gov.uddp.core.canonical.CanonicalResidentProcessingService;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.onboarding.OnboardingService.ProcessingMode;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.OnboardingHarness;
import gov.uddp.core.testsupport.Redelivery;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M14 acceptance: one stable Golden Family per source household, members resolved to current Golden Residents,
 * versions and events only when members or roles change, retirement and reactivation without reusing an ID, and
 * State 4's households onboarded by configuration alone.
 */
class GoldenFamilyTest {
    private static final String FX_SOURCE = "household-register";

    @TempDir
    Path work;

    @Test
    void sampleHouseholdsBecomeStableGoldenFamiliesAndAnUnchangedRebuildWritesNothing() {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        UUID run = SampleData.processedSampleWithMatchingRun(connections);
        var golden = SampleData.golden(connections, SampleData.loader(SampleData.STATE_PACKS));

        golden.build(run);

        assertEquals(6, sql.count("select count(*) from golden_family where status='ACTIVE'"));
        assertEquals(7, sql.count("select count(*) from golden_family_membership where effective_to is null"));
        assertEquals(6, sql.count("select count(*) from golden_family_version where version_number=1 and effective_to is null"));
        assertEquals(6, sql.count("select count(*) from golden_family_event where event_type='CREATED'"));
        UUID ram = sql.goldenOf(sql.canonicalsNamed("Ram Singh").getFirst());
        assertEquals(3, sql.count("select count(*) from golden_family_membership where golden_resident_id=?"
                + " and effective_to is null", ram), "Ram Singh's one Golden Resident is in three households");
        assertEquals(2, sql.count("select (v.family_payload->>'memberCount')::int from golden_family_version v"
                + " join golden_family f using (golden_family_id) where f.family_record_key='ST1|pds|pds|R1001'"));
        List<UUID> ids = sql.column("select golden_family_id from golden_family order by family_record_key", UUID.class);

        golden.build(run);

        assertEquals(ids, sql.column("select golden_family_id from golden_family order by family_record_key", UUID.class));
        assertEquals(6, sql.count("select count(*) from golden_family_version"), "an unchanged rebuild writes no version");
        assertEquals(0, sql.count("select count(*) from golden_family_event where event_type<>'CREATED'"
                + " and event_type<>'MEMBER_ADDED'"));
        assertEquals(6, sql.count("select (summary->'families'->>'unchanged')::int from golden_build"
                + " order by started_at desc limit 1"));
    }

    @Test
    void aGoldenResidentMergeMovesHouseholdMembersToTheSurvivingIdentity() {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        var loader = SampleData.loader(SampleData.STATE_PACKS);
        var processing = SampleData.processing(connections, loader);
        SampleData.landAll(connections, SampleData.STATE_PACKS).values().forEach(ids -> ids.forEach(processing::processBatch));
        var golden = SampleData.golden(connections, loader);
        golden.build(MatchingRunFixture.completed(connections, List.of()));
        assertEquals(7, sql.count("select count(*) from golden_resident where status='ACTIVE'"));
        List<UUID> familyIds = sql.column("select golden_family_id from golden_family order by family_record_key", UUID.class);

        golden.build(MatchingRunFixture.completed(connections, List.of(Set.copyOf(sql.canonicalsNamed("Ram Singh")))));

        UUID ram = sql.goldenOf(sql.canonicalsNamed("Ram Singh").getFirst());
        assertEquals(familyIds, sql.column("select golden_family_id from golden_family order by family_record_key", UUID.class));
        assertEquals(3, sql.count("select count(*) from golden_family_membership where golden_resident_id=?"
                + " and effective_to is null", ram));
        // Two of Ram's three households pointed at an identity that is now retired into the survivor.
        assertEquals(2, sql.count("select count(*) from golden_family_event where event_type='MEMBER_CHANGED'"
                + " and golden_resident_id=?", ram));
        assertEquals(2, sql.count("select count(*) from golden_family_event e join golden_resident g"
                + " on g.golden_resident_id = (e.detail->>'previousGoldenResidentId')::uuid"
                + " where e.event_type='MEMBER_CHANGED' and g.status='RETIRED' and g.merged_into_id=?", ram));
        assertEquals(2, sql.count("select count(*) from golden_family_version where version_number=2"
                + " and change_reasons @> '[\"MEMBER_CHANGED\"]'"));
        assertEquals(0, sql.count("select count(*) from golden_family_membership m join golden_resident g"
                + " using (golden_resident_id) where m.effective_to is null and g.status <> 'ACTIVE'"));
    }

    @Test
    void movesRetirementAndReactivationKeepEveryHouseholdIdAndItsHistory() {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        StatePackLoader loader = SampleData.loader(SampleData.FAMILY_FIXTURE_PACKS);
        var pack = loader.find("fx-household").orElseThrow();
        var processing = SampleData.processing(connections, loader);
        SampleData.landAll(connections, SampleData.FAMILY_FIXTURE_PACKS).values()
                .forEach(ids -> ids.forEach(processing::processBatch));
        var golden = SampleData.golden(connections, loader);
        golden.build(MatchingRunFixture.completed(connections, List.of()));

        assertEquals(List.of("HH1", "HH2"), sql.column("select source_family_id from golden_family order by 1", String.class));
        assertEquals("HEAD,SPOUSE,CHILD", sql.text("select string_agg(relationship_type, ',' order by resident_record_key)"
                + " from golden_family_membership m join golden_family f using (golden_family_id)"
                + " where f.source_family_id='HH1' and m.effective_to is null"));
        UUID hh1 = family(sql, "HH1");
        UUID hh2 = family(sql, "HH2");

        // Rohan moves to HH2 and Meena (who named no household) now names HH2.
        redeliver(connections, pack, processing, golden, moves("HH2", "HH2", "HH2"));
        assertEquals(List.of("H1-1", "H1-2"), members(sql, hh1));
        assertEquals(List.of("H1-3", "H2-1", "H2-2"), members(sql, hh2));
        assertEquals(2, sql.count("select version_number from golden_family_version where golden_family_id=?"
                + " and effective_to is null", hh1));
        assertEquals(1, sql.count("select count(*) from golden_family_event where golden_family_id=?"
                + " and event_type='MEMBER_REMOVED' and resident_record_key like '%|H1-3'", hh1));
        assertEquals(2, sql.count("select count(*) from golden_family_event where golden_family_id=?"
                + " and event_type='MEMBER_ADDED' and version_number=2", hh2));
        // Version 1 of HH1 still shows Rohan.
        assertEquals(3, sql.count("select count(*) from golden_family_membership where golden_family_id=?"
                + " and added_in_version<=1 and (removed_in_version is null or removed_in_version>1)", hh1));

        // Everyone in HH2 moves to HH9: HH2 retires, HH9 is a new identity.
        redeliver(connections, pack, processing, golden, moves("HH9", "HH9", "HH9"));
        assertEquals("RETIRED", sql.text("select status from golden_family where golden_family_id=?", hh2));
        assertEquals(0, sql.count("select count(*) from golden_family_version where golden_family_id=?"
                + " and effective_to is null", hh2));
        UUID hh9 = family(sql, "HH9");
        assertNotEquals(hh2, hh9);

        // HH2 is named again: same ID, next version, REACTIVATED.
        redeliver(connections, pack, processing, golden, moves("HH9", "HH9", "HH2"));
        assertEquals("ACTIVE", sql.text("select status from golden_family where golden_family_id=?", hh2));
        assertEquals(3, sql.count("select version_number from golden_family_version where golden_family_id=?"
                + " and effective_to is null", hh2));
        assertEquals(List.of("H2-1"), members(sql, hh2));
        assertEquals(1, sql.count("select count(*) from golden_family_event where golden_family_id=?"
                + " and event_type='REACTIVATED'", hh2));
        assertEquals(1, sql.count("select count(*) from golden_family where source_family_id='HH2'"));
    }

    @Test
    void stateFourHouseholdsAreOnboardedByConfigurationAlone() throws Exception {
        Path packs = Redelivery.copyPacks(Files.createDirectories(work.resolve("packs")),
                SampleData.STATE_PACKS, SampleData.REPOSITORY_ROOT.resolve("m10-state-packs"));
        var harness = new OnboardingHarness(packs);
        var sql = new Sql(harness.connections);
        var processing = SampleData.processing(harness.connections, harness.packs);
        SampleData.landAll(harness.connections, SampleData.STATE_PACKS).values().forEach(ids -> ids.forEach(processing::processBatch));
        SampleData.golden(harness.connections, harness.packs)
                .build(MatchingRunFixture.completed(harness.connections, List.of(Set.copyOf(sql.canonicalsNamed("Ram Singh")))));
        assertEquals(6, sql.count("select count(*) from golden_family where status='ACTIVE'"));

        harness.activate("state-4", "1.0.0");
        harness.autoMatches = () -> List.of(Set.copyOf(sql.canonicalsNamed("Ram Singh")));
        UUID run = harness.onboarding.start("state-4", "1.0.0", "citizen-register", ProcessingMode.INCREMENTAL);
        harness.onboarding.runToCompletion(run);

        assertEquals("COMPLETED", sql.text("select status from onboarding_run where onboarding_run_id=?", run));
        assertEquals(8, sql.count("select count(*) from golden_family where status='ACTIVE'"));
        assertEquals(9, sql.count("select count(*) from golden_family_membership where effective_to is null"));
        UUID ram = sql.goldenOf(sql.canonicalsNamed("Ram Singh").getFirst());
        assertEquals(4, sql.count("select count(*) from golden_family_membership where golden_resident_id=?"
                + " and effective_to is null", ram));
        assertEquals(1, sql.count("select count(*) from golden_family where family_record_key"
                + " = 'ST4|citizen-register|citizen-register|H4-RAM'"));
    }

    /** Re-delivers the fixture source with {@code edit} applied to the ORIGINAL file, then processes and rebuilds. */
    private void redeliver(PostgresConnectionFactory connections, LoadedStatePack pack,
                           CanonicalResidentProcessingService processing, GoldenResidentService golden,
                           UnaryOperator<List<String>> edit) {
        processing.processBatch(Redelivery.land(connections, pack, FX_SOURCE, work, edit));
        golden.build(MatchingRunFixture.completed(connections, List.of()));
    }

    private static UnaryOperator<List<String>> moves(String rohan, String meena, String vikram) {
        return lines -> lines.stream()
                .map(line -> line.startsWith("H1-3,") ? line.replace(",HH1,", "," + rohan + ",") : line)
                .map(line -> line.startsWith("H2-2,") ? line.replace(",12,,W,", ",12," + meena + ",W,") : line)
                .map(line -> line.startsWith("H2-1,") ? line.replace(",HH2,", "," + vikram + ",") : line)
                .toList();
    }

    private static UUID family(Sql sql, String sourceFamilyId) {
        return sql.uuid("select golden_family_id from golden_family where source_family_id=?", sourceFamilyId);
    }

    private static List<String> members(Sql sql, UUID family) {
        return sql.column("select split_part(resident_record_key, '|', 4) from golden_family_membership"
                + " where golden_family_id=? and effective_to is null order by resident_record_key", String.class, family);
    }
}
