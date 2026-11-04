package gov.uddp.core.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.stewardship.StewardshipStore;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.TestDatabase;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchingImpactServiceTest {
    private static final List<String> TABLES = List.of("matching_run", "resident_match_pair",
            "resident_match_cluster_member", "golden_resident", "stewardship_decision", "stewardship_link_constraint",
            "matching_label", "canonical_resident", "matching_profile");

    private PostgresConnectionFactory connections;
    private List<UUID> ids;
    private UUID run;
    private UUID profile;

    @BeforeEach
    void setUp() throws SQLException {
        connections = TestDatabase.freshMigrated();
        ids = seedResidents(6);
        // Baseline: r0-r1 are one person, r2-r3 are queued for review.
        run = MatchingRunFixture.completed(connections, List.of(Set.of(ids.get(0), ids.get(1))),
                List.of(Set.of(ids.get(2), ids.get(3))));
        profile = UUID.randomUUID();
        update("insert into matching_profile(profile_id,name,version,status,config,created_by)"
                + " values ('" + profile + "','resident',1,'DRAFT','{}'::jsonb,'maker')");
    }

    @Test
    void previewComparesWithTheBaselineAndWritesNothing() throws Exception {
        var before = counts();

        var impact = service(candidate(
                pair(0, 1, 0.30),   // baseline AUTO, candidate no match: a split
                pair(0, 2, 0.97)))  // baseline no match, candidate AUTO: a merge
                .preview(profile, null, null, null);

        assertEquals(run, impact.baselineRunId());
        assertEquals(1, impact.newMerges());
        assertEquals(1, impact.splits());
        assertEquals(3, impact.changedPairCount());     // plus r2-r3 leaving the review queue
        assertEquals(1, impact.baselineAutoPairs());
        assertEquals(1, impact.candidateAutoPairs());
        assertEquals(1, impact.baselineReviewQueue());
        assertEquals(0, impact.candidateReviewQueue());
        assertTrue(impact.changedPairs().stream().allMatch(p -> p.nameA().startsWith("Person ")));
        assertEquals(before, counts());
    }

    @Test
    void stewardshipDecisionsOverrideTheModel() throws Exception {
        // A steward decided r4 and r5 are different people and that r2 and r3 are the same person.
        decide("REJECT_MATCH", "CANNOT_LINK", ids.get(4), ids.get(5));
        decide("MERGE", "MUST_LINK", ids.get(2), ids.get(3));
        var before = counts();

        var impact = service(candidate(
                pair(0, 1, 0.99),
                pair(4, 5, 0.99),   // the model says same person; the CANNOT_LINK decision wins
                pair(2, 3, 0.10)))  // the model says different; the MUST_LINK decision wins
                .preview(profile, run, null, null);

        assertEquals(2, impact.constraintsApplied());
        assertEquals(0, impact.newMerges());
        assertEquals(0, impact.splits());
        assertEquals(0, impact.changedPairCount());
        assertEquals(0, impact.candidateReviewQueue());
        assertEquals(before, counts());
    }

    @Test
    void aWhatIfThresholdChangesTheReviewQueueWithoutTouchingTheProfile() {
        var impact = service(candidate(pair(2, 3, 0.80), pair(0, 4, 0.75)))
                .preview(profile, run, 0.95, 0.70);
        assertEquals(2, impact.candidateReviewQueue());

        var stricter = service(candidate(pair(2, 3, 0.80), pair(0, 4, 0.75))).preview(profile, run, 0.95, 0.78);
        assertEquals(1, stricter.candidateReviewQueue());
    }

    // ---- helpers

    private MatchingImpactService service(String candidateJson) {
        return new MatchingImpactService(connections, (module, arguments) -> {
            try {
                return new ObjectMapper().readTree(candidateJson);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, new StewardshipStore());
    }

    private String candidate(String... pairs) {
        return "{\"autoMatch\":0.95,\"possibleMatch\":0.70,\"records\":6,\"pairs\":[" + String.join(",", pairs) + "]}";
    }

    private String pair(int a, int b, double probability) {
        return "[\"" + ids.get(a) + "\",\"" + ids.get(b) + "\"," + probability + "]";
    }

    private void decide(String action, String constraint, UUID a, UUID b) throws SQLException {
        UUID decision = UUID.randomUUID();
        String ka = "canonical:" + a;
        String kb = "canonical:" + b;
        String first = ka.compareTo(kb) <= 0 ? ka : kb;
        String second = ka.compareTo(kb) <= 0 ? kb : ka;
        update("insert into stewardship_decision(decision_id, action, reviewer, reason, review_task_id)"
                + " values ('" + decision + "', 'MERGE', 'steward', 'test', null)");
        update("insert into stewardship_link_constraint(decision_id, record_key_a, record_key_b, constraint_type)"
                + " values ('" + decision + "', '" + first + "', '" + second + "', '" + constraint + "')");
    }

    private Map<String, Integer> counts() throws SQLException {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (var c = connections.open(); var s = c.createStatement()) {
            for (String table : TABLES) {
                var r = s.executeQuery("select count(*) from " + table);
                r.next();
                out.put(table, r.getInt(1));
            }
        }
        return out;
    }

    private void update(String sql) throws SQLException {
        try (var c = connections.open(); var s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private List<UUID> seedResidents(int count) throws SQLException {
        List<UUID> out = new ArrayList<>();
        UUID batch = UUID.randomUUID();
        update("insert into ingestion_batch(batch_id,state_code,source_system,source_type,state_pack_version,"
                + "pipeline_template,status) values ('" + batch + "','ST1','src','file','fixture','generic-file-ingest.hpl','COMPLETED')");
        for (int i = 0; i < count; i++) {
            UUID raw = UUID.randomUUID();
            UUID canonical = UUID.randomUUID();
            update("insert into raw_record(raw_record_id,batch_id,state_code,source_system,raw_payload,source_metadata,"
                    + "state_pack_version,pipeline_template) values ('" + raw + "','" + batch + "','ST1','src','{}','{}',"
                    + "'fixture','generic-file-ingest.hpl')");
            update("insert into canonical_resident(canonical_resident_id,raw_record_id,batch_id,state_code,source_system,"
                    + "source_entity,state_pack_version,canonical_payload) values ('" + canonical + "','" + raw + "','"
                    + batch + "','ST1','src','src','fixture','{\"name\":\"Person " + i + "\"}')");
            out.add(canonical);
        }
        return out;
    }
}
