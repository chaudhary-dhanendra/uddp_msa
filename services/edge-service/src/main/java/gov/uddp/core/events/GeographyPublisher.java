package gov.uddp.core.events;

import gov.uddp.contracts.Topics;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.geography.GeographyReplication;
import gov.uddp.core.geography.LgdSnapshotData;
import gov.uddp.messaging.Outbox;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Publishes an LGD snapshot that changed the edge's geography master (upload or scheduled sync) to the services that
 * keep a copy (canonical-service, statepack-service), through the outbox, in ordered chunks (MSA). On with events.
 */
@Component
@ConditionalOnProperty(name = "uddp.events.enabled", havingValue = "true")
public class GeographyPublisher {

    private final PostgresConnectionFactory connections;

    public GeographyPublisher(PostgresConnectionFactory connections) {
        this.connections = connections;
    }

    public int publish(UUID snapshotId, LgdSnapshotData snapshot) {
        var envelopes = GeographyReplication.envelopes(snapshotId, snapshot, "edge-service",
                GeographyReplication.CHUNK_ROWS);
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                for (var envelope : envelopes) {
                    Outbox.append(c, Topics.GEOGRAPHY_SNAPSHOT_PUBLISHED, envelope);
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot publish the LGD snapshot " + snapshotId, e);
        }
        return envelopes.size();
    }
}
