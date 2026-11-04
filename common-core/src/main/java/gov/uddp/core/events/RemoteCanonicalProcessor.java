package gov.uddp.core.events;

import gov.uddp.core.canonical.CanonicalProcessingSummary;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.onboarding.CanonicalBatchProcessor;
import gov.uddp.messaging.Outbox;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The MAPPED stage with canonical-service (MSA Phase 3): canonical-service maps the batch it received from
 * ingestion-service, and this waits until its batch-processed report has been projected here
 * ({@link RemoteCanonicalProjection}), returning the same counts. Needs uddp.ingestion.remote too, because
 * canonical-service reads Raw from ingestion-service's events.
 */
@Component
@Primary
@ConditionalOnProperty(name = "uddp.canonical.remote", havingValue = "true")
public class RemoteCanonicalProcessor implements CanonicalBatchProcessor {

    private final PostgresConnectionFactory connections;
    private final Duration timeout;

    public RemoteCanonicalProcessor(PostgresConnectionFactory connections,
                                    @Value("${uddp.canonical.remote-timeout:PT30M}") Duration timeout) {
        this.connections = connections;
        this.timeout = timeout;
    }

    @Override
    public CanonicalProcessingSummary processBatch(UUID batchId) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try (var c = connections.open(); var s = c.prepareStatement("select summary::text from"
                    + " remote_canonical_batch where batch_id = ?")) {
                s.setObject(1, batchId);
                try (var r = s.executeQuery()) {
                    if (r.next() && residentsArrived(c, batchId, Outbox.json().readTree(r.getString(1)))) {
                        var p = Outbox.json().readTree(r.getString(1));
                        return new CanonicalProcessingSummary(batchId, p.path("recordsRead").asInt(),
                                p.path("recordsMapped").asInt(), p.path("recordsSkipped").asInt(),
                                p.path("recordsFailed").asInt(), p.path("recordsHeld").asInt());
                    }
                }
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for canonical-service", e);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read the projected canonical batch", e);
            }
        }
        throw new IllegalStateException("canonical-service did not process batch " + batchId + " within " + timeout);
    }

    /** The report may come before the batch's Residents (other topic): wait until every mapped one is here. */
    private static boolean residentsArrived(java.sql.Connection c, UUID batchId,
                                            com.fasterxml.jackson.databind.JsonNode report) throws Exception {
        if (report.path("observed").asBoolean(false)) {
            try (var s = c.prepareStatement("select 1 from remote_canonical_observed where batch_id = ?")) {
                s.setObject(1, batchId);
                try (var r = s.executeQuery()) {
                    if (!r.next()) {
                        return false;
                    }
                }
            }
        }
        try (var s = c.prepareStatement("select count(*) from canonical_resident where batch_id = ?")) {
            s.setObject(1, batchId);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getLong(1) >= report.path("recordsMapped").asLong();
            }
        }
    }
}
