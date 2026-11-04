package gov.uddp.core.testsupport;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Writes a COMPLETED matching run in the shape {@code matching/run.py} persists, from decisions given as
 * test data, so Java Golden tests run without Python. Like run.py, only the latest canonical version of each source
 * record takes part. Every pair of those gets a row: AUTO_MATCH inside a group, POSSIBLE_MATCH for the given pairs,
 * NO_MATCH otherwise; every one gets a cluster (singletons included) and only AUTO_MATCH groups share one, as
 * Splink's clustering does. An {@link #incremental} run (ADR-0004) keeps only the pairs touching a record that is not
 * a current Golden member, and the clusters containing one. Splink itself is proven by the Python integration tests.
 */
public final class MatchingRunFixture {
    public static final BigDecimal AUTO_MATCH = new BigDecimal("0.95");
    public static final BigDecimal POSSIBLE_MATCH = new BigDecimal("0.75");

    private MatchingRunFixture() {
    }

    public static UUID completed(PostgresConnectionFactory connections, Collection<Set<UUID>> autoMatchGroups) {
        return completed(connections, autoMatchGroups, List.of());
    }

    public static UUID completed(PostgresConnectionFactory connections, Collection<Set<UUID>> autoMatchGroups,
                                 Collection<Set<UUID>> possibleMatchPairs) {
        return write(connections, autoMatchGroups, possibleMatchPairs, false);
    }

    /** An INCREMENTAL run: decisions only for pairs that touch a latest canonical that is not a current Golden member. */
    public static UUID incremental(PostgresConnectionFactory connections, Collection<Set<UUID>> autoMatchGroups) {
        return write(connections, autoMatchGroups, List.of(), true);
    }

    private static UUID write(PostgresConnectionFactory connections, Collection<Set<UUID>> autoMatchGroups,
                              Collection<Set<UUID>> possibleMatchPairs, boolean incremental) {
        UUID run = UUID.randomUUID();
        try (var c = connections.open()) {
            List<UUID> all = ids(c, "select canonical_resident_id from canonical_resident_current order by canonical_resident_id");
            Set<UUID> considered = incremental
                    ? new HashSet<>(ids(c, "select c.canonical_resident_id from canonical_resident_current c"
                            + " where not exists (select 1 from golden_resident_membership m"
                            + " where m.canonical_resident_id = c.canonical_resident_id and m.effective_to is null)"))
                    : new HashSet<>(all);
            Map<UUID, String> cluster = new HashMap<>();
            for (UUID id : all) {
                cluster.put(id, id.toString());
            }
            for (Set<UUID> group : autoMatchGroups) {
                String clusterId = group.stream().map(UUID::toString).sorted().findFirst().orElseThrow();
                group.forEach(id -> cluster.put(id, clusterId));
            }
            try (var s = c.prepareStatement("insert into matching_run(run_id,model_version,threshold,possible_match_threshold,"
                    + "status,records_considered,mode) values(?,'fixture',?,?,'RUNNING',?,?)")) {
                s.setObject(1, run);
                s.setBigDecimal(2, AUTO_MATCH);
                s.setBigDecimal(3, POSSIBLE_MATCH);
                s.setInt(4, considered.size());
                s.setString(5, incremental ? "INCREMENTAL" : "FULL");
                s.executeUpdate();
            }
            try (var s = c.prepareStatement("insert into resident_match_pair(run_id,canonical_record_a,canonical_record_b,"
                    + "match_probability,match_weight,decision,evidence) values(?,?,?,?,?,?,'{\"fixture\":true}'::jsonb)")) {
                for (int i = 0; i < all.size(); i++) {
                    for (int j = i + 1; j < all.size(); j++) {
                        UUID a = all.get(i);
                        UUID b = all.get(j);
                        if (!considered.contains(a) && !considered.contains(b)) {
                            continue;
                        }
                        boolean same = cluster.get(a).equals(cluster.get(b));
                        boolean possible = possibleMatchPairs.contains(Set.of(a, b));
                        s.setObject(1, run);
                        s.setObject(2, a);
                        s.setObject(3, b);
                        s.setBigDecimal(4, same ? new BigDecimal("0.99") : possible ? new BigDecimal("0.85") : new BigDecimal("0.01"));
                        s.setBigDecimal(5, same ? new BigDecimal("10") : possible ? new BigDecimal("2") : new BigDecimal("-10"));
                        s.setString(6, same ? "AUTO_MATCH" : possible ? "POSSIBLE_MATCH" : "NO_MATCH");
                        s.addBatch();
                    }
                }
                s.executeBatch();
            }
            Set<String> touched = new HashSet<>();
            considered.forEach(id -> touched.add(cluster.get(id)));
            try (var s = c.prepareStatement("insert into resident_match_cluster_member(run_id,cluster_id,canonical_record_id)"
                    + " values(?,?,?)")) {
                for (UUID id : all) {
                    if (!touched.contains(cluster.get(id))) {
                        continue;
                    }
                    s.setObject(1, run);
                    s.setString(2, cluster.get(id));
                    s.setObject(3, id);
                    s.addBatch();
                }
                s.executeBatch();
            }
            try (var s = c.prepareStatement("update matching_run set status='COMPLETED', completed_at=now() where run_id=?")) {
                s.setObject(1, run);
                s.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot write fixture matching run", e);
        }
        return run;
    }

    private static List<UUID> ids(Connection c, String sql) throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (var s = c.prepareStatement(sql); var r = s.executeQuery()) {
            while (r.next()) {
                out.add(r.getObject(1, UUID.class));
            }
        }
        return out;
    }
}
