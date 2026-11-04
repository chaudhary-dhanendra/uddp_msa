package gov.uddp.core.events;

import gov.uddp.messaging.RowReplication;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import gov.uddp.contracts.Topics;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.golden.GoldenBuildSummary;
import gov.uddp.core.golden.GoldenResidentService;
import gov.uddp.core.onboarding.GoldenBuildRunner;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import gov.uddp.messaging.Outbox;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MSA Phase 3, api-stewardship-service: the edge holds the Golden records as a replica of registry-service's rows.
 * A registry-side build publishes golden-replica; applying it at the edge reproduces every Golden row with the same
 * ids. A steward's split at the edge publishes decision-recorded; registry-service applies those constraints, rebuilds,
 * and the new replica brings the split back to the edge: both sides then hold the same six identities.
 */
class RemoteGoldenTest {

    /** Rows the edge already holds from its other read models (Raw, canonical and matching projections). */
    private static final List<String> READ_MODELS = List.of("ingestion_batch", "raw_record", "canonical_resident",
            "canonical_resident_version", "canonical_family_observation", "canonical_family_membership", "matching_run",
            "resident_match_pair", "resident_match_cluster_member");

    @Test
    void theEdgeReplicaFollowsRegistryBuildsAndStewardDecisionsReachRegistry() throws Exception {
        var registry = TestDatabase.freshMigrated();
        var registrySql = new Sql(registry);
        var registryEvents = new PlatformEvents(registry, true, "registry-service");
        registryEvents.setGoldenReplica(true);
        var processing = SampleData.processing(registry, SampleData.loader(SampleData.STATE_PACKS));
        SampleData.landAll(registry, SampleData.STATE_PACKS).values().forEach(ids -> ids.forEach(processing::processBatch));
        UUID run = MatchingRunFixture.completed(registry, List.of(Set.copyOf(registrySql.canonicalsNamed("Ram Singh"))));
        GoldenResidentService registryGolden = SampleData.golden(registry, SampleData.loader(SampleData.STATE_PACKS));
        registryGolden.setStewardship(new RegistryStyleStewardship());
        registryGolden.setPlatformEvents(registryEvents);
        registryGolden.build(run);
        assertEquals(1, registrySql.count("select count(*) from outbox_event where topic = ?", Topics.GOLDEN_REPLICA));

        var edge = TestDatabase.freshMigrated();
        var edgeSql = new Sql(edge);
        copy(registry, edge, READ_MODELS);
        applyNewReplicas(registry, edge);
        assertEquals(golden(registrySql), golden(edgeSql));
        assertEquals(5, edgeSql.count("select count(*) from golden_resident where status = 'ACTIVE'"));
        assertEquals(registrySql.count("select count(*) from golden_resident_version_provenance"),
                edgeSql.count("select count(*) from golden_resident_version_provenance"));
        assertEquals(registrySql.count("select count(*) from golden_family_membership"),
                edgeSql.count("select count(*) from golden_family_membership"));

        // The edge's stewardship: decisions are published, and builds are registry-service's.
        var edgeEvents = new PlatformEvents(edge, true, "api-stewardship-service");
        GoldenBuildRunner remoteRegistry = matchingRun -> {
            try {
                applyNewDecisions(edge, registry);
                GoldenBuildSummary summary = registryGolden.build(matchingRun);
                applyNewReplicas(registry, edge);
                return summary;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        var stewardship = new gov.uddp.core.stewardship.StewardshipService(edge, remoteRegistry,
                new com.fasterxml.jackson.databind.ObjectMapper());
        stewardship.setEvents(edgeEvents);
        UUID ram = edgeSql.goldenOf(edgeSql.canonicalsNamed("Ram Singh").getFirst());
        String detached = edgeSql.text("select source_record_key from golden_resident_membership where"
                + " golden_resident_id = ? and effective_to is null order by source_record_key limit 1", ram);
        stewardship.split(ram, List.of(detached), "steward", "different person");

        assertEquals(1, edgeSql.count("select count(*) from outbox_event where topic = ?",
                Topics.STEWARDSHIP_DECISION_RECORDED));
        assertEquals(1, registrySql.count("select count(*) from stewardship_link_constraint_effective"
                + " where constraint_type = 'CANNOT_LINK'") > 0 ? 1 : 0);
        assertEquals(6, registrySql.count("select count(*) from golden_resident where status = 'ACTIVE'"));
        assertEquals(golden(registrySql), golden(edgeSql));
    }

    private static List<String> golden(Sql sql) {
        return sql.column("select golden_resident_id || ':' || status || ':' || coalesce((select max(version_number)"
                + " from golden_resident_version v where v.golden_resident_id = g.golden_resident_id), 0)"
                + " from golden_resident g order by 1", String.class);
    }

    private static int replicasApplied = 0;
    private static int decisionsApplied = 0;

    private static void applyNewReplicas(PostgresConnectionFactory registry, PostgresConnectionFactory edge)
            throws Exception {
        List<String> replicas = new Sql(registry).column("select payload::text from outbox_event where topic = ?"
                + " order by created_at", String.class, Topics.GOLDEN_REPLICA);
        for (String replica : replicas.subList(replicasApplied, replicas.size())) {
            JsonNode envelope = Outbox.json().readTree(replica);
            try (var c = edge.open()) {
                GoldenReplica.apply(c, envelope);
            }
        }
        replicasApplied = replicas.size();
    }

    private static void applyNewDecisions(PostgresConnectionFactory edge, PostgresConnectionFactory registry)
            throws Exception {
        List<String> decisions = new Sql(edge).column("select payload::text from outbox_event where topic = ?"
                + " order by created_at", String.class, Topics.STEWARDSHIP_DECISION_RECORDED);
        for (String decision : decisions.subList(decisionsApplied, decisions.size())) {
            try (var c = registry.open()) {
                RowReplication.apply(c, Outbox.json().readTree(decision).path("payload").path("rows"),
                        List.of("stewardship_decision", "stewardship_link_constraint"), Map.of());
            }
        }
        decisionsApplied = decisions.size();
    }

    private static void copy(PostgresConnectionFactory from, PostgresConnectionFactory to, List<String> tables)
            throws Exception {
        try (var source = from.open(); var target = to.open()) {
            var rows = RowReplication.read(source, tables.stream()
                    .map(t -> new RowReplication.Selection(t, "true", List.of())).toList());
            RowReplication.apply(target, rows, tables, Map.of());
        }
    }

    /** registry-service's stewardship part: constraints from the replicated decisions, no review tasks. */
    private static final class RegistryStyleStewardship implements gov.uddp.core.golden.StewardshipConstraints {
        private final gov.uddp.core.stewardship.StewardshipStore store = new gov.uddp.core.stewardship.StewardshipStore();

        @Override
        public List<gov.uddp.core.golden.GoldenTargetGroups.LinkConstraint> effective(java.sql.Connection c)
                throws Exception {
            return store.effective(c);
        }

        @Override
        public int createReviewTasks(java.sql.Connection c, UUID matchingRun, UUID build) {
            return 0;
        }
    }
}
