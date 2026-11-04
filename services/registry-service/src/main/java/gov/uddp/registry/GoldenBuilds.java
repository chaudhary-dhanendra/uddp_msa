package gov.uddp.registry;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.golden.GoldenBuildSummary;
import gov.uddp.core.golden.GoldenResidentService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds Golden Residents and Families for one matching run once registry-service holds the whole run: the run is
 * COMPLETED in the projection (every announced pair and cluster member arrived) and every clustered canonical record
 * is projected. The build itself is the monolith's unchanged engine; it writes golden-resident-changed and
 * golden-build-completed to this service's outbox in its own transaction.
 */
@Service
public class GoldenBuilds {

    private final GoldenResidentService golden;
    private final PostgresConnectionFactory connections;
    private final Duration wait;

    public GoldenBuilds(GoldenResidentService golden, PostgresConnectionFactory registryConnections,
                        @Value("${uddp.registry.build-wait:PT10M}") Duration wait) {
        this.golden = golden;
        this.connections = registryConnections;
        this.wait = wait;
    }

    public GoldenBuildSummary build(UUID matchingRunId) {
        return build(matchingRunId, null);
    }

    /** As {@link #build(UUID)}, after the stewardship decision {@code afterDecisionId} (if any) has been applied here. */
    public GoldenBuildSummary build(UUID matchingRunId, UUID afterDecisionId) {
        awaitReady(matchingRunId, afterDecisionId);
        return golden.build(matchingRunId);
    }

    /** True when the run and every canonical record it clusters are here. */
    public boolean ready(UUID matchingRunId) {
        try (var c = connections.open(); var s = c.prepareStatement("select exists (select 1 from matching_run"
                + " where run_id = ? and status = 'COMPLETED') and not exists (select 1 from"
                + " resident_match_cluster_member m where m.run_id = ? and not exists (select 1 from"
                + " canonical_resident r where r.canonical_resident_id = m.canonical_record_id))")) {
            s.setObject(1, matchingRunId);
            s.setObject(2, matchingRunId);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getBoolean(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot check matching run " + matchingRunId, e);
        }
    }

    private boolean decided(UUID decisionId) {
        if (decisionId == null) {
            return true;
        }
        try (var c = connections.open(); var s = c.prepareStatement("select 1 from stewardship_decision"
                + " where decision_id = ?")) {
            s.setObject(1, decisionId);
            try (var r = s.executeQuery()) {
                return r.next();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot check stewardship decision " + decisionId, e);
        }
    }

    private void awaitReady(UUID matchingRunId, UUID afterDecisionId) {
        Instant deadline = Instant.now().plus(wait);
        while (!ready(matchingRunId) || !decided(afterDecisionId)) {
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Matching run " + matchingRunId + " is not complete in registry-service");
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for matching run " + matchingRunId, e);
            }
        }
    }
}
