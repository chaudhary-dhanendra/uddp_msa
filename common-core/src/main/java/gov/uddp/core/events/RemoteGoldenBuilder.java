package gov.uddp.core.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import gov.uddp.contracts.EventEnvelope;
import gov.uddp.contracts.Topics;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.golden.GoldenBuildSummary;
import gov.uddp.core.onboarding.GoldenBuildRunner;
import gov.uddp.messaging.Outbox;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Golden builds with registry-service (MSA Phase 3): sends cmd.registry.golden-build-requested (naming the latest
 * stewardship decision, which registry-service applies first) and waits until a new build of the run has been
 * replicated here ({@link GoldenReplica}; a build's rows arrive in one event), returning the summary registry-service
 * recorded. The read APIs then read the replicated rows as before.
 */
@Component
@Primary
@ConditionalOnProperty(name = "uddp.registry.remote", havingValue = "true")
public class RemoteGoldenBuilder implements GoldenBuildRunner {

    private final PostgresConnectionFactory connections;
    private final Duration timeout;
    private gov.uddp.core.golden.StewardshipConstraints stewardship;

    public RemoteGoldenBuilder(PostgresConnectionFactory connections,
                               @Value("${uddp.registry.remote-timeout:PT30M}") Duration timeout) {
        this.connections = connections;
        this.timeout = timeout;
    }

    /**
     * Review tasks are the edge's (M13, ADR-0010): registry-service creates none, so after each replicated build the
     * run's POSSIBLE_MATCH pairs become review tasks here, as the monolith's build creates them.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStewardship(gov.uddp.core.golden.StewardshipConstraints stewardship) {
        this.stewardship = stewardship;
    }

    @Override
    public GoldenBuildSummary build(UUID matchingRunId) {
        long before;
        try (var c = connections.open()) {
            before = builds(c, matchingRunId);
            var payload = Outbox.json().createObjectNode().put("matchingRunId", matchingRunId.toString());
            try (var s = c.prepareStatement("select decision_id::text from stewardship_decision"
                    + " order by decision_sequence desc limit 1"); var r = s.executeQuery()) {
                payload.put("afterDecisionId", r.next() ? r.getString(1) : null);
            }
            Outbox.append(c, Topics.CMD_GOLDEN_BUILD_REQUESTED, new EventEnvelope(UUID.randomUUID(),
                    "uddp.cmd.registry.golden-build-requested", 1, Instant.now(), "api-stewardship-service",
                    matchingRunId.toString(), UUID.randomUUID(), null, null, null, payload));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot request a Golden build from registry-service", e);
        }
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try (var c = connections.open()) {
                if (builds(c, matchingRunId) > before) {
                    try (var s = c.prepareStatement("select summary::text, golden_build_id from golden_build"
                            + " where matching_run_id = ? and completed_at is not null order by completed_at desc limit 1")) {
                        s.setObject(1, matchingRunId);
                        try (var r = s.executeQuery()) {
                            r.next();
                            var summary = Outbox.json().copy()
                                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                    .readValue(r.getString(1), GoldenBuildSummary.class);
                            createReviewTasks(matchingRunId, r.getObject(2, UUID.class));
                            return summary;
                        }
                    }
                }
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for registry-service", e);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read the replicated Golden build", e);
            }
        }
        throw new IllegalStateException("registry-service did not build run " + matchingRunId + " within " + timeout);
    }

    private void createReviewTasks(UUID run, UUID build) throws Exception {
        if (stewardship == null) {
            return;
        }
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try (var s = c.prepareStatement("select pg_advisory_xact_lock(hashtext('uddp.golden-resident-build'))")) {
                s.execute();
            }
            stewardship.createReviewTasks(c, run, build);
            c.commit();
        }
    }

    private static long builds(Connection c, UUID run) throws Exception {
        try (var s = c.prepareStatement("select count(*) from golden_build where matching_run_id = ?"
                + " and completed_at is not null")) {
            s.setObject(1, run);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }
}
