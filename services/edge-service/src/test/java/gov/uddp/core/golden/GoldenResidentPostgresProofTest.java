package gov.uddp.core.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Builds Golden Residents on a disposable PostgreSQL 16 from the sample packs and a matching run the
 * test writes itself (Ram Singh x3 AUTO_MATCH). Counts are scoped to the v2 policy.
 */
class GoldenResidentPostgresProofTest {
    static final String VERSION = SurvivorshipPolicy.RESIDENT_V2;

    @Test
    void buildsFiveGoldenResidentsAndIsIdempotent() {
        var connections = TestDatabase.freshMigrated();
        UUID run = SampleData.processedSampleWithMatchingRun(connections);
        var service = new GoldenResidentService(connections, new ObjectMapper(), SampleData.loader(SampleData.STATE_PACKS));

        assertThrows(IllegalStateException.class, () -> service.build(UUID.randomUUID()));
        assertPendingRunIsRejected(connections, service);

        var first = service.build(run);
        assertEquals(7, first.canonicalRecordsConsidered());
        assertEquals(5, first.acceptedGroups());
        assertEquals(4, first.singletonGroups());
        assertEquals(SurvivorshipPolicy.RESIDENT_V2, first.policyVersion());
        assertEquals(5, count(connections, "select count(*) from golden_resident where matching_run_id=?"
                + " and survivorship_policy_version='" + VERSION + "'", run));
        assertEquals(7, count(connections,
                "select count(*) from golden_resident_member m"
                        + " join golden_resident g on g.golden_resident_id=m.golden_resident_id"
                        + " where g.matching_run_id=? and g.survivorship_policy_version='" + VERSION + "'", run));
        assertEquals(25, count(connections,
                "select count(*) from golden_resident_field_provenance p"
                        + " join golden_resident g on g.golden_resident_id=p.golden_resident_id"
                        + " where g.matching_run_id=? and p.survivorship_policy_version='" + VERSION + "'", run));
        assertNoMatchNeverSharesGolden(connections, run);

        var again = service.build(run);
        assertEquals(0, again.created());
        assertEquals(5, again.reused());
        assertEquals(5, service.list(run, VERSION).size());
        assertEquals(3, ramMembers(connections, run));
    }

    private static void assertPendingRunIsRejected(PostgresConnectionFactory connections, GoldenResidentService service) {
        UUID pending = UUID.randomUUID();
        try (var c = connections.open();
                var q = c.prepareStatement("insert into matching_run(run_id,model_version,threshold,status,records_considered)"
                        + " values(?,?,?,'RUNNING',0)")) {
            q.setObject(1, pending);
            q.setString(2, "test");
            q.setBigDecimal(3, BigDecimal.ONE);
            q.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            assertThrows(IllegalStateException.class, () -> service.build(pending));
        } finally {
            try (var c = connections.open(); var q = c.prepareStatement("delete from matching_run where run_id=?")) {
                q.setObject(1, pending);
                q.executeUpdate();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    static long count(PostgresConnectionFactory connections, String sql, UUID id) {
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            var r = s.executeQuery();
            r.next();
            return r.getLong(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static long ramMembers(PostgresConnectionFactory connections, UUID run) {
        String sql = "select count(*) from golden_resident_member m"
                + " join golden_resident g on g.golden_resident_id=m.golden_resident_id"
                + " join canonical_resident r on r.canonical_resident_id=m.canonical_resident_id"
                + " where g.matching_run_id=? and g.survivorship_policy_version='" + VERSION + "'"
                + " and r.canonical_payload->>'name'='Ram Singh'"
                + " group by g.golden_resident_id";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, run);
            var r = s.executeQuery();
            assertTrue(r.next());
            return r.getLong(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void assertNoMatchNeverSharesGolden(PostgresConnectionFactory connections, UUID run) {
        String sql = "select count(*) from resident_match_pair p"
                + " join golden_resident_member a on a.canonical_resident_id=p.canonical_record_a"
                + " join golden_resident_member b on b.canonical_resident_id=p.canonical_record_b"
                + " and b.golden_resident_id=a.golden_resident_id"
                + " join golden_resident g on g.golden_resident_id=a.golden_resident_id"
                + " where p.run_id=? and p.decision='NO_MATCH' and g.matching_run_id=?";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setObject(1, run);
            s.setObject(2, run);
            var r = s.executeQuery();
            r.next();
            assertEquals(0, r.getLong(1), "NO_MATCH canonical Residents must not share a Golden Resident");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
