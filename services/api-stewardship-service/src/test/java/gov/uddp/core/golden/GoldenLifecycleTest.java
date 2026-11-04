package gov.uddp.core.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M11 stable Golden identity on the sample packs (7 canonical Residents, 5 people; Ram Singh in three states), with
 * matching runs written by {@link MatchingRunFixture}. Each test asserts from the database and through the service
 * read API that IDs are carried forward, versions and events are written only for real changes, and retired IDs
 * resolve.
 */
class GoldenLifecycleTest {
    private PostgresConnectionFactory connections;
    private Sql sql;
    private GoldenResidentService v2;
    private UUID baselineRun;
    private Set<UUID> ram;
    private UUID sita;

    @BeforeEach
    void baseline() {
        connections = TestDatabase.freshMigrated();
        sql = new Sql(connections);
        baselineRun = SampleData.processedSampleWithMatchingRun(connections);
        v2 = new GoldenResidentService(connections, new ObjectMapper(), SampleData.loader(SampleData.STATE_PACKS));
        var first = v2.build(baselineRun);
        assertEquals(5, first.created());
        ram = new HashSet<>(sql.canonicalsNamed("Ram Singh"));
        sita = sql.canonicalsNamed("Sita Singh").getFirst();
        assertEquals(3, ram.size());
    }

    @Test
    void rerunningMatchingAndGoldenOnUnchangedDataKeepsEveryIdAndCreatesNoVersion() {
        String before = identities();
        long versions = sql.count("select count(*) from golden_resident_version");
        long events = sql.count("select count(*) from golden_resident_event");

        UUID rerun = MatchingRunFixture.completed(connections, List.of(ram));
        var summary = v2.build(rerun);
        var again = v2.build(baselineRun);

        for (var result : List.of(summary, again)) {
            assertEquals(0, result.created());
            assertEquals(5, result.reused());
            assertEquals(5, result.unchanged());
            assertEquals(0, result.versioned());
            assertEquals(0, result.retired());
        }
        assertEquals(before, identities());
        assertEquals(versions, sql.count("select count(*) from golden_resident_version"));
        assertEquals(5, versions);
        assertEquals(events, sql.count("select count(*) from golden_resident_event"));
        assertEquals(5, sql.count("select count(*) from golden_resident where status='ACTIVE'"));
        assertEquals(7, sql.count("select count(*) from golden_resident_membership where effective_to is null"));
        assertEquals(3, sql.count("select count(*) from golden_build"), "baseline, rerun and repeat builds");
    }

    @Test
    void mergeKeepsTheLargerIdentityAndRetiresTheOtherThenSplitGivesTheOtherSideANewId() {
        UUID ramId = sql.goldenOf(ram.iterator().next());
        UUID sitaId = sql.goldenOf(sita);
        Set<UUID> merged = new HashSet<>(ram);
        merged.add(sita);

        var merge = v2.build(MatchingRunFixture.completed(connections, List.of(merged)));

        assertEquals(1, merge.merged());
        assertEquals(1, merge.retired());
        assertEquals(0, merge.created());
        assertEquals(4, sql.count("select count(*) from golden_resident where status='ACTIVE'"));
        assertEquals(ramId, sql.goldenOf(sita), "the identity with the larger overlap (Ram x3) survives");
        assertEquals("RETIRED", sql.text("select status from golden_resident where golden_resident_id=?", sitaId));
        assertEquals(ramId, sql.uuid("select merged_into_id from golden_resident where golden_resident_id=?", sitaId));
        assertEquals(0, sql.count("select count(*) from golden_resident_version where golden_resident_id=?"
                + " and effective_to is null", sitaId));
        assertEquals(1, events(ramId, "MERGED", sitaId));
        assertEquals(1, events(sitaId, "RETIRED", ramId));
        assertEquals(1, sql.count("select count(*) from golden_resident_event where golden_resident_id=?"
                + " and event_type='MEMBER_ADDED' and canonical_resident_id=?", ramId, sita));
        assertEquals(2L, currentVersion(ramId));
        assertTrue(reasons(ramId, 2).containsAll(List.of("MERGED", "MEMBERSHIP_CHANGED")));

        var retired = v2.detail(sitaId);
        assertEquals("RETIRED", retired.get("status"));
        assertEquals(ramId, retired.get("mergedIntoId"));
        assertEquals(ramId, retired.get("resolvedGoldenResidentId"));
        assertNull(retired.get("currentVersion"));

        var split = v2.build(MatchingRunFixture.completed(connections, List.of(ram)));

        assertEquals(1, split.created());
        assertEquals(1, split.split());
        assertEquals(0, split.retired());
        assertEquals(5, sql.count("select count(*) from golden_resident where status='ACTIVE'"));
        UUID newSitaId = sql.goldenOf(sita);
        assertEquals(ramId, sql.goldenOf(ram.iterator().next()), "the side with the largest overlap keeps the ID");
        assertNotEquals(sitaId, newSitaId, "a retired ID is never reused");
        assertNotEquals(ramId, newSitaId);
        assertEquals(ramId, sql.uuid("select split_from_id from golden_resident where golden_resident_id=?", newSitaId));
        assertEquals("RETIRED", sql.text("select status from golden_resident where golden_resident_id=?", sitaId));
        assertEquals(1, events(newSitaId, "CREATED", null));
        assertEquals(1, events(newSitaId, "SPLIT", ramId));
        assertEquals(1, events(ramId, "SPLIT", newSitaId));
        assertEquals(1, sql.count("select count(*) from golden_resident_event where golden_resident_id=?"
                + " and event_type='MEMBER_REMOVED' and canonical_resident_id=?", ramId, sita));
        assertEquals(3L, currentVersion(ramId));
        assertTrue(reasons(ramId, 3).containsAll(List.of("SPLIT", "MEMBERSHIP_CHANGED")));

        @SuppressWarnings("unchecked")
        var versions = (List<Map<String, Object>>) v2.history(ramId).get("versions");
        assertEquals(List.of(3, 4, 3), versions.stream().map(v -> ((List<?>) v.get("members")).size()).toList(),
                "history keeps the members of every version");
        assertEquals(ramId, v2.detail(newSitaId).get("splitFromId"));
    }

