package gov.uddp.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.uddp.contracts.Topics;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.golden.GoldenBuildSummary;
import gov.uddp.core.golden.GoldenResidentService;
import gov.uddp.core.golden.GoldenTargetGroups;
import gov.uddp.core.golden.StewardshipConstraints;
import gov.uddp.core.stewardship.StewardshipStore;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import gov.uddp.messaging.Outbox;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The review queue with registry-service: registry-service creates no review tasks, so the edge turns the run's
 * POSSIBLE_MATCH pairs into review tasks once the remote build has been replicated, exactly as the monolith's build
 * does, linked to the replicated build; a second build of the same run adds no duplicate task.
 */
class RemoteReviewTasksTest {

    private static final List<String> READ_MODELS = List.of("ingestion_batch", "raw_record", "canonical_resident",
            "canonical_resident_version", "canonical_family_observation", "canonical_family_membership", "matching_run",
            "resident_match_pair", "resident_match_cluster_member");

    @Test
    void theEdgeCreatesTheRunsReviewTasksAfterARemoteBuild() throws Exception {
        var registry = TestDatabase.freshMigrated();
        var registrySql = new Sql(registry);
        var registryEvents = new PlatformEvents(registry, true, "registry-service");
        registryEvents.setGoldenReplica(true);
        var processing = SampleData.processing(registry, SampleData.loader(SampleData.STATE_PACKS));
        SampleData.landAll(registry, SampleData.STATE_PACKS).values().forEach(ids -> ids.forEach(processing::processBatch));
        List<UUID> others = registrySql.column("select canonical_resident_id from canonical_resident"
                + " where canonical_payload->>'name' <> 'Ram Singh' order by canonical_resident_id", UUID.class);
        UUID run = MatchingRunFixture.completed(registry, List.of(Set.copyOf(registrySql.canonicalsNamed("Ram Singh"))),
                List.of(Set.of(others.get(0), others.get(1))));
        GoldenResidentService registryGolden = SampleData.golden(registry, SampleData.loader(SampleData.STATE_PACKS));
        registryGolden.setStewardship(new NoReviewTasks());
        registryGolden.setPlatformEvents(registryEvents);

        var edge = TestDatabase.freshMigrated();
        var edgeSql = new Sql(edge);
        copy(registry, edge);
        var builder = new RemoteGoldenBuilder(edge, Duration.ofMinutes(2));
        builder.setStewardship(new StewardshipStore());

        for (int build = 1; build <= 2; build++) {
            int commands = build;
            CompletableFuture<GoldenBuildSummary> result = CompletableFuture.supplyAsync(() -> builder.build(run));
            long deadline = System.currentTimeMillis() + 30_000;
            while (edgeSql.count("select count(*) from outbox_event where topic = ?",
                    Topics.CMD_GOLDEN_BUILD_REQUESTED) < commands) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("no golden-build-requested command");
                }
                Thread.sleep(100);
            }
            registryGolden.build(run);
            applyReplicas(registry, edge);
            assertEquals(run, result.get(60, TimeUnit.SECONDS).matchingRunId());
        }

        assertEquals(0, registrySql.count("select count(*) from stewardship_review_task"));
        assertEquals(1, edgeSql.count("select count(*) from stewardship_review_task"));
        assertEquals(1, edgeSql.count("select count(*) from stewardship_review_task t join golden_build b"
                + " using (golden_build_id) where t.matching_run_id = ? and b.matching_run_id = ?", run, run));
        assertEquals(5, edgeSql.count("select count(*) from golden_resident where status = 'ACTIVE'"));
    }

    private static int applied = 0;

    private static void applyReplicas(PostgresConnectionFactory registry, PostgresConnectionFactory edge)
            throws Exception {
        List<String> replicas = new Sql(registry).column("select payload::text from outbox_event where topic = ?"
                + " order by created_at", String.class, Topics.GOLDEN_REPLICA);
        for (String replica : replicas.subList(applied, replicas.size())) {
            try (var c = edge.open()) {
                GoldenReplica.apply(c, Outbox.json().readTree(replica));
            }
        }
        applied = replicas.size();
    }

    private static void copy(PostgresConnectionFactory from, PostgresConnectionFactory to) throws Exception {
        try (var source = from.open(); var target = to.open()) {
            var rows = RowReplication.read(source, READ_MODELS.stream()
                    .map(t -> new RowReplication.Selection(t, "true", List.of())).toList());
            RowReplication.apply(target, rows, READ_MODELS, Map.of());
        }
    }

    /** registry-service's stewardship part: constraints only, never review tasks. */
    private static final class NoReviewTasks implements StewardshipConstraints {
        private final StewardshipStore store = new StewardshipStore();

        @Override
        public List<GoldenTargetGroups.LinkConstraint> effective(Connection c) throws Exception {
            return store.effective(c);
        }

        @Override
        public int createReviewTasks(Connection c, UUID matchingRun, UUID build) {
            return 0;
        }
    }
}
