package gov.uddp.core.canonical;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.uddp.core.testsupport.Redelivery;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Canonical Benefit observations (M15) come from each pack's declarative Benefit rules during canonical processing,
 * with scheme codes crosswalked onto the platform catalogue; they are append-only and idempotent, never affect
 * Residents or Families, and the latest delivery per resident decides its current benefit.
 */
class CanonicalBenefitProcessingTest {
    @TempDir
    Path work;

    @Test
    void samplePacksObserveEveryResidentsBenefitWithoutChangingResidentOrFamilyProcessing() {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        var service = SampleData.processing(connections, SampleData.loader(SampleData.STATE_PACKS));
        var batches = SampleData.landAll(connections, SampleData.STATE_PACKS);
        batches.values().forEach(ids -> ids.forEach(service::processBatch));

        assertEquals(7, sql.count("select count(*) from canonical_resident"));
        assertEquals(7, sql.count("select count(*) from canonical_family_observation"));
        assertEquals(7, sql.count("select count(*) from canonical_benefit_observation"));
        assertEquals(6, sql.count("select count(*) from canonical_benefit_current"));
        assertEquals(5, sql.count("select count(*) from benefit"));
        assertEquals(0, sql.count("select count(*) from canonical_benefit_processing_error"));
        // Two source codes (PHH, AAY) crosswalk onto one platform scheme; R1001 is one benefit named by two members.
        assertEquals("OLD-AGE-PENSION:3,PDS-RATION:3", sql.text("select string_agg(scheme_id || ':' || n, ','"
                + " order by scheme_id) from (select scheme_id, count(*) n from canonical_benefit_current"
                + " group by scheme_id) x"));
        assertEquals("AAY,PHH", sql.text("select string_agg(distinct source_scheme_code, ',' order by"
                + " source_scheme_code) from canonical_benefit_current where scheme_id = 'PDS-RATION'"));
        assertEquals("Ram Singh,Sita Singh", sql.text("select string_agg(c.canonical_payload->>'name', ','"
                + " order by c.canonical_payload->>'name') from canonical_benefit_current b"
                + " join canonical_resident c on c.canonical_resident_id = b.canonical_resident_id"
                + " where b.source_benefit_id = 'R1001'"));
        assertEquals("ST1|pds|pds|R1001", sql.text("select distinct family_record_key from canonical_benefit_current"
                + " where source_benefit_id = 'R1001'"));
        assertEquals("FAMILY", sql.text("select distinct beneficiary_level from canonical_benefit_current"
                + " where scheme_id = 'PDS-RATION'"));
        assertEquals(2, sql.count("select count(*) from canonical_benefit_current where source_benefit_id = 'R1001'"
                + " and entitlement = '{\"quantity\": 25, \"unit\": \"KG\", \"frequency\": \"MONTHLY\"}'::jsonb"));
        assertEquals("1.0.0", sql.text("select distinct scheme_catalogue_version from canonical_benefit_observation"));
        // Sunita Devi's delivery names no benefit: an observation without one.
        assertEquals(1, sql.count("select count(*) from canonical_benefit_observation o join canonical_resident c"
                + " on c.canonical_resident_id = o.canonical_resident_id where o.benefit_id is null"
                + " and c.canonical_payload->>'name' = 'Sunita Devi'"));
        // Ram and Sita report the same R1001 lifting: one disbursement. Every benefit has one.
        assertEquals(5, sql.count("select count(*) from canonical_benefit_disbursement"));
        assertEquals(0, sql.count("select count(*) from benefit b where not exists (select 1 from"
                + " canonical_benefit_disbursement d where d.benefit_id = b.benefit_id)"));
        // Fields only Benefit rules read are benefit data, not Resident extensions; other unmapped fields still are.
        assertEquals(0, sql.count("select count(*) from canonical_resident where jsonb_exists_any(canonical_payload->'extensions',"
                + " array['pension_order_no', 'last_paid_on', 'card_status', 'welfare_id', 'monthly_amount'])"));
        assertEquals("B+", sql.text("select canonical_payload#>>'{extensions,blood_group}' from canonical_resident"
                + " where source_system = 'beneficiaries' and canonical_payload->>'name' = 'Ram Singh'"));
        // Lineage: every observation points at the raw record and canonical Resident of the same delivery.
        assertEquals(0, sql.count("select count(*) from canonical_benefit_observation o"
                + " join canonical_resident c on c.canonical_resident_id = o.canonical_resident_id"
                + " where c.raw_record_id <> o.raw_record_id"));

        // Processing the same batches again writes nothing.
        batches.values().forEach(ids -> ids.forEach(service::processBatch));
        assertEquals(7, sql.count("select count(*) from canonical_benefit_observation"));
        assertEquals(5, sql.count("select count(*) from benefit"));
        assertEquals(5, sql.count("select count(*) from canonical_benefit_disbursement"));
    }

