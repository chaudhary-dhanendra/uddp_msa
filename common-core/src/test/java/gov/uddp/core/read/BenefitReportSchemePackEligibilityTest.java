package gov.uddp.core.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.read.statepack.ConfiguredStatePackQueryService;
import gov.uddp.core.rules.DecisionTableDmn.DecisionRow;
import gov.uddp.core.rules.RuleSet;
import gov.uddp.core.rules.RuleSetService;
import gov.uddp.core.rules.RuleSetStore;
import gov.uddp.core.scheme.SchemeCatalogue;
import gov.uddp.core.scheme.SchemeEligibilityService;
import gov.uddp.core.scheme.SchemePackService;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The eligible-not-enrolled report follows eligibility approved in Scheme Studio: a scheme whose catalogue entry
 * declares none is reported once its Scheme Pack version in force has an approved eligibility rule set.
 */
class BenefitReportSchemePackEligibilityTest {
    @Test
    void anApprovedSchemePackRuleGivesTheSchemeEligibility() {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        var loader = SampleData.loader(SampleData.STATE_PACKS);
        var processing = SampleData.processing(connections, loader);
        SampleData.landAll(connections, SampleData.STATE_PACKS).values().forEach(ids -> ids.forEach(processing::processBatch));
        SampleData.golden(connections, loader)
                .build(MatchingRunFixture.completed(connections, List.of(Set.copyOf(sql.canonicalsNamed("Ram Singh")))));

        var json = new ObjectMapper().findAndRegisterModules();
        var db = new Db(connections, json);
        var catalogue = new SchemeCatalogue(SampleData.SCHEME_CATALOGUE);
        var goldens = new GoldenVisibility(db);
        var benefits = new BenefitReadService(db, new BatchReadService(db, new ConfiguredStatePackQueryService(loader, db)),
                catalogue, goldens);
        var rules = new RuleSetService(new RuleSetStore(connections));
        var schemes = new SchemePackService(connections, json);
        schemes.importCatalogue(catalogue);
        var reports = new BenefitReportService(db, catalogue, benefits, goldens, java.time.Clock.systemUTC(), schemes,
                new SchemeEligibilityService(connections, json, rules));

        var before = assertThrows(IllegalArgumentException.class,
                () -> reports.eligibleNotEnrolled("DISABILITY-PENSION", "2026-11-02"));
        assertEquals("Scheme DISABILITY-PENSION declares no eligibility", before.getMessage());

        RuleSet rule = rules.createFromTable(new RuleSetService.TableDraft(RuleSet.Type.ELIGIBILITY,
                RuleSet.OwnerType.SCHEME, "DISABILITY-PENSION", LocalDate.of(1970, 1, 1), null, List.of(
                        new DecisionRow("gender = \"MALE\"", "ELIGIBLE", "GO para 1"))), "maker");
        rules.submit(rule.id(), "maker");
        rules.approve(rule.id(), "checker", null);
        var v1 = schemes.history("DISABILITY-PENSION").get(0);
        var v2 = schemes.newVersion("DISABILITY-PENSION", v1.id(), "maker");
        schemes.setEligibilityRuleSet(v2.id(), rule.id());
        schemes.submit("DISABILITY-PENSION", v2.id(), "maker");
        schemes.approve("DISABILITY-PENSION", v2.id(), "checker", null);

        var report = reports.eligibleNotEnrolled("DISABILITY-PENSION", "2026-11-02");
        assertEquals(1, report.schemes().size());
        var result = report.schemes().get(0);
        assertEquals("DISABILITY-PENSION", result.schemeId());
        long men = sql.count("select count(*) from golden_resident g join golden_resident_version v"
                + " on v.golden_resident_id = g.golden_resident_id and v.effective_to is null"
                + " where g.status = 'ACTIVE' and v.golden_payload->>'gender' = 'MALE'");
        assertTrue(men > 0);
        assertEquals(men, (long) result.eligible(), "the approved rule decides, not the catalogue");
        assertTrue(reports.eligibleNotEnrolled(null, "2026-11-02").schemes().stream()
                .anyMatch(s -> s.schemeId().equals("DISABILITY-PENSION")), "listed among every scheme with eligibility");
    }
}
