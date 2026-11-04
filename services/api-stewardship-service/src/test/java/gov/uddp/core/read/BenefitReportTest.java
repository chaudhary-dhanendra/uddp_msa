package gov.uddp.core.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.read.statepack.ConfiguredStatePackQueryService;
import gov.uddp.core.scheme.SchemeCatalogue;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * M15 benefit links and reports over current Golden identities: duplicates follow stewardship splits and merges,
 * two schemes of one exclusivity group are a duplicate, suspended benefits are not, and eligibility counts residents
 * enrolled anywhere in the scheme's group.
 */
class BenefitReportTest {
    private PostgresConnectionFactory connections;
    private Sql sql;
    private BenefitReadService benefits;
    private BenefitReportService reports;

    private void setUp(Path packs) {
        connections = TestDatabase.freshMigrated();
        sql = new Sql(connections);
        var loader = SampleData.loader(packs);
        var processing = SampleData.processing(connections, loader);
        SampleData.landAll(connections, packs).values().forEach(ids -> ids.forEach(processing::processBatch));
        services(loader);
    }

    private void services(StatePackLoader loader) {
        var db = new Db(connections, new ObjectMapper().findAndRegisterModules());
        var catalogue = new SchemeCatalogue(SampleData.SCHEME_CATALOGUE);
        var goldens = new GoldenVisibility(db);
        benefits = new BenefitReadService(db, new BatchReadService(db, new ConfiguredStatePackQueryService(loader, db)),
                catalogue, goldens);
        reports = new BenefitReportService(db, catalogue, benefits, goldens);
    }

    @Test
    void theSeededDuplicateFollowsStewardshipSplitsAndMerges() {
        setUp(SampleData.STATE_PACKS);
        var loader = SampleData.loader(SampleData.STATE_PACKS);
        var golden = SampleData.golden(connections, loader);
        var stewardship = SampleData.stewardship(connections, golden);
        assertEquals(0, reports.duplicates().findingCount(), "benefits link to Golden Residents only after a build");

        golden.build(MatchingRunFixture.completed(connections, List.of(Set.copyOf(sql.canonicalsNamed("Ram Singh")))));
        UUID ram = sql.goldenOf(sql.canonicalsNamed("Ram Singh").getFirst());
        var report = reports.duplicates();
        assertEquals(1, report.findingCount());
        var finding = report.findings().getFirst();
        assertEquals(ram, finding.goldenResidentId());
        assertEquals("SOCIAL-PENSION", finding.exclusivityGroup());
        assertEquals(List.of("OLD-AGE-PENSION"), finding.schemeIds());
        assertEquals(2, finding.stateCodes().size(), "the same scheme from two states");
        assertEquals(2, finding.benefitCount());

        // Splitting one pension's resident record off Ram Singh moves that pension to the new identity: no duplicate.
        String pensionRecord = sql.text("select resident_record_key from canonical_benefit_current"
                + " where scheme_id = 'OLD-AGE-PENSION' and resident_record_key in (select source_record_key from"
                + " golden_resident_membership where golden_resident_id = ? and effective_to is null)"
                + " order by resident_record_key desc limit 1", ram);
        var split = stewardship.split(ram, List.of(pensionRecord), "Asha", "Different father's name");
        assertEquals(0, reports.duplicates().findingCount());
        assertEquals(2, benefits.benefitsOf(ram).benefitCount(), "PDS and one pension stay with Ram Singh");
        UUID other = sql.uuid("select golden_resident_id from golden_resident_membership where source_record_key = ?"
                + " and effective_to is null", pensionRecord);
        assertEquals(List.of("OLD-AGE-PENSION"), benefits.benefitsOf(other).schemes());
        assertEquals(1, split.goldenBuild().split());

        // Merging them back restores the duplicate under the original Golden ID; the retired ID holds nothing.
        stewardship.merge(List.of(other, ram), "Asha", "Father's name was a typo");
        assertEquals(ram, reports.duplicates().findings().getFirst().goldenResidentId());
        assertEquals("RETIRED", benefits.benefitsOf(other).status());
        assertEquals(0, benefits.benefitsOf(other).benefitCount());
    }

    @Test
    void twoSchemesOfOneGroupAreADuplicateAndSuspendedOrFailedBenefitsAreNot() {
        setUp(SampleData.BENEFIT_FIXTURE_PACKS);
        var golden = SampleData.golden(connections, SampleData.loader(SampleData.BENEFIT_FIXTURE_PACKS));
        golden.build(MatchingRunFixture.completed(connections, List.of(Set.copyOf(sql.canonicalsNamed("Asha Rao")))));

        var report = reports.duplicates();
        assertEquals(1, report.findingCount());
        var asha = report.findings().getFirst();
        assertEquals("Asha Rao", asha.name());
        assertEquals("SOCIAL-PENSION", asha.exclusivityGroup());
        assertEquals(List.of("DISABILITY-PENSION", "OLD-AGE-PENSION"), asha.schemeIds());

        // Esha Nair's PDS card is suspended: listed as a benefit, but not active.
        UUID esha = sql.goldenOf(sql.canonicalsNamed("Esha Nair").getFirst());
        var held = benefits.benefitsOf(esha);
        assertEquals(1, held.benefitCount());
        assertEquals("SUSPENDED", held.benefits().getFirst().benefit().status());
        assertEquals(List.of("FAMILY", "RESIDENT"), held.benefits().getFirst().linkedVia());
        assertNull(held.benefits().getFirst().benefit().lastDisbursement());

        // Old age pension eligibility (45+ in the sample catalogue) on 2026-10-25: Asha is enrolled through either
        // pension of the group; Bina, Chand and Gita are eligible but their pension rows were Benefit errors, and
        // Dev Nair (46) holds no pension.
        var eligibility = reports.eligibleNotEnrolled("OLD-AGE-PENSION", "2026-10-25").schemes().getFirst();
        assertEquals(7, eligibility.residentsEvaluated(), "8 canonical residents, Asha's two records are one Golden");
        assertEquals(5, eligibility.eligible());
        assertEquals(1, eligibility.enrolled());
        assertEquals(0, eligibility.undetermined());
        assertEquals(List.of("Bina Iyer", "Chand Pal", "Dev Nair", "Gita Das"),
                eligibility.notEnrolled().stream().map(BenefitReportService.NotEnrolledResident::name).toList());
        assertTrue(eligibility.notEnrolled().stream().allMatch(n -> n.ageYears() >= 45));
    }
}
