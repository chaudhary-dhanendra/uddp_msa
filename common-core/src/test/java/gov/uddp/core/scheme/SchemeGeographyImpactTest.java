package gov.uddp.core.scheme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.rules.DecisionTableDmn.DecisionRow;
import gov.uddp.core.rules.RuleSet;
import gov.uddp.core.rules.RuleSetService;
import gov.uddp.core.rules.RuleSetStore;
import gov.uddp.core.scheme.SchemeImpactService.GeographyDimension;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Track F6: scheme impact grouped by a requested geography level read from the members' resolved canonical geography,
 * with a data-availability caveat for Residents lacking that level (never shown as zero), while the district path is
 * unchanged.
 */
class SchemeGeographyImpactTest {
    @Test
    void impactByRequestedLevelWithCaveat() {
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
        List<UUID> ram = sql.canonicalsNamed("Ram Singh");
        SampleData.golden(connections, loader).build(MatchingRunFixture.completed(connections, List.of(Set.copyOf(ram))));

        // Ram Singh's records sit in one block and village; one other Resident only in another block.
        UUID other = sql.uuid("select canonical_resident_id from canonical_resident_current where canonical_payload->>'name'"
                + " <> 'Ram Singh' order by canonical_resident_id limit 1");
        ram.forEach(id -> setGeography(sql, id, "{\"block\":{\"lgd\":\"90101\",\"status\":\"ACTIVE_VALID\"},"
                + "\"village\":{\"lgd\":\"500001\",\"status\":\"ACTIVE_VALID\"}}"));
        setGeography(sql, other, "{\"block\":{\"lgd\":\"90201\",\"status\":\"ACTIVE_VALID\"},"
                + "\"village\":{\"raw\":\"Nowhere\",\"status\":\"UNKNOWN\"}}");

        var base = impact.impact(approved);
        var blocks = impact.impact(approved, GeographyDimension.BLOCK);
        assertEquals(Set.of("90101", "90201"), blocks.byGeography().keySet());
        assertEquals(2, blocks.geographyAvailable());
        assertEquals(base.evaluated() - 2, blocks.geographyUnavailable());
        assertNotNull(blocks.caveat());
        assertTrue(blocks.caveat().contains("not counted as zero"), blocks.caveat());
        assertEquals(1, blocks.byGeography().get("90101").enrolled(), "Ram Singh's pension counts in his block");

        var villages = impact.impact(approved, GeographyDimension.VILLAGE);
        assertEquals(Set.of("500001"), villages.byGeography().keySet(), "an UNKNOWN village is unavailable, not an area");
        assertEquals(base.evaluated() - 1, villages.geographyUnavailable());

        var districts = impact.impact(approved, GeographyDimension.DISTRICT);
        assertEquals(base, districts.impact(), "the district path is unchanged");
        var withoutUnknown = new java.util.TreeMap<>(base.byDistrict());
        int unknownDistricts = withoutUnknown.containsKey("UNKNOWN") ? withoutUnknown.remove("UNKNOWN").eligible() : 0;
        assertEquals(withoutUnknown, new java.util.TreeMap<>(districts.byGeography()));
        if (districts.geographyUnavailable() == 0) {
            assertNull(districts.caveat());
            assertEquals(0, unknownDistricts);
        }
    }

    private static void setGeography(Sql sql, UUID id, String geography) {
        sql.execute("update canonical_resident set canonical_payload = jsonb_set(canonical_payload, '{extensions}',"
                + " coalesce(canonical_payload->'extensions','{}'::jsonb) || jsonb_build_object('uddp.geography', ?::jsonb))"
                + " where canonical_resident_id=?", geography, id);
    }
}
