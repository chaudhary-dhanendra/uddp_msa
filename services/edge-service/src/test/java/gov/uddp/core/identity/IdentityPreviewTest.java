package gov.uddp.core.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.api.IdentityController;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Track E5: the Studio Identity step's preview endpoint runs the real evaluator over the current canonical records and
 * the latest matching run, reports merges, conflicts, never-merge blocks and coverage, and changes nothing.
 */
class IdentityPreviewTest {
    private static final Path SEED = Path.of("../../platform/identity/identifier-types.yaml");

    @Test
    void previewReportsThePolicyImpactWithoutChangingAnything() {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        UUID run = SampleData.processedSampleWithMatchingRun(connections);
        var registry = new IdentifierTypeRegistry(connections, SEED, IdentifierTypeRegistry.defaultValidators());
        var studio = new IdentityStudioService(connections, registry);
        var controller = new IdentityController(registry, new IdentityPolicyService(connections, registry), studio);
        identify(sql, canonical(sql, "Ram Singh", "ST1"), "h-ram-1");
        identify(sql, canonical(sql, "Ram Singh", "ST2"), "h-ram-2");
        identify(sql, canonical(sql, "Sita Singh", null), "h-shared");
        identify(sql, canonical(sql, "Anita Devi", null), "h-shared");
        identify(sql, canonical(sql, "Sunita Devi", null), "h-gender");
        identify(sql, canonical(sql, "Ramesh Kumar", null), "h-gender");
        long records = sql.count("select count(*) from canonical_resident_current");

        var preview = controller.previewIdentityPolicy(new IdentityPolicy(List.of("AADHAAR"), List.of("AADHAAR"),
                List.of(), null, 3650, true, null, null));

        assertEquals(records, preview.records());
        assertEquals(run, preview.matchingRunId());
        assertEquals(1, preview.autoMerges(), "Sita and Anita share an Aadhaar");
        assertEquals(1, preview.newAutoMerges(), "matching had not linked them");
        assertEquals(1, preview.conflicts(), "Sunita and Ramesh disagree on gender");
        assertEquals(1, preview.neverMerge(), "the two Ram Singh records hold different Aadhaar values");
        assertEquals(6, preview.coverage().get("AADHAAR"));
        assertTrue(preview.warnings().isEmpty(), preview.warnings().toString());
        assertEquals(0, sql.count("select count(*) from identity_policy"), "a preview stores nothing");
        assertEquals(0, sql.count("select count(*) from golden_resident"), "a preview builds nothing");

        var coverage = controller.identityCoverage();
        long present = coverage.sources().stream().flatMap(s -> s.types().stream())
                .filter(t -> t.type().equals("AADHAAR")).mapToLong(IdentityStudioService.IdentifierCoverage::present).sum();
        assertEquals(6, present);
        var recommendation = controller.identityRecommendation();
        assertTrue(recommendation.policy().neverMergeTypes().contains("AADHAAR"));
        var empty = controller.previewIdentityPolicy(new IdentityPolicy(List.of(), List.of(), List.of(), null, null,
                null, 0.98, null));
        assertEquals(0, empty.autoMerges());
        assertTrue(empty.warnings().stream().anyMatch(w -> w.contains("no deterministic identifier")));
    }

    private static UUID canonical(Sql sql, String name, String state) {
        return sql.uuid("select canonical_resident_id from canonical_resident_current where canonical_payload->>'name' = ?"
                + " and (cast(? as text) is null or state_code = ?) order by state_code limit 1", name, state, state);
    }

    /** Test setup only: gives a canonical record a protected Aadhaar, as E2 mapping would. */
    private static void identify(Sql sql, UUID canonical, String value) {
        sql.execute("update canonical_resident set canonical_payload = jsonb_set(canonical_payload,"
                + " '{extensions,uddp.identifiers}', coalesce(canonical_payload->'extensions'->'uddp.identifiers', '{}')"
                + " || jsonb_build_object('AADHAAR', jsonb_build_object('subject', 'PERSON', 'value', ?::text)))"
                + " where canonical_resident_id = ?", value, canonical);
    }
}