    @Test
    void benefitErrorsAreRecordedWithoutRejectingResidentsOrFamilies() {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        var service = SampleData.processing(connections, SampleData.loader(SampleData.BENEFIT_FIXTURE_PACKS));
        SampleData.landAll(connections, SampleData.BENEFIT_FIXTURE_PACKS).values()
                .forEach(ids -> ids.forEach(service::processBatch));

        assertEquals(8, sql.count("select count(*) from canonical_resident"));
        assertEquals(8, sql.count("select count(*) from canonical_family_observation"));
        assertEquals(3, sql.count("select count(*) from canonical_benefit_processing_error"));
        for (String reason : new String[] {"GHOST-SCHEME is not in scheme catalogue",
                "sourceFamilyId is required", "Not a number for"}) {
            assertEquals(1, sql.count("select count(*) from canonical_benefit_processing_error where error_message"
                    + " like ?", "%" + reason + "%"), reason);
        }
        // XYZ is not in the crosswalk: kept as UNMAPPED with a warning, not rejected.
        assertEquals(5, sql.count("select count(*) from canonical_benefit_observation"));
        assertEquals(4, sql.count("select count(*) from canonical_benefit_current"));
        assertEquals("XYZ", sql.text("select source_code from canonical_benefit_warning where resolved_at is null"));
        assertEquals("DISABILITY-PENSION,OLD-AGE-PENSION,PDS-RATION,UNMAPPED", sql.text("select string_agg(scheme_id, ','"
                + " order by scheme_id) from benefit"));
        assertEquals("SUSPENDED", sql.text("select benefit_status from canonical_benefit_current"
                + " where source_benefit_id = 'R-6'"));
        assertEquals("TX-1,TX-2,TX-3", sql.text("select string_agg(disbursement_reference, ',' order by"
                + " disbursement_reference) from canonical_benefit_disbursement"));
    }

