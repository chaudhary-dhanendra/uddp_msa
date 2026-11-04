package gov.uddp.core.scheme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.golden.GoldenBuildCompletedEvent;
import gov.uddp.core.rules.DecisionTableDmn.DecisionRow;
import gov.uddp.core.rules.RuleSet;
import gov.uddp.core.rules.RuleSetService;
import gov.uddp.core.rules.RuleSetStore;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Track A5: data availability per attribute, an impact preview that only reads (enrolment, gaps, overlaps, by
 * district) and eligibility recomputed with stored explanations after every Golden build.
 */
class SchemeImpactServiceTest {
    @Test
    void availabilityImpactAndRecomputeAfterGoldenBuild() {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        var loader = SampleData.loader(SampleData.STATE_PACKS);
        var processing = SampleData.processing(connections, loader);
        SampleData.landAll(connections, SampleData.STATE_PACKS).values().forEach(ids -> ids.forEach(processing::processBatch));

        var json = new ObjectMapper();
        var rules = new RuleSetService(new RuleSetStore(connections));
        var schemes = new SchemePackService(connections, json);
        var eligibility = new SchemeEligibilityService(connections, json, rules);
        var impact = new SchemeImpactService(connections, json, rules, schemes, eligibility, loader);
        schemes.importCatalogue(new SchemeCatalogue(SampleData.SCHEME_CATALOGUE));

        RuleSet rule = rules.createFromTable(new RuleSetService.TableDraft(RuleSet.Type.ELIGIBILITY,
                RuleSet.OwnerType.SCHEME, "OLD-AGE-PENSION", LocalDate.of(1970, 1, 1), null, List.of(
                        new DecisionRow("gender = \"MALE\"", "ELIGIBLE", "GO para 1"))), "maker");
        rules.submit(rule.id(), "maker");
        rules.approve(rule.id(), "checker", null);
        var v1 = schemes.history("OLD-AGE-PENSION").get(0);
        var v2 = schemes.newVersion("OLD-AGE-PENSION", v1.id(), "maker");
        schemes.setEligibilityRuleSet(v2.id(), rule.id());
        schemes.submit("OLD-AGE-PENSION", v2.id(), "maker");
        var approved = schemes.approve("OLD-AGE-PENSION", v2.id(), "checker", null);

        var golden = SampleData.golden(connections, loader);
        golden.setEvents(event -> impact.afterGoldenBuild((GoldenBuildCompletedEvent) event));
        golden.build(MatchingRunFixture.completed(connections, List.of(Set.copyOf(sql.canonicalsNamed("Ram Singh")))));

        var latest = impact.latest("OLD-AGE-PENSION", 100).orElseThrow(() -> new AssertionError("recomputed after build"));
        assertEquals(5, latest.computation().evaluated(), "every active Golden Resident");
        assertEquals(latest.computation().evaluated(), latest.results().size());
        assertTrue(latest.computation().eligible() > 0);
        assertTrue(latest.results().stream().filter(r -> "ELIGIBLE".equals(r.outcome()))
                .allMatch(r -> r.clauseReferences().equals(List.of("GO para 1")) && r.explanation() != null));

        var availability = impact.dataAvailability(approved);
        assertEquals(List.of("gender"), availability.attributes().stream().map(a -> a.attribute()).toList());
        var gender = availability.attributes().get(0);
        assertEquals(5, gender.population());
        assertFalse(gender.suppliedBy().isEmpty(), "the packs that map gender");

        var preview = impact.impact(approved);
        assertEquals(5, preview.evaluated());
        assertEquals(latest.computation().eligible(), preview.totals().eligible());
        assertTrue(preview.totals().enrolled() >= 1, "Ram Singh holds the pension");
        assertEquals(preview.totals().eligible() - (preview.totals().enrolled() - preview.totals().enrolledNotEligible()),
                preview.totals().eligibleNotEnrolled());
        assertEquals(preview.totals().eligible(), preview.byDistrict().values().stream().mapToInt(c -> c.eligible()).sum());
        assertEquals(preview.totals().enrolled(), preview.byDistrict().values().stream().mapToInt(c -> c.enrolled()).sum());
        assertFalse(preview.byDistrict().isEmpty());
        assertEquals(1, impact.latest("OLD-AGE-PENSION", 100).stream().count(), "the preview stored nothing new");
        assertEquals(1, sql.count("select count(*) from scheme_eligibility_computation"));
    }
}
