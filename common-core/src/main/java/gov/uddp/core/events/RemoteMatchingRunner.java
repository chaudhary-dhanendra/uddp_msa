package gov.uddp.core.events;

import gov.uddp.contracts.EventEnvelope;
import gov.uddp.contracts.Topics;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.onboarding.MatchingRunner;
import gov.uddp.messaging.Outbox;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The MATCHED stage with identity-service (MSA Phase 3): sends cmd.identity.matching-requested through the outbox and
 * waits until the run identity-service makes for it has been projected here as COMPLETED
 * ({@link RemoteMatchingProjection}). The onboarding stage stays synchronous, as in the monolith.
 */
@Component
@Primary
@ConditionalOnProperty(name = "uddp.identity.remote", havingValue = "true")
public class RemoteMatchingRunner implements MatchingRunner {

    private final PostgresConnectionFactory connections;
    private final Duration timeout;

    public RemoteMatchingRunner(PostgresConnectionFactory connections,
                                @Value("${uddp.identity.remote-timeout:PT30M}") Duration timeout) {
        this.connections = connections;
        this.timeout = timeout;
    }

    @Override
    public UUID run() {
        return request("FULL");
    }

    @Override
    public UUID runIncremental() {
        return request("INCREMENTAL");
    }

    private UUID request(String mode) {
        UUID correlation = UUID.randomUUID();
        try (var c = connections.open()) {
            var payload = Outbox.json().createObjectNode().put("mode", mode);
            Outbox.append(c, Topics.CMD_MATCHING_REQUESTED, new EventEnvelope(UUID.randomUUID(),
                    "uddp.cmd.identity.matching-requested", 1, Instant.now(), "common-core", correlation.toString(),
                    correlation, null, null, null, payload));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot request a matching run from identity-service", e);
        }
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try (var c = connections.open(); var s = c.prepareStatement("select run_id from matching_run"
                    + " where correlation_id = ? and status = 'COMPLETED'")) {
                s.setObject(1, correlation);
                try (var r = s.executeQuery()) {
                    if (r.next()) {
                        return r.getObject(1, UUID.class);
                    }
                }
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for identity-service", e);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read the projected matching run", e);
            }
        }
        throw new IllegalStateException("identity-service did not complete matching run " + correlation + " within "
                + timeout);
    }
}
