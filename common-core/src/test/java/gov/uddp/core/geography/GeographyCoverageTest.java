package gov.uddp.core.geography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.geography.GeographyCoverageService.LevelCoverage;
import gov.uddp.core.read.Db;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Track F5: geography coverage per source and level counts resolved (including derived) and UNKNOWN values against the
 * source's current Residents, filters by state and source, and shows no row for a level a source never supplies.
 */
class GeographyCoverageTest {
    @Test
    void coveragePerSourceAndLevel() {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        var loader = SampleData.loader(SampleData.STATE_PACKS);
        var processing = SampleData.processing(connections, loader);
        SampleData.landAll(connections, SampleData.STATE_PACKS).values().forEach(ids -> ids.forEach(processing::processBatch));

        // One source's Residents: two resolved districts (one derived from a village), one unknown; villages unknown.
        String state = sql.text("select state_code from canonical_resident_current group by state_code, source_system"
                + " having count(*) >= 3 order by state_code, source_system limit 1");
        String source = sql.text("select source_system from canonical_resident_current where state_code=?"
                + " group by source_system having count(*) >= 3 order by source_system limit 1", state);
        List<UUID> ids = sql.column("select canonical_resident_id from canonical_resident_current where state_code=?"
                + " and source_system=? order by canonical_resident_id", UUID.class, state, source);
        long records = ids.size();
        set(sql, ids.get(0), "{\"district\":{\"lgd\":\"901\",\"status\":\"ACTIVE_VALID\"},"
                + "\"village\":{\"raw\":\"Rivrbend\",\"status\":\"UNKNOWN\"},\"pincode\":{\"value\":\"900001\"}}");
        set(sql, ids.get(1), "{\"village\":{\"lgd\":\"500001\",\"status\":\"ACTIVE_VALID\"},"
                + "\"district\":{\"lgd\":\"901\",\"status\":\"ACTIVE_VALID\"}}");
        set(sql, ids.get(2), "{\"district\":{\"raw\":\"Nowhere\",\"status\":\"UNKNOWN\"}}");

        var service = new GeographyCoverageService(new Db(connections, new ObjectMapper()));
        List<LevelCoverage> all = service.coverage(null, null);
        assertEquals(List.of("district", "village"), all.stream().map(LevelCoverage::level).toList(),
                "only levels some source supplies; pincode is not a level");
        LevelCoverage district = all.getFirst();
        assertEquals(state, district.stateCode());
        assertEquals(source, district.sourceSystem());
        assertEquals(records, district.records());
        assertEquals(3, district.supplied());
        assertEquals(2, district.resolved());
        assertEquals(1, district.unknown());
        assertEquals(Math.round(2 * 1000.0 / records) / 10.0, district.resolvedPercent());
        assertEquals(Math.round(1000.0 / records) / 10.0, district.unknownPercent());
        LevelCoverage village = all.get(1);
        assertEquals(1, village.resolved());
        assertEquals(1, village.unknown());

        assertEquals(all, service.coverage(state, source));
        assertTrue(service.coverage(state, "no-such-source").isEmpty());
        assertTrue(service.coverage("ZZ", null).isEmpty());
    }

    private static void set(Sql sql, UUID id, String geography) {
        sql.execute("update canonical_resident set canonical_payload = jsonb_set(canonical_payload, '{extensions}',"
                + " coalesce(canonical_payload->'extensions','{}'::jsonb) || jsonb_build_object('uddp.geography', ?::jsonb))"
                + " where canonical_resident_id=?", geography, id);
    }
}
