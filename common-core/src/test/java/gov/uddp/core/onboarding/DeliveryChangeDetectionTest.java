package gov.uddp.core.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresRawRecordRepository;
import gov.uddp.core.onboarding.DeliveryChangeService.RemovalHandling;
import gov.uddp.core.testsupport.OnboardingHarness;
import gov.uddp.core.testsupport.Redelivery;
import gov.uddp.core.testsupport.SampleData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Track C1: re-deliveries are compared per source-record key and content hash. */
class DeliveryChangeDetectionTest {
    private static final String HEADER = "beneficiary_nm,dob,sex_cd,dist_cd,ration_no,mobile,card_type,card_status,"
            + "card_issued_on,monthly_grain_kg,last_lifted_on,last_lifted_kg";
    private static final String RAM = "Ram Singh,12-03-1980,M,101,R1001,9876543210,PHH,A,01-04-2015,25,05-10-2026,25";
    private static final String SITA = "Sita Singh,14-07-1984,F,101,R1001,9876543211,PHH,A,01-04-2015,25,05-10-2026,25";
    private static final String SITA_CORRECTED = SITA.replace("14-07-1984", "15-07-1984");
    private static final String RAMESH = "Ramesh Kumar,01-01-1975,M,102,R1002,9876543220,AAY,A,15-06-2012,35,07-10-2026,35";
    private static final String GITA = "Gita Devi,02-02-1990,F,101,R1003,9876543230,PHH,A,01-04-2015,25,05-10-2026,25";

    @TempDir
    Path work;

    @Test
    void twoDeliveriesGiveCorrectCountsStableGoldenIdsAndConfiguredRemovalHandling() throws IOException {
        Path packs = Redelivery.copyPacks(work.resolve("packs"), SampleData.STATE_PACKS);
        Path source = work.resolve("pds.csv");
        Path sourceYaml = packs.resolve("state-1/sources").resolve(
                Files.list(packs.resolve("state-1/sources")).findFirst().orElseThrow().getFileName());
        Files.writeString(sourceYaml, Files.readString(sourceYaml).replace("sample-data/state-1/pds.csv",
                source.toAbsolutePath().toString().replace('\\', '/')));

        var harness = new OnboardingHarness(packs).activate("state-1", SampleData.loader(packs).find("state-1").orElseThrow().manifest().version());
        var changes = new DeliveryChangeService(harness.connections, new PostgresRawRecordRepository(
                harness.connections, new ObjectMapper()), harness.packs, new ObjectMapper());
        harness.onboarding.setDeliveryChanges(changes);
        String state = harness.packs.find("state-1").orElseThrow().manifest().stateCode();

        // Delivery 1: everything is new.
        deliver(source, RAM, SITA, RAMESH);
        UUID first = run(harness);
        assertCounts(harness, first, 3, 0, 0, 0);
        int benefitsBefore = harness.count("select count(*) from canonical_benefit_current");
        Map<String, UUID> golden = goldenIds(harness);

        // Delivery 2 (mark only): Sita corrected, Gita added, Ramesh no longer delivered, Ram untouched.
        deliver(source, RAM, SITA_CORRECTED, GITA);
        UUID second = run(harness);
        assertCounts(harness, second, 1, 1, 1, 1);
        assertEquals(1, harness.count("select count(*) from source_record_state where status='REMOVED'"));
        assertEquals(2, harness.count("select count(*) from canonical_resident_version where source_record_key like"
                + " '%Sita Singh'"), "a changed row becomes a new canonical version");
        assertEquals(1, harness.count("select count(*) from canonical_resident_version where source_record_key like"
                + " '%Ram Singh'"), "an unchanged row is skipped");
        assertEquals(benefitsBefore + 1, harness.count("select count(*) from canonical_benefit_current"),
                "marking a removal ends no benefit (Gita's is new)");
        Map<String, UUID> after = goldenIds(harness);
        assertEquals(golden.get("Ram Singh"), after.get("Ram Singh"), "unchanged record keeps its Golden ID");
        assertEquals(golden.get("Sita Singh"), after.get("Sita Singh"), "changed record keeps its Golden ID");

        // Delivery 3 (end benefits): Sita is no longer delivered, so her benefit observation stops being current.
        changes.setRemovalHandling(state, "pds", RemovalHandling.END_BENEFITS);
        int current = harness.count("select count(*) from canonical_benefit_current");
        deliver(source, RAM, GITA);
        UUID third = run(harness);
        assertCounts(harness, third, 0, 0, 2, 1);
        assertEquals(current - 1, harness.count("select count(*) from canonical_benefit_current"));
        assertEquals(1, harness.count("select count(*) from benefit_end_marker"));
        assertEquals(3, ((List<?>) harness.onboarding.history("state-1")).size());
    }

    private UUID run(OnboardingHarness harness) {
        String version = harness.packs.find("state-1").orElseThrow().manifest().version();
        UUID run = harness.onboarding.start("state-1", version, "pds");
        harness.onboarding.runToCompletion(run);
        return run;
    }

    private void deliver(Path file, String... rows) throws IOException {
        var lines = new ArrayList<String>();
        lines.add(HEADER);
        lines.addAll(List.of(rows));
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private void assertCounts(OnboardingHarness harness, UUID run, int fresh, int changed, int unchanged, int removed) {
        @SuppressWarnings("unchecked")
        var counts = (Map<String, Object>) harness.onboarding.detail(run).get("deliveryChanges");
        assertEquals(Map.of("new", fresh, "changed", changed, "unchanged", unchanged, "removed", removed), counts);
    }

    private Map<String, UUID> goldenIds(OnboardingHarness harness) {
        return harness.query("select split_part(source_record_key,'|',5), golden_resident_id"
                + " from golden_resident_membership where effective_to is null", r -> {
            var ids = new java.util.HashMap<String, UUID>();
            try {
                while (r.next()) {
                    ids.put(r.getString(1), r.getObject(2, UUID.class));
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            return ids;
        });
    }
}