    @Test
    void survivorshipPolicyChangeKeepsEveryIdAndCreatesNewVersions() {
        String before = identities();
        var properties = new StatePackProperties();
        properties.setPlatformDirectory(SampleData.REPOSITORY_ROOT.resolve("platform").toString());
        var v3 = new GoldenResidentService(connections, new ObjectMapper(), SampleData.loader(SampleData.STATE_PACKS),
                new PlatformSurvivorshipPolicyLoader(properties));

        var summary = v3.build(baselineRun);

        assertEquals("resident-survivorship-v3", summary.policyVersion());
        assertEquals(0, summary.created());
        assertEquals(5, summary.reused());
        assertEquals(5, summary.versioned());
        assertEquals(before, identities());
        for (UUID id : sql.column("select golden_resident_id from golden_resident", UUID.class)) {
            assertEquals(2L, currentVersion(id));
            assertTrue(reasons(id, 2).contains("POLICY_CHANGED"));
            assertEquals("resident-survivorship-v2", sql.text("select survivorship_policy_version"
                    + " from golden_resident_version where golden_resident_id=? and version_number=1", id));
        }
        assertEquals(0, sql.count("select count(*) from golden_resident_version_provenance p"
                + " join golden_resident_version v on v.golden_resident_version_id=p.golden_resident_version_id"
                + " where v.effective_to is null and p.survivorship_policy_version<>'resident-survivorship-v3'"));
        assertEquals(5, v3.list(null, "resident-survivorship-v3").size());
        assertEquals(0, v3.list(null, "resident-survivorship-v2").size());

        var unchanged = v3.build(baselineRun);
        assertEquals(5, unchanged.unchanged());
    }

    /** Every Golden ID with its status, in a stable order. */
    private String identities() {
        return String.join(",", sql.column("select golden_resident_id::text || ':' || status from golden_resident"
                + " order by golden_resident_id", String.class));
    }

    private long currentVersion(UUID id) {
        return sql.count("select version_number from golden_resident_version where golden_resident_id=?"
                + " and effective_to is null", id);
    }

    private List<String> reasons(UUID id, int version) {
        try {
            JsonNode node = new ObjectMapper().readTree(sql.text("select change_reasons::text from golden_resident_version"
                    + " where golden_resident_id=? and version_number=?", id, version));
            return java.util.stream.StreamSupport.stream(node.spliterator(), false).map(JsonNode::asText).toList();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long events(UUID id, String type, UUID related) {
        return sql.count("select count(*) from golden_resident_event where golden_resident_id=? and event_type=?"
                + " and related_golden_resident_id is not distinct from cast(? as uuid)", id, type, related);
    }
}
