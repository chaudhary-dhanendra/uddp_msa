package gov.uddp.core.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.uddp.core.testsupport.Redelivery;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Canonical Family observations come from each pack's declarative Family rules during canonical processing, are
 * append-only and idempotent, never affect Residents, and the latest delivery per resident decides its households.
 */
class CanonicalFamilyProcessingTest {
    @TempDir
    Path work;

    @Test
    void samplePacksObserveEveryResidentsHouseholdWithoutChangingResidentProcessing() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var service = SampleData.processing(connections, SampleData.loader(SampleData.STATE_PACKS));
        var batches = SampleData.landAll(connections, SampleData.STATE_PACKS);
        batches.values().forEach(ids -> ids.forEach(service::processBatch));

        assertEquals(7, count(connections, "select count(*) from canonical_resident"));
        assertEquals(7, count(connections, "select count(*) from canonical_family_observation"));
        assertEquals(7, count(connections, "select count(*) from canonical_family_membership"));
        assertEquals(6, count(connections, "select count(distinct family_record_key) from canonical_family_membership_current"));
        assertEquals(0, count(connections, "select count(*) from canonical_family_processing_error"));
        assertEquals("Ram Singh,Sita Singh", text(connections, "select string_agg(c.canonical_payload->>'name', ','"
                + " order by c.canonical_payload->>'name') from canonical_family_membership_current m"
                + " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id"
                + " where m.family_record_key = 'ST1|pds|pds|R1001'"));
        assertEquals(7, count(connections, "select count(*) from canonical_family_membership_current"
                + " where relationship_type = 'UNKNOWN'"));
        // Every observation keeps lineage to its raw record and to the canonical Resident of the same delivery.
        assertEquals(0, count(connections, "select count(*) from canonical_family_observation o"
                + " join canonical_resident c on c.canonical_resident_id = o.canonical_resident_id"
                + " where c.raw_record_id <> o.raw_record_id"));

        // Processing the same batches again writes nothing.
        batches.values().forEach(ids -> ids.forEach(service::processBatch));
        assertEquals(7, count(connections, "select count(*) from canonical_family_observation"));
        assertEquals(7, count(connections, "select count(*) from canonical_family_membership"));
    }

    @Test
    void relationshipsExtensionsBlankHouseholdsAndFamilyErrorsFollowTheFixturePack() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var loader = SampleData.loader(SampleData.FAMILY_FIXTURE_PACKS);
        var service = SampleData.processing(connections, loader);
        SampleData.landAll(connections, SampleData.FAMILY_FIXTURE_PACKS).values()
                .forEach(ids -> ids.forEach(service::processBatch));

        // A Family error never rejects the Resident: all six members are canonical.
        assertEquals(6, count(connections, "select count(*) from canonical_resident"));
        assertEquals(1, count(connections, "select count(*) from canonical_family_processing_error"
                + " where error_message like '%COUSIN%'"));
        assertEquals(5, count(connections, "select count(*) from canonical_family_observation"));
        assertEquals(4, count(connections, "select count(*) from canonical_family_membership"));
        assertEquals("HEAD,SPOUSE,CHILD", text(connections, "select string_agg(relationship_type, ',' order by"
                + " resident_record_key) from canonical_family_membership_current where source_family_id = 'HH1'"));
        assertEquals("AAY", text(connections, "select family_payload#>>'{extensions,rationCategory}'"
                + " from canonical_family_membership_current where resident_record_key = 'FXH|household-register|"
                + "household-register|H1-1'"));
        // Meena's delivery names no household: an observation with no membership.
        assertEquals(1, count(connections, "select count(*) from canonical_family_observation o"
                + " where o.resident_record_key like '%|H2-2' and not exists (select 1 from canonical_family_membership m"
                + " where m.canonical_family_observation_id = o.canonical_family_observation_id)"));
    }

    @Test
    void theLatestDeliveryDecidesTheHouseholdAndEarlierObservationsStay() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var loader = SampleData.loader(SampleData.FAMILY_FIXTURE_PACKS);
        var service = SampleData.processing(connections, loader);
        SampleData.landAll(connections, SampleData.FAMILY_FIXTURE_PACKS).values()
                .forEach(ids -> ids.forEach(service::processBatch));
        var pack = loader.find("fx-household").orElseThrow();

        // Rohan moves to HH2 (his Resident values are unchanged); Meena now names HH2 too.
        UUID batch = Redelivery.land(connections, pack, "household-register", work, lines -> lines.stream()
                .map(line -> line.startsWith("H1-3,") ? line.replace(",HH1,", ",HH2,") : line)
                .map(line -> line.startsWith("H2-2,") ? line.replace(",12,,W,", ",12,HH2,W,") : line)
                .toList());
        service.processBatch(batch);

        // Unmapped source fields are kept in Resident extensions, so Rohan's and Meena's Residents get version 2;
        // the four unchanged re-deliveries write no canonical row.
        assertEquals(8, count(connections, "select count(*) from canonical_resident"));
        assertEquals(List.of("H1-1", "H1-2"), keys(connections, "HH1"));
        assertEquals(List.of("H1-3", "H2-1", "H2-2"), keys(connections, "HH2"));
        assertEquals(10, count(connections, "select count(*) from canonical_family_observation"),
                "every delivery is observed; nothing is replaced");
        assertEquals(5, count(connections, "select count(*) from canonical_family_membership"
                + " where source_family_id = 'HH1'"), "HH1's earlier memberships stay as history");
    }

    private static List<String> keys(PostgresConnectionFactory connections, String household) throws Exception {
        String joined = text(connections, "select string_agg(split_part(resident_record_key, '|', 4), ',' order by"
                + " resident_record_key) from canonical_family_membership_current where source_family_id = '" + household + "'");
        return List.of(joined.split(","));
    }

    static long count(PostgresConnectionFactory connections, String sql) throws Exception {
        try (var c = connections.open(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    static String text(PostgresConnectionFactory connections, String sql) throws Exception {
        try (var c = connections.open(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }
}