    @Test
    void mappingTheCodeLaterReprocessesOnlyTheUnmappedRows() {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        var loader = SampleData.loader(SampleData.BENEFIT_FIXTURE_PACKS);
        var service = SampleData.processing(connections, loader);
        SampleData.landAll(connections, SampleData.BENEFIT_FIXTURE_PACKS).values()
                .forEach(ids -> ids.forEach(service::processBatch));
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var processor = new CanonicalBenefitProcessor(connections, new gov.uddp.core.mapping.BenefitMappingEngine(
                new gov.uddp.core.mapping.RawCsvRecordParser(json),
                new gov.uddp.core.scheme.SchemeCatalogue(SampleData.SCHEME_CATALOGUE)), json);
        var raws = new PostgresRawRecordRepository(connections, json);
        var pack = loader.all().stream().filter(p -> p.crosswalks().containsKey("scheme")).findFirst().orElseThrow();

        // Still unmapped: nothing to reprocess into a scheme, and nothing duplicated.
        assertEquals(0, processor.reprocessUnmapped(pack, raws));
        assertEquals(5, sql.count("select count(*) from canonical_benefit_observation"));

        var crosswalks = new java.util.HashMap<>(pack.crosswalks());
        var values = new java.util.HashMap<>(pack.crosswalks().get("scheme").values());
        values.put("XYZ", "OLD-AGE-PENSION");
        crosswalks.put("scheme", new gov.uddp.core.statepack.StatePackAssets.CrosswalkDefinition("scheme", values));
        var mappedPack = new gov.uddp.core.statepack.LoadedStatePack(pack.manifest(), pack.sources(), pack.mappings(),
                crosswalks, pack.validations(), pack.matching(), pack.survivorship());

        assertEquals(1, processor.reprocessUnmapped(mappedPack, raws));
        assertEquals(6, sql.count("select count(*) from canonical_benefit_observation"), "only the one row was reprocessed");
        assertEquals(0, sql.count("select count(*) from canonical_benefit_warning where resolved_at is null"));
        assertEquals("OLD-AGE-PENSION", sql.text("select scheme_id from canonical_benefit_current where source_benefit_id = 'X-3'"));
        assertEquals(0, sql.count("select count(*) from canonical_benefit_current where scheme_id = 'UNMAPPED'"));
        assertEquals(0, processor.reprocessUnmapped(mappedPack, raws));
    }

    @Test
    void theLatestDeliveryDecidesTheBenefitAndDisbursementsAccumulate() {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        var loader = SampleData.loader(SampleData.STATE_PACKS);
        var service = SampleData.processing(connections, loader);
        SampleData.landAll(connections, SampleData.STATE_PACKS).values()
                .forEach(ids -> ids.forEach(service::processBatch));
        var pension = loader.all().stream().filter(p -> p.sources().stream()
                .anyMatch(s -> s.sourceId().equals("pension"))).findFirst().orElseThrow();
        String benefitId = sql.text("select benefit_id::text from benefit where source_benefit_id = 'PPO-5001'");

        // Next month: PPO-5001 is paid again and Anita's pension is suspended.
        service.processBatch(Redelivery.land(connections, pension, "pension", work, lines -> lines.stream()
                .map(line -> line.replace("20261001,1000", "20261101,1000").replace("PPO-5002,1", "PPO-5002,2"))
                .toList()));

        assertEquals(7, sql.count("select count(*) from canonical_resident"), "Resident content is unchanged");
        assertEquals(9, sql.count("select count(*) from canonical_benefit_observation"));
        assertEquals(5, sql.count("select count(*) from benefit"), "the same benefits keep their IDs");
        assertEquals(benefitId, sql.text("select benefit_id::text from benefit where source_benefit_id = 'PPO-5001'"));
        assertEquals(2, sql.count("select count(*) from canonical_benefit_disbursement d join benefit b"
                + " on b.benefit_id = d.benefit_id where b.source_benefit_id = 'PPO-5001'"));
        assertEquals("SUSPENDED", sql.text("select benefit_status from canonical_benefit_current"
                + " where source_benefit_id = 'PPO-5002'"));
        assertEquals("ACTIVE,SUSPENDED", sql.text("select string_agg(benefit_status, ',' order by observed_at)"
                + " from canonical_benefit_observation where source_benefit_id = 'PPO-5002'"), "history kept");

        // A delivery that names no pension ends Ram Singh's link to PPO-5001; the benefit identity stays.
        service.processBatch(Redelivery.land(connections, pension, "pension", work, Redelivery.replaceIn("Ram Singh",
                "VRIDHA,PPO-5001,1,20250401,1000,20261001,1000", ",,,,,,")));
        assertEquals(0, sql.count("select count(*) from canonical_benefit_current where source_benefit_id = 'PPO-5001'"));
        assertEquals(5, sql.count("select count(*) from benefit"));
    }
}
