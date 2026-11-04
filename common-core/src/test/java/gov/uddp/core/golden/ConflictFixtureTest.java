package gov.uddp.core.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * One person in two fixture states with conflicting name, mobile and district. Each pack's
 * survivorship/fieldPriority decides the winner: fx-alpha claims mobile, fx-beta claims district, both
 * claim name (so the name tie falls back to canonical-ID order); DOB and gender agree.
 */
class ConflictFixtureTest {
    @Test
    void fieldPriorityAndFallbackPickTheConfiguredWinnersWithProvenance() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var loader = SampleData.loader(SampleData.FIXTURE_PACKS);
        var processing = SampleData.processing(connections, loader);
        SampleData.landAll(connections, SampleData.FIXTURE_PACKS).values().forEach(ids -> ids.forEach(processing::processBatch));
        UUID alpha = canonicalId(connections, "FXA", "Ram Singh");
        UUID beta = canonicalId(connections, "FXB", "Ram Singh Yadav");
        UUID run = MatchingRunFixture.completed(connections, List.of(Set.of(alpha, beta)));

        new GoldenResidentService(connections, new ObjectMapper(), loader).build(run);

        var provenance = provenance(connections, run, alpha);
        UUID lowerId = alpha.compareTo(beta) < 0 ? alpha : beta;
        assertEquals(new Field(beta, "\"100002\"", SurvivorshipEngine.CONFIGURED_SOURCE_PRECEDENCE),
                provenance.get("address.districtLGD"));
        assertEquals(new Field(alpha, "\"9000000001\"", SurvivorshipEngine.CONFIGURED_SOURCE_PRECEDENCE),
                provenance.get("mobile"));
        assertEquals(SurvivorshipEngine.DETERMINISTIC_CANONICAL_ID_FALLBACK, provenance.get("name").rule());
        assertEquals(lowerId, provenance.get("name").selected());
        assertEquals(SurvivorshipEngine.ALL_VALUES_AGREE, provenance.get("dateOfBirth").rule());
        assertEquals("\"1980-03-12\"", provenance.get("dateOfBirth").value());
        assertEquals(SurvivorshipEngine.ALL_VALUES_AGREE, provenance.get("gender").rule());
        assertEquals(5, provenance.size());
    }

    record Field(UUID selected, String value, String rule) {
    }

    static UUID canonicalId(PostgresConnectionFactory connections, String stateCode, String name) throws Exception {
        try (var c = connections.open();
                var s = c.prepareStatement("select canonical_resident_id from canonical_resident"
                        + " where state_code=? and canonical_payload->>'name'=?")) {
            s.setString(1, stateCode);
            s.setString(2, name);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getObject(1, UUID.class);
            }
        }
    }

    static Map<String, Field> provenance(PostgresConnectionFactory connections, UUID run, UUID member) throws Exception {
        String sql = "select p.field_path, p.canonical_resident_id, p.selected_value::text, p.rule_applied"
                + " from golden_resident_field_provenance p"
                + " join golden_resident g on g.golden_resident_id=p.golden_resident_id"
                + " join golden_resident_member m on m.golden_resident_id=g.golden_resident_id"
                + " where g.matching_run_id=? and m.canonical_resident_id=?";
        Map<String, Field> fields = new LinkedHashMap<>();
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, run);
            s.setObject(2, member);
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    fields.put(r.getString(1), new Field(r.getObject(2, UUID.class), r.getString(3), r.getString(4)));
                }
            }
        }
        return fields;
    }
}
